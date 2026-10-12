package com.paytmmoney.seatreservation.burst;

import java.util.Map;

/** Reads whole-number samples from Prometheus text format; enough to diff counters before and after a run. */
final class PrometheusText {

    private PrometheusText() {}

    /** Sum of samples of {@code metric} whose labels include all of {@code labels}; 0 if none. */
    static long value(String scrape, String metric, Map<String, String> labels) {
        long total = 0;
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(metric)) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            String series = line.substring(0, space);
            String name = series.contains("{") ? series.substring(0, series.indexOf('{')) : series;
            boolean labelsMatch =
                    labels.entrySet().stream().allMatch(e -> series.contains(e.getKey() + "=\"" + e.getValue() + "\""));
            if (name.equals(metric) && labelsMatch) {
                total += wholeNumber(line.substring(space + 1).trim());
            }
        }
        return total;
    }

    private static long wholeNumber(String text) {
        int dot = text.indexOf('.');
        String integral = dot < 0 ? text : text.substring(0, dot);
        if (dot >= 0 && !text.substring(dot + 1).chars().allMatch(c -> c == '0')) {
            throw new IllegalArgumentException("Not a whole number: " + text);
        }
        return Long.parseLong(integral);
    }
}
