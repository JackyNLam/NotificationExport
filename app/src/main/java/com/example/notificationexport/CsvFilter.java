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

        Result(int kept, int skipped, long newestMillis) {
            this.kept = kept;
            this.skipped = skipped;
            this.newestMillis = newestMillis;
        }
    }

    private CsvFilter() {
    }

    /**
     * Writes rows matching {@code cfg} with a timestamp strictly after {@code sinceMillis}
     * (pass 0 to include everything). Returns null when nothing was written.
     */
    public static Result writeSince(File raw, File out, FilterConfig cfg, long sinceMillis) {
        if (raw == null || !raw.exists()) return null;

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        int kept = 0;
        int skipped = 0;
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

                long rowMillis = 0L;
                try {
                    Date d = sdf.parse(parts[0].trim());
                    if (d != null) rowMillis = d.getTime();
                } catch (Exception ignored) {
                    // keep rowMillis at 0 so unparsable rows are treated as "old"
                }

                if (sinceMillis > 0 && rowMillis <= sinceMillis) continue;

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
        return new Result(kept, skipped, newest);
    }

    /** Newest timestamp in the raw capture log, or 0 when the log is missing/empty. */
    public static long lastTimestampMillis(android.content.Context ctx) {
        File raw = new File(ctx.getFilesDir(), "notification_history.csv");
        if (!raw.exists()) return 0L;

        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        long newest = 0L;
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
                    if (d != null && d.getTime() > newest) newest = d.getTime();
                } catch (Exception ignored) {
                    // skip unparsable row
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return newest;
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