package com.termux.app.ubuntu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

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

    private volatile boolean mInstalling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ubuntu_installer);

        mStatus = findViewById(R.id.ubuntu_status);
        mDetails = findViewById(R.id.ubuntu_details);
        mProgress = findViewById(R.id.ubuntu_progress);
        mRetryButton = findViewById(R.id.ubuntu_retry);
        mHostModeButton = findViewById(R.id.ubuntu_host_mode);

        mRetryButton.setOnClickListener(v -> startInstallation());
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
        } else {
            mStatus.setText(getString(R.string.ubuntu_installer_status_missing, state.detail));
            mProgress.setProgress(0);
            mRetryButton.setEnabled(true);
            mRetryButton.setText(R.string.ubuntu_installer_retry);
            mRetryButton.setOnClickListener(v -> startInstallation());
            startInstallation();
        }
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
