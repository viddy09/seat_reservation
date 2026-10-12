package com.paytmmoney.seatreservation.support;

import java.util.Map;

/**
 * Minimal reader for Prometheus text exposition, enough to assert counter and gauge values in tests. Values are read
 * as whole numbers (every metric asserted here is a count), which also keeps floating point out of the codebase.
 */
public final class PrometheusText {

    private PrometheusText() {}

    /** Sum of all samples of {@code metric} whose labels include every entry of {@code labels}; 0 if none. */
    public static long value(String scrape, String metric, Map<String, String> labels) {
        long total = 0;
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(metric)) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            String series = line.substring(0, space);
            String name = series.contains("{") ? series.substring(0, series.indexOf('{')) : series;
            if (!name.equals(metric)
                    || !labels.entrySet().stream()
                            .allMatch(e -> series.contains(e.getKey() + "=\"" + e.getValue() + "\""))) {
                continue;
            }
            total += wholeNumber(line.substring(space + 1).trim());
        }
        return total;
    }

    public static long value(String scrape, String metric) {
        return value(scrape, metric, Map.of());
    }

    private static long wholeNumber(String text) {
        int dot = text.indexOf('.');
        if (dot < 0) {
            return Long.parseLong(text);
        }
        if (!text.substring(dot + 1).chars().allMatch(c -> c == '0')) {
            throw new IllegalArgumentException("Not a whole number: " + text);
        }
        return Long.parseLong(text.substring(0, dot));
    }
}
