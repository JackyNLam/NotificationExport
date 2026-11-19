package com.example.notificationexport;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes a one-off CSV of the real captured rows for testing.
 *
 * Deliberately isolated from the scheduled export: it uses a separate file name,
 * does not apply the filter, and never touches the auto-export cursor, so the
 * same rows can still appear in a future scheduled export.
 */
public final class DebugExport {

    private DebugExport() {
    }

    /** Returns the written file, or null when there is nothing captured yet. */
    public static File write(Context ctx) {
        File raw = new File(ctx.getFilesDir(), "notification_history.csv");
        if (!raw.exists() || raw.length() == 0 || CsvFilter.countDataRows(raw) == 0) {
            return null;
        }

        File dir = ScheduleManager.getExportDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) return null;

        String name = "debug_export_"
                + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date())
                + ".csv";
        File out = new File(dir, name);

        FilterConfig permissive = new FilterConfig();
        permissive.skipEmpty = false;

        CsvFilter.Result result = CsvFilter.writeRange(raw, out, permissive, 0L, 0L, 0);
        if (result == null || result.kept == 0) {
            out.delete();
            return null;
        }
        return out;
    }
}