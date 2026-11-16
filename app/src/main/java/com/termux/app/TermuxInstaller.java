package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.ubuntu.UbuntuBootstrapVerifier;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        // The bootstrap that is embedded in the application is written into the cache directory
        // first, so that both ways of installing it use the same code path.
        try {
            final File embedded = File.createTempFile("bootstrap-embedded", ".zip", activity.getCacheDir());
            try (OutputStream out = new FileOutputStream(embedded)) {
                out.write(loadZipBytes());
            }
            installBootstrapArchive(activity, embedded, whenDone);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to prepare the embedded bootstrap", e);
            showBootstrapErrorDialog(activity, whenDone,
                Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
        }
    }

    /**
     * Installs a bootstrap from a zip file that the user selected, for example from the
     * storage of the device.
     *
     * <p>The file is copied into the cache directory of the application instead of being kept
     * in memory, the bootstrap is more than 30 MB large. The checksum of the archive is
     * calculated while it is copied. An archive that was not published for this project is
     * only installed after the user confirmed it.
     *
     * @param activity the activity
     * @param uri      the uri of the bootstrap zip file
     * @param whenDone run on the main thread after the installation finished
     * @param onError  run on the main thread when the archive could not be read
     */
    public static void installBootstrapFromUri(final Activity activity, final Uri uri, final Runnable whenDone,
                                                final Consumer<String> onError) {
        new Thread(() -> {
            File temporaryFile = null;
            try {
                temporaryFile = File.createTempFile("bootstrap", ".zip", activity.getCacheDir());
                final String checksum = copyUriToFile(activity, uri, temporaryFile);
                final File archive = temporaryFile;
                activity.runOnUiThread(() -> {
                    if (UbuntuBootstrapVerifier.isTrusted(checksum)) {
                        installBootstrapArchive(activity, archive, whenDone);
                        return;
                    }
                    confirmUntrustedBootstrap(activity, archive, checksum, whenDone, onError);
                });
            } catch (Exception | OutOfMemoryError e) {
                if (temporaryFile != null && !temporaryFile.delete()) temporaryFile.deleteOnExit();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the bootstrap archive", e);
                final String message = e.getMessage() != null ? e.getMessage() : e.toString();
                activity.runOnUiThread(() -> {
                    showBootstrapErrorDialog(activity, whenDone,
                        Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                    if (onError != null) onError.accept(message);
                });
            }
        }, "TermuxInstaller-bootstrap-import").start();
    }

    /**
     * Ask the user whether an archive with an unknown checksum should be installed.
     *
     * <p>The bootstrap contains the host runtime of the application, an archive that was
     * modified would be executed with the rights of the application. The checksum of the
     * archive is shown, so that it can be compared with the published one.
     */
    private static void confirmUntrustedBootstrap(final Activity activity, final File archive, final String checksum,
                                                  final Runnable whenDone, final Consumer<String> onError) {
        Logger.logWarn(LOG_TAG, "The bootstrap archive has an unknown checksum: " + checksum);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.bootstrap_untrusted_title)
            .setMessage(activity.getString(R.string.bootstrap_untrusted_message, checksum))
            .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                if (!archive.delete()) archive.deleteOnExit();
                if (onError != null) onError.accept(activity.getString(R.string.bootstrap_untrusted_aborted));
            })
            .setPositiveButton(R.string.bootstrap_untrusted_install, (dialog, which) ->
                installBootstrapArchive(activity, archive, whenDone))
            .show();
    }

    /** The size of the selected file, {@code -1} when it is unknown. */
    private static long queryContentSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    long size = cursor.getLong(column);
                    if (size > 0) return size;
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Cannot query the size of the selected file: " + e.getMessage());
        }
        // The provider does not report the size, it is taken from the file descriptor instead.
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                long size = descriptor.getStatSize();
                if (size > 0) return size;
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Cannot determine the size of the selected file: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Copy the selected file into a temporary file and calculate its checksum.
     *
     * <p>The archive is streamed, so that it does not have to be held in memory.
     *
     * @return the SHA-256 of the copied file
     */
    private static String copyUriToFile(Activity activity, Uri uri, File target) throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("The selected file cannot be read");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            out.flush();
        }
        return UbuntuBootstrapVerifier.toHex(digest.digest());
    }

    /**
     * Extracts a bootstrap zip into the termux prefix directory.
     *
     * <p>The archive is read from a file, so that it does not have to be held in memory.
     *
     * @param activity  the activity
     * @param zipFile   the bootstrap zip file
     * @param whenDone  run after the installation finished
     */
    private static void installBootstrapArchive(final Activity activity, final File zipFile, final Runnable whenDone) {
        final ProgressDialog progress = showProgressDialog(activity);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // The live prefix directory is not deleted here: it is only replaced when the
                    // extraction into the staging directory succeeded. A failed installation
                    // therefore leaves the working installation untouched.

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile), 64 * 1024))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    // The separator is the Unicode arrow U+2190, some bootstrap
                                    // archives use the ASCII form "<-" instead.
                                    String[] parts = line.split("←|<-");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                if (!isSafeZipEntryName(zipEntryName))
                                    throw new RuntimeException("Unsafe file name in the bootstrap archive: \"" + zipEntryName + "\"");
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    // The old prefix directory is only removed now, the new one is complete.
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    if (!zipFile.delete()) zipFile.deleteOnExit();
                    activity.runOnUiThread(() -> {
                        try {
                            if (progress != null) progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /**
     * Show the progress dialog of the installation.
     *
     * @return the dialog or {@code null} when the activity is gone.
     */
    private static ProgressDialog showProgressDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        try {
            return ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        } catch (WindowManager.BadTokenException e) {
            // The activity was dismissed while the dialog was created - ignore.
            return null;
        }
    }

    /**
     * Check that a member of a bootstrap archive cannot escape the prefix directory.
     */
    private static boolean isSafeZipEntryName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("/") || name.contains("\\")) return false;
        for (String part : name.split("/")) {
            if (part.equals("..")) return false;
        }
        return true;
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
