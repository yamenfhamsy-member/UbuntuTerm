package com.termux.api;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.api.apis.AudioAPI;
import com.termux.api.apis.BatteryStatusAPI;
import com.termux.api.apis.BrightnessAPI;
import com.termux.api.apis.ClipboardAPI;
import com.termux.api.apis.DialogAPI;
import com.termux.api.apis.DownloadAPI;
import com.termux.api.apis.JobSchedulerAPI;
import com.termux.api.apis.KeystoreAPI;
import com.termux.api.apis.MediaPlayerAPI;
import com.termux.api.apis.MediaScannerAPI;
import com.termux.api.apis.NotificationAPI;
import com.termux.api.apis.SAFAPI;
import com.termux.api.apis.SensorAPI;
import com.termux.api.apis.ShareAPI;
import com.termux.api.apis.TextToSpeechAPI;
import com.termux.api.apis.ToastAPI;
import com.termux.api.apis.TorchAPI;
import com.termux.api.apis.VibrateAPI;
import com.termux.api.apis.VolumeAPI;
import com.termux.api.apis.WallpaperAPI;
import com.termux.api.apis.WifiAPI;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.plugins.TermuxPluginUtils;

/**
 * Receiver handling the Termux:API requests that are sent by the guest of UbuntuTerm.
 *
 * <p>This is the {@code TermuxApiReceiver} of the official Termux:API application, embedded
 * into UbuntuTerm so that no companion application has to be installed. Only the API methods
 * that are safe and functional without additional companion applications or dangerous runtime
 * permissions are exposed.
 */
public class TermuxApiReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxApiReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        try {
            doWork(context, intent);
        } catch (Throwable t) {
            String header = "Error in " + LOG_TAG + " for " + ResultReturner.getApiMethodLabel(intent) +
                " sent by API server " + ResultReturner.getApiServerLabel(intent);
            String message = header + ":\n\n" +
                IntentUtils.getIntentString(intent) + "\n\n" +
                "Error";

            Logger.logStackTraceWithMessage(LOG_TAG, message, t);

            TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
                TermuxConstants.TERMUX_API_APP_NAME + " Error", header,
                MarkdownUtils.getMarkdownCodeForString(Logger.getMessageAndStackTraceString(message, t), true),
                false, false, true);

            ResultReturner.noteDone(this, intent);
        }
    }

    private void doWork(Context context, Intent intent) {
        String apiMethod = intent.getStringExtra("api_method");
        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra");
            return;
        }

        switch (apiMethod) {
            case "AudioInfo":
                AudioAPI.onReceive(this, context, intent);
                break;
            case "BatteryStatus":
                BatteryStatusAPI.onReceive(this, context, intent);
                break;
            case "Brightness":
                if (!Settings.System.canWrite(context)) {
                    TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.WRITE_SETTINGS);
                    Toast.makeText(context, "Please enable permission for UbuntuTerm:API", Toast.LENGTH_LONG).show();

                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    context.startActivity(settingsIntent);
                    return;
                }
                BrightnessAPI.onReceive(this, context, intent);
                break;
            case "Clipboard":
                ClipboardAPI.onReceive(this, context, intent);
                break;
            case "Dialog":
                DialogAPI.onReceive(context, intent);
                break;
            case "Download":
                DownloadAPI.onReceive(this, context, intent);
                break;
            case "JobScheduler":
                JobSchedulerAPI.onReceive(this, context, intent);
                break;
            case "Keystore":
                KeystoreAPI.onReceive(this, intent);
                break;
            case "MediaPlayer":
                MediaPlayerAPI.onReceive(context, intent);
                break;
            case "MediaScanner":
                MediaScannerAPI.onReceive(context, intent);
                break;
            case "NotificationList":
                // The notification listener service is not part of UbuntuTerm.
                Logger.logError(LOG_TAG, "The 'NotificationList' API method is not supported by UbuntuTerm");
                ResultReturner.noteDone(this, intent);
                break;
            case "Notification":
                NotificationAPI.onReceiveShowNotification(this, context, intent);
                break;
            case "NotificationChannel":
                NotificationAPI.onReceiveChannel(this, context, intent);
                break;
            case "NotificationRemove":
                NotificationAPI.onReceiveRemoveNotification(this, context, intent);
                break;
            case "SAF":
                SAFAPI.onReceive(this, context, intent);
                break;
            case "Sensor":
                SensorAPI.onReceive(context, intent);
                break;
            case "Share":
                ShareAPI.onReceive(context, intent);
                break;
            case "TextToSpeech":
                TextToSpeechAPI.onReceive(context, intent);
                break;
            case "Toast":
                ToastAPI.onReceive(context, intent);
                break;
            case "Torch":
                TorchAPI.onReceive(this, context, intent);
                break;
            case "Vibrate":
                VibrateAPI.onReceive(this, context, intent);
                break;
            case "Volume":
                VolumeAPI.onReceive(this, context, intent);
                break;
            case "Wallpaper":
                WallpaperAPI.onReceive(context, intent);
                break;
            case "WifiConnectionInfo":
                WifiAPI.onReceiveWifiConnectionInfo(this, context, intent);
                break;
            case "WifiEnable":
                WifiAPI.onReceiveWifiEnable(this, context, intent);
                break;
            default:
                Logger.logError(LOG_TAG, "Unrecognized 'api_method' extra: '" + apiMethod + "'");
                ResultReturner.noteDone(this, intent);
                break;
        }
    }

}
