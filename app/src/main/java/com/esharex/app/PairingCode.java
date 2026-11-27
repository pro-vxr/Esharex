package com.esharex.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** One permanent group code, shared by every phone through the host. */
final class PairingCode {
    private static final String PREFS = "esharex_pairing";
    private static final String KEY = "group_code";
    private static final Object LOCK = new Object();

    private PairingCode() {}

    static String get(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String code = prefs.getString(KEY, "");
            if (!code.matches("[0-9]{6}")) {
                code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1000000));
                prefs.edit().putString(KEY, code).apply();
            }
            return code;
        }
    }

    /** When this phone first became a host. The oldest host stays the host; newer phones join it. */
    static long since(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long since = prefs.getLong("host_since", 0L);
            if (since <= 0L) { since = System.currentTimeMillis(); prefs.edit().putLong("host_since", since).apply(); }
            return since;
        }
    }

    /** Take over another phone's group code so every phone in the group shows the same one. */
    static void adopt(Context context, String code) {
        if (code == null || !code.matches("[0-9]{6}")) return;
        synchronized (LOCK) {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply();
        }
    }
}
