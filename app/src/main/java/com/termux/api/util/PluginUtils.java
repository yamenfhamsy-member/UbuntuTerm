package com.termux.api.util;

import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_API_APP;

/**
 * Helper for the unique request codes of the {@link PendingIntent}s that are created by the
 * embedded Termux:API implementation.
 *
 * <p>Unlike the official Termux:API application, the API implementation is part of the main
 * application, so the request code counter is stored in the preferences of the application
 * itself instead of the preferences of a separate Termux:API application.
 */
public class PluginUtils {

    private static final String PREFERENCES_NAME = "com.ubutumerm.app.api";

    /**
     * Try to get the next unique {@link PendingIntent} request code that isn't already being used by
     * the app and which would create a unique {@link PendingIntent} that doesn't conflict with that
     * of any other execution commands.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the request code that should be safe to use.
     */
    public synchronized static int getLastPendingIntentRequestCode(final Context context) {
        if (context == null) return TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE;

        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            int lastPendingIntentRequestCode = preferences.getInt(
                TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE,
                TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE);

            int nextPendingIntentRequestCode = lastPendingIntentRequestCode + 1;

            if (nextPendingIntentRequestCode == Integer.MAX_VALUE || nextPendingIntentRequestCode < 0)
                nextPendingIntentRequestCode = TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE;

            preferences.edit().putInt(TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE,
                nextPendingIntentRequestCode).apply();

            return nextPendingIntentRequestCode;
        } catch (Exception e) {
            return TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE;
        }
    }

}
