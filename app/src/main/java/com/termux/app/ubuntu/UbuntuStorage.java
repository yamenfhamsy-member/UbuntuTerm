package com.termux.app.ubuntu;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access to the shared storage of the device ("/sdcard", "/storage/emulated/0").
 *
 * <p>The application declares {@code MANAGE_EXTERNAL_STORAGE} and the legacy
 * {@code READ_EXTERNAL_STORAGE}/{@code WRITE_EXTERNAL_STORAGE} permissions. The user can grant
 * the access in the settings of Android or through the application itself, both ways have to be
 * detected here:
 *
 * <ul>
 *     <li>the legacy permissions are the ones that the application asks for,</li>
 *     <li>"all files access" ({@code MANAGE_EXTERNAL_STORAGE}) is granted in the settings of
 *     Android and does not show up in the list of the granted runtime permissions.</li>
 * </ul>
 *
 * <p>The shared storage is bound into the guest, so that the paths of the guest
 * ({@code /sdcard}, {@code /storage/emulated/0}) reach the files of the device. The symlinks of
 * {@code ~/storage} are created on the host side, they are visible inside the guest because the
 * home directory of the application is the home directory of the guest.
 */
public class UbuntuStorage {

    private static final String LOG_TAG = "UbuntuStorage";

    /** The directories of the shared storage that are linked into {@code ~/storage}. */
    private static final Map<String, String> STORAGE_LINKS = new LinkedHashMap<>();

    static {
        // The names of the public directories of the shared storage. They are the values of
        // Environment.DIRECTORY_*, but those fields are not constants in the Android platform,
        // so they are not available in the unit tests.
        STORAGE_LINKS.put("shared", null);
        STORAGE_LINKS.put("downloads", "Download");
        STORAGE_LINKS.put("dcim", "DCIM");
        STORAGE_LINKS.put("pictures", "Pictures");
        STORAGE_LINKS.put("music", "Music");
        STORAGE_LINKS.put("movies", "Movies");
        STORAGE_LINKS.put("documents", "Documents");
        STORAGE_LINKS.put("podcasts", "Podcasts");
    }

    private UbuntuStorage() {
    }

    /**
     * Whether the application may read and write the shared storage of the device.
     *
     * <p>Both ways of granting the access are checked: the legacy runtime permissions and the
     * "all files access" permission, that can only be granted in the settings of Android.
     */
    public static boolean hasStoragePermission(Context context) {
        if (isLegacyStoragePermissionGranted(context))
            return true;
        return hasManageExternalStorage(context);
    }

    /** Whether the legacy {@code READ_EXTERNAL_STORAGE}/{@code WRITE_EXTERNAL_STORAGE} are granted. */
    public static boolean isLegacyStoragePermissionGranted(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether the "all files access" permission is granted. */
    public static boolean hasManageExternalStorage(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return false;
        return Environment.isExternalStorageManager();
    }

    /**
     * The root of the shared storage of the device, {@code null} when it cannot be determined or
     * when the application has no access to it.
     */
    public static String getSharedStoragePath(Context context) {
        if (!hasStoragePermission(context))
            return null;
        File storage = Environment.getExternalStorageDirectory();
        if (storage == null)
            return null;
        String path = storage.getAbsolutePath();
        // The application cannot use the storage when the directory is not accessible.
        if (!new File(path).isDirectory())
            return null;
        return path;
    }

    /**
     * The bind arguments that make the shared storage visible inside the guest.
     *
     * <p>Without the permission the application specific external files directory is bound to
     * {@code /sdcard} instead, it is always available.
     */
    public static List<String> buildGuestBinds(Context context) {
        List<String> binds = new ArrayList<>();
        String sharedStorage = getSharedStoragePath(context);
        if (sharedStorage != null) {
            // The same path is used inside the guest, so that the symlinks of "~/storage" and the
            // paths of the applications of the guest reach the files of the device.
            binds.add("--bind=" + sharedStorage + ":" + sharedStorage);
            binds.add("--bind=" + sharedStorage + ":/sdcard");
            return binds;
        }
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null && externalFiles.isDirectory())
            binds.add("--bind=" + externalFiles.getAbsolutePath() + ":/sdcard");
        return binds;
    }

    /**
     * Create the symlinks of {@code ~/storage}.
     *
     * <p>The directory is never emptied, the files of the user stay untouched and a link that
     * already exists is only replaced when it is broken or points to another directory. A single
     * failing link does not abort the setup of the other ones.
     *
     * @return the number of links that were created or repaired.
     */
    public static int setupStorageSymlinks(Context context) {
        String sharedStorage = getSharedStoragePath(context);
        if (sharedStorage == null) {
            android.util.Log.w(LOG_TAG, "The shared storage is not accessible, no symlinks are created");
            return 0;
        }

        File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
        int linked = setupStorageSymlinks(storageDir, sharedStorage);

        // The directories of the application itself, they are always available.
        linked += linkExternalDirectories(storageDir, "external", context.getExternalFilesDirs(null));
        linked += linkExternalDirectories(storageDir, "media", context.getExternalMediaDirs());

        android.util.Log.i(LOG_TAG, "Prepared " + linked + " storage links in " + storageDir);
        return linked;
    }

    /**
     * Create the symlinks of the shared storage inside the given directory.
     *
     * @param storageDir    directory that receives the links, it is created when it is missing.
     * @param sharedStorage root of the shared storage of the device.
     * @return the number of links that were created or repaired.
     */
    static int setupStorageSymlinks(File storageDir, String sharedStorage) {
        if (sharedStorage == null)
            return 0;
        if (!storageDir.isDirectory() && !storageDir.mkdirs()) {
            android.util.Log.w(LOG_TAG, "Cannot create the storage directory " + storageDir);
            return 0;
        }

        int linked = 0;
        for (Map.Entry<String, String> entry : STORAGE_LINKS.entrySet()) {
            String name = entry.getKey();
            String target = (entry.getValue() == null) ? sharedStorage
                : new File(sharedStorage, entry.getValue()).getAbsolutePath();
            if (createOrRepairLink(storageDir, name, target))
                linked++;
        }
        return linked;
    }

    /** Create a symlink or repair a broken one, returns whether the link is usable afterwards. */
    static boolean createOrRepairLink(File storageDir, String name, String target) {
        File link = new File(storageDir, name);
        try {
            if (isSymbolicLinkTo(link, target))
                return true;
            if (isSymbolicLink(link)) {
                // A link that points to another directory is replaced.
                if (!link.delete())
                    return false;
            } else if (link.exists()) {
                // A file or a directory of the user is never replaced.
                android.util.Log.w(LOG_TAG, "The path " + link + " exists and is not a link, it is kept");
                return false;
            }
            java.nio.file.Files.createSymbolicLink(link.toPath(), new File(target).toPath());
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            android.util.Log.w(LOG_TAG, "Cannot create the storage link " + link + " -> " + target, e);
            return false;
        }
    }

    /** Create the links of the application specific directories. */
    private static int linkExternalDirectories(File storageDir, String prefix, File[] dirs) {
        if (dirs == null)
            return 0;
        int linked = 0;
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null || !dir.isDirectory())
                continue;
            if (createOrRepairLink(storageDir, prefix + "-" + i, dir.getAbsolutePath()))
                linked++;
        }
        return linked;
    }

    /** Whether the file is a symlink that points to the given target. */
    private static boolean isSymbolicLinkTo(File file, String target) {
        if (!isSymbolicLink(file))
            return false;
        try {
            return java.nio.file.Files.readSymbolicLink(file.toPath()).toString().equals(target);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the file is a symbolic link. */
    private static boolean isSymbolicLink(File file) {
        return java.nio.file.Files.isSymbolicLink(file.toPath());
    }
}
