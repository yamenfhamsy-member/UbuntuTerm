package com.termux.app.ubuntu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.termux.shared.logger.Logger;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

/**
 * Activity shown the first time UbuntuTerm is started.
 *
 * <p>It downloads the official Ubuntu base image, verifies it against the pinned SHA-256 and
 * installs it into the application data directory. The terminal is only opened after the
 * installation has been completed successfully.
 */
public class UbuntuInstallerActivity extends Activity {

    private static final String LOG_TAG = "UbuntuInstallerActivity";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mStatus;
    private TextView mDetails;
    private ProgressBar mProgress;
    private Button mRetryButton;
    private Button mHostModeButton;
    private Button mRepairButton;
    private Button mResetButton;

    private volatile boolean mInstalling = false;

    /** Whether the installation is started automatically when the guest is missing. */
    private boolean mAutoInstall = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ubuntu_installer);

        mStatus = findViewById(R.id.ubuntu_status);
        mDetails = findViewById(R.id.ubuntu_details);
        mProgress = findViewById(R.id.ubuntu_progress);
        mRetryButton = findViewById(R.id.ubuntu_retry);
        mHostModeButton = findViewById(R.id.ubuntu_host_mode);
        mRepairButton = findViewById(R.id.ubuntu_repair);
        mResetButton = findViewById(R.id.ubuntu_reset);

        mRetryButton.setOnClickListener(v -> startInstallation());
        mRepairButton.setOnClickListener(v -> confirmRepair());
        mResetButton.setOnClickListener(v -> confirmReset());
        mHostModeButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, com.termux.app.TermuxActivity.class);
            intent.putExtra("com.termux.app.EXTRA_UBUNTU_HOST_MODE", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        showState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mInstalling)
            showState();
    }

    private void showState() {
        UbuntuRootFsManager.State state = UbuntuRootFsManager.getState(this);

        UbuntuRootFsManager.Source source = UbuntuRootFsManager.getSource(this);
        mDetails.setText(getString(R.string.ubuntu_installer_details,
            UbuntuConstants.UBUNTU_VERSION,
            UbuntuConstants.UBUNTU_CODENAME,
            UbuntuConstants.UBUNTU_ARCH,
            getString(source == UbuntuRootFsManager.Source.BUNDLED
                ? R.string.ubuntu_installer_source_bundled
                : R.string.ubuntu_installer_source_download)));

        if (state.installed) {
            mStatus.setText(getString(R.string.ubuntu_installer_status_ready, state.detail));
            mProgress.setProgress(100);
            mRetryButton.setEnabled(false);
            mRetryButton.setText(R.string.ubuntu_installer_open_ubuntu);
            mRetryButton.setOnClickListener(v -> openTerminal());
            mRepairButton.setVisibility(View.VISIBLE);
            mResetButton.setVisibility(View.VISIBLE);
        } else {
            mStatus.setText(getString(R.string.ubuntu_installer_status_missing, state.detail));
            mProgress.setProgress(0);
            mRetryButton.setEnabled(true);
            mRetryButton.setText(R.string.ubuntu_installer_retry);
            mRetryButton.setOnClickListener(v -> startInstallation());
            mRepairButton.setVisibility(View.GONE);
            mResetButton.setVisibility(View.GONE);
            if (mAutoInstall)
                startInstallation();
        }
    }

    /**
     * Reinstall the guest, for example when it was damaged.
     *
     * <p>The installation is atomic: the new system is prepared completely before the old one is
     * replaced, so a failed repair does not destroy the working installation.
     */
    private void confirmRepair() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.ubuntu_installer_repair)
            .setMessage(R.string.ubuntu_installer_repair_confirm)
            .setNegativeButton(R.string.ubuntu_installer_confirm_no, null)
            .setPositiveButton(R.string.ubuntu_installer_confirm_yes, (dialog, which) -> startInstallation())
            .show();
    }

    /**
     * Delete the guest system. The home directory with the files of the user is kept.
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.ubuntu_installer_reset)
            .setMessage(R.string.ubuntu_installer_reset_confirm)
            .setNegativeButton(R.string.ubuntu_installer_confirm_no, null)
            .setPositiveButton(R.string.ubuntu_installer_confirm_yes, (dialog, which) -> {
                new Thread(() -> {
                    UbuntuRootFsManager.uninstall(this);
                    Logger.logInfo(LOG_TAG, "The Ubuntu guest was removed by the user");
                    runOnUiThread(() -> {
                        mInstalling = false;
                        // The guest is not installed again automatically, the user decides when.
                        mAutoInstall = false;
                        showState();
                        mStatus.setText(R.string.ubuntu_installer_reset_done);
                    });
                }, "UbuntuTerm-reset").start();
            })
            .show();
    }

    private void openTerminal() {
        Intent intent = new Intent(this, com.termux.app.TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void startInstallation() {
        if (mInstalling)
            return;

        mInstalling = true;
        mRetryButton.setEnabled(false);
        mProgress.setProgress(0);
        mStatus.setText(R.string.ubuntu_installer_status_starting);

        new Thread(() -> UbuntuRootFsManager.install(this, new UbuntuRootFsManager.InstallListener() {
            @Override
            public void onProgress(UbuntuRootFsManager.Phase phase, int percent, String message) {
                mHandler.post(() -> {
                    mStatus.setText(message);
                    if (percent >= 0)
                        mProgress.setProgress(percent);
                    if (phase == UbuntuRootFsManager.Phase.DONE)
                        openTerminal();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                mHandler.post(() -> {
                    mInstalling = false;
                    mStatus.setText(getString(R.string.ubuntu_installer_status_error, message));
                    mRetryButton.setEnabled(true);
                    mRetryButton.setText(R.string.ubuntu_installer_retry);
                    mRetryButton.setOnClickListener(v -> startInstallation());
                });
            }
        }), "UbuntuTerm-installer").start();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (mInstalling)
            return; // Do not leave a half finished installation behind.
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
