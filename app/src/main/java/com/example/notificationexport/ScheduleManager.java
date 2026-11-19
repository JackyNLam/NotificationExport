package com.example.notificationexport;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.util.Calendar;

/**
 * Daily auto-export schedule. Uses an inexact repealing alarm so no special
 * alarm permission is needed on Android 12+.
 */
public final class ScheduleManager {

    public static final String PREFS_NAME = "schedule_prefs";
    public static final String K_ENABLED = "enabled";
    public static final String K_HOUR = "hour";
    public static final String K_MINUTE = "minute";
    public static final String K_LAST_EXPORT_MS = "last_export_ms";

    private static final int REQUEST_CODE = 1001;
    public static final String EXPORT_DIR_NAME = "exports";

    private ScheduleManager() {
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, false);
    }

    public static int getHour(Context ctx) {
        return prefs(ctx).getInt(K_HOUR, 23);
    }

    public static int getMinute(Context ctx) {
        return prefs(ctx).getInt(K_MINUTE, 0);
    }

    public static void save(Context ctx, boolean enabled, int hour, int minute) {
        prefs(ctx).edit()
                .putBoolean(K_ENABLED, enabled)
                .putInt(K_HOUR, hour)
                .putInt(K_MINUTE, minute)
                .apply();
        applySchedule(ctx);
    }

    public static long getLastExportMillis(Context ctx) {
        return prefs(ctx).getLong(K_LAST_EXPORT_MS, 0L);
    }

    public static void setLastExportMillis(Context ctx, long millis) {
        prefs(ctx).edit().putLong(K_LAST_EXPORT_MS, millis).apply();
    }

    /**
     * App-specific external storage: /sdcard/Android/data/&lt;pkg&gt;/files/exports.
     * Needs no runtime permission on any supported API level and is visible in most
     * file managers. Falls back to internal storage when external is unavailable.
     */
    public static File getExportDir(Context ctx) {
        File base = null;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            base = ctx.getExternalFilesDir(null);
        }
        if (base == null) base = ctx.getFilesDir();
        return new File(base, EXPORT_DIR_NAME);
    }

    public static String getExportDirDisplayPath(Context ctx) {
        return getExportDir(ctx).getAbsolutePath();
    }

    /** (Re)schedules or cancels the daily alarm. Safe to call repeatedly. */
    public static void applySchedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = pendingIntent(ctx);
        am.cancel(pi);

        if (!isEnabled(ctx)) {
            pi.cancel();
            return;
        }

        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, getHour(ctx));
        next.set(Calendar.MINUTE, getMinute(ctx));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }

        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                next.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY,
                pi);
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, AutoExportReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}