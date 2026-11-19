package com.example.notificationexport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Applies {@link FilterConfig} rules to the raw capture log and writes a UTF-8-BOM CSV. */
public final class CsvFilter {

    public static final String HEADER = "Timestamp,Package,Title,Text";
    public static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** Result of a filtered write. */
    public static final class Result {
        public final int kept;
        public final int skipped;
        public final long newestMillis;
        /** Number of data rows read from the raw log (used as the auto-export cursor). */
        public final int totalRows;

        Result(int kept, int skipped, long newestMillis, int totalRows) {
            this.kept = kept;
            this.skipped = skipped;
            this.newestMillis = newestMillis;
            this.totalRows = totalRows;
        }
    }

    private CsvFilter() {
    }

    /**
     * Writes rows that match {@code cfg} and fall inside the period.
     *
     * @param sinceMillis keep rows strictly newer than this; 0 = no lower bound
     * @param untilMillis keep rows at or before this; 0 = no upper bound
     * @param skipRows    ignore this many data rows from the top (the auto-export cursor,
     *                    which guarantees a row is never exported twice, even when two
     *                    notifications share the same one-second timestamp)
     * @return null when nothing usable was written
     */
    public static Result writeRange(File raw, File out, FilterConfig cfg,
                                    long sinceMillis, long untilMillis, int skipRows) {
        if (raw == null || !raw.exists()) return null;

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        int kept = 0;
        int skipped = 0;
        int totalRows = 0;
        long newest = 0L;
        boolean wroteFile = false;

        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(raw), StandardCharsets.UTF_8));
             OutputStreamWriter writer = new OutputStreamWriter(
                     new FileOutputStream(out), StandardCharsets.UTF_8)) {

            writer.write('\uFEFF');
            writer.write(HEADER);
            writer.write("\n");

            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // header (may carry a BOM)
                if (line.trim().isEmpty()) continue;

                String[] parts = parseCsvLine(line);
                if (parts.length < 4) continue;

                int rowIndex = totalRows++;
                if (rowIndex < skipRows) continue;

                long rowMillis = 0L;
                try {
                    Date d = sdf.parse(parts[0].trim());
                    if (d != null) rowMillis = d.getTime();
                } catch (Exception ignored) {
                    // unparsable timestamp: treated as out of range by the checks below
                }

                if (sinceMillis > 0 && rowMillis < sinceMillis) continue;
                if (untilMillis > 0 && rowMillis > untilMillis) continue;

                if (!cfg.matches(parts[1].trim(), parts[2].trim(), parts[3].trim())) {
                    skipped++;
                    continue;
                }

                writer.write(line);
                writer.write("\n");
                kept++;
                if (rowMillis > newest) newest = rowMillis;
                wroteFile = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        if (!wroteFile) {
            // Header only — not a useful export.
            out.delete();
            return null;
        }
        return new Result(kept, skipped, newest, totalRows);
    }

    /** Newest timestamp in the raw capture log, or 0 when the log is missing/empty. */
    public static long lastTimestampMillis(android.content.Context ctx) {
        return boundaryTimestamp(ctx, false);
    }

    /** Oldest timestamp in the raw capture log, or 0 when the log is missing/empty. */
    public static long firstTimestampMillis(android.content.Context ctx) {
        return boundaryTimestamp(ctx, true);
    }

    private static long boundaryTimestamp(android.content.Context ctx, boolean oldest) {
        File raw = new File(ctx.getFilesDir(), "notification_history.csv");
        if (!raw.exists()) return 0L;

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        long result = 0L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.trim().isEmpty()) continue;
                String[] parts = parseCsvLine(line);
                if (parts.length == 0) continue;
                try {
                    Date d = sdf.parse(parts[0].trim());
                    if (d == null) continue;
                    long t = d.getTime();
                    if (result == 0L || (oldest ? t < result : t > result)) result = t;
                } catch (Exception ignored) {
                    // skip unparsable row
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /** Same field parser as the main screen: handles quotes, escaped quotes and embedded commas. */
    public static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null || line.isEmpty()) return new String[0];
        int i = 0;
        int len = line.length();
        while (i < len) {
            if (line.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
                if (i < len && line.charAt(i) == ',') i++;
            } else {
                int comma = line.indexOf(',', i);
                if (comma == -1) {
                    fields.add(line.substring(i));
                    i = len;
                } else {
                    fields.add(line.substring(i, comma));
                    i = comma + 1;
                }
            }
        }
        return fields.toArray(new String[0]);
    }
}