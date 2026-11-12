package com.termux.app.ubuntu;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Activity that lets the user import a Termux bootstrap into the application.
 *
 * <p>UbuntuTerm is shipped without a bootstrap, because the bootstrap has to match the
 * application id of the fork. The user therefore imports the matching bootstrap archive
 * (a {@code bootstrap-aarch64.zip}) from the storage of the device. The archive is installed
 * into the application prefix directory, exactly like the bootstrap that is embedded in the
 * official Termux application.
 */
public class UbuntuBootstrapImportActivity extends Activity {

    private static final String LOG_TAG = "UbuntuBootstrapImport";

    private static final int REQUEST_PICK_BOOTSTRAP = 0x4201;

    private TextView mStatus;
    private Button mPickButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ubuntu_bootstrap_import);

        mStatus = findViewById(R.id.bootstrap_import_status);
        mPickButton = findViewById(R.id.bootstrap_import_pick);

        mPickButton.setOnClickListener(v -> pickBootstrap());
    }

    private void pickBootstrap() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_PICK_BOOTSTRAP);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No file picker available", e);
            mStatus.setText(getString(R.string.bootstrap_import_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_BOOTSTRAP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        mStatus.setText(R.string.bootstrap_import_installing);
        mPickButton.setEnabled(false);

        new Thread(() -> {
            File tempFile = null;
            try {
                tempFile = new File(getCacheDir(), "imported-bootstrap.zip");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tempFile)) {
                    if (in == null) throw new IOException("The selected file cannot be read");
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0)
                        out.write(buffer, 0, read);
                    out.flush();
                }

                TermuxInstaller.installBootstrapFromFile(this, tempFile, this::onBootstrapInstalled);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import the bootstrap", e);
                runOnUiThread(() -> {
                    mStatus.setText(getString(R.string.bootstrap_import_failed, String.valueOf(e.getMessage())));
                    mPickButton.setEnabled(true);
                });
            } finally {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
            }
        }, "UbuntuTerm-bootstrap-import").start();
    }

    private void onBootstrapInstalled() {
        runOnUiThread(() -> {
            if (!UbuntuSessionLauncher.isHostRuntimeReady()) {
                mStatus.setText(R.string.bootstrap_import_incomplete);
                mPickButton.setEnabled(true);
                return;
            }
            mStatus.setText(R.string.bootstrap_import_done);
            Intent intent = new Intent(this, UbuntuInstallerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
    }
}
