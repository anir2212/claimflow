package dev.claimflow.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC 4180 CSV reader (quoted fields, doubled quotes, embedded newlines); no dependencies. */
final class Csv {
    private Csv() {}

    /** Returns one map per data row keyed by the header names, in file order. */
    static List<Map<String, String>> read(String text, List<String> requiredHeader) {
        List<List<String>> rows = parse(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV is empty");
        List<String> header = rows.get(0);
        if (!header.equals(requiredHeader)) {
            throw new IllegalArgumentException("CSV header must be exactly: " + String.join(",", requiredHeader));
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isEmpty()) continue;
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("CSV row " + (i + 1) + " has " + row.size()
                    + " fields; expected " + header.size());
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) values.put(header.get(c), row.get(c));
            result.add(values);
        }
        return result;
    }

    private static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String input = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV has an unterminated quoted field");
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
