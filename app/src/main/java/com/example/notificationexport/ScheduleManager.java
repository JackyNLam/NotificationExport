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
 * Auto-export schedule. Fires once every N days at the chosen clock time, using
 * a single inexact alarm that is re-armed after each run (no exact-alarm
 * permission needed, and the interval can be any number of days).
 */
public final class ScheduleManager {

    public static final String PREFS_NAME = "schedule_prefs";
    public static final String K_ENABLED = "enabled";
    public static final String K_HOUR = "hour";
    public static final String K_MINUTE = "minute";
    public static final String K_INTERVAL_DAYS = "interval_days";
    public static final String K_NEXT_FIRE = "next_fire_ms";
    public static final String K_EXPORTED_ROWS = "exported_rows";
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

    public static int getIntervalDays(Context ctx) {
        int days = prefs(ctx).getInt(K_INTERVAL_DAYS, 1);
        return days < 1 ? 1 : days;
    }

    public static void save(Context ctx, boolean enabled, int hour, int minute, int intervalDays) {
        prefs(ctx).edit()
                .putBoolean(K_ENABLED, enabled)
                .putInt(K_HOUR, hour)
                .putInt(K_MINUTE, minute)
                .putInt(K_INTERVAL_DAYS, Math.max(1, intervalDays))
                // Drop the stored fire time so a new time/interval takes effect now.
                .remove(K_NEXT_FIRE)
                .apply();
        applySchedule(ctx);
    }

    /** Cursor: how many rows of the raw log have already been written to an export. */
    public static int getExportedRowCount(Context ctx) {
        return prefs(ctx).getInt(K_EXPORTED_ROWS, 0);
    }

    public static void setExportedRowCount(Context ctx, int rows) {
        prefs(ctx).edit().putInt(K_EXPORTED_ROWS, Math.max(0, rows)).apply();
    }

    public static long getLastExportMillis(Context ctx) {
        return prefs(ctx).getLong(K_LAST_EXPORT_MS, 0L);
    }

    public static void setLastExportMillis(Context ctx, long millis) {
        prefs(ctx).edit().putLong(K_LAST_EXPORT_MS, millis).apply();
    }

    /** Called when the log is cleared: the cursor must restart from zero. */
    public static void resetCursor(Context ctx) {
        prefs(ctx).edit()
                .putInt(K_EXPORTED_ROWS, 0)
                .putLong(K_LAST_EXPORT_MS, 0L)
                .apply();
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

    /** Milliseconds of the next scheduled run, or 0 when not scheduled. */
    public static long getNextFireMillis(Context ctx) {
        return prefs(ctx).getLong(K_NEXT_FIRE, 0L);
    }

    /** (Re)arms the next one-shot alarm. Safe to call repeatedly. */
    public static void applySchedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = pendingIntent(ctx);
        am.cancel(pi);

        if (!isEnabled(ctx)) {
            prefs(ctx).edit().remove(K_NEXT_FIRE).apply();
            pi.cancel();
            return;
        }

        long now = System.currentTimeMillis();
        long next = prefs(ctx).getLong(K_NEXT_FIRE, 0L);
        if (next <= now) {
            next = nextClockTime(now, getHour(ctx), getMinute(ctx));
        }
        prefs(ctx).edit().putLong(K_NEXT_FIRE, next).apply();

        am.set(AlarmManager.RTC_WAKEUP, next, pi);
    }

    /** Advances the schedule past the run that just happened, then re-arms. */
    public static void onAlarmFired(Context ctx) {
        long now = System.currentTimeMillis();
        long step = getIntervalDays(ctx) * AlarmManager.INTERVAL_DAY;
        long next = prefs(ctx).getLong(K_NEXT_FIRE, now) + step;
        if (next <= now) {
            next = nextClockTime(now, getHour(ctx), getMinute(ctx));
        }
        prefs(ctx).edit().putLong(K_NEXT_FIRE, next).apply();
        applySchedule(ctx);
    }

    private static long nextClockTime(long now, int hour, int minute) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(now);
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= now) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
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