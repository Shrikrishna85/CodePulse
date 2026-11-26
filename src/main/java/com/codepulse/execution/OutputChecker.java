package com.codepulse.execution;

public final class OutputChecker {

    private OutputChecker() {}

    public static boolean matches(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    static String normalize(String output) {
        String normalizedNewlines = output.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedNewlines.split("\n", -1);
        StringBuilder normalized = new StringBuilder(normalizedNewlines.length());
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (lineIndex > 0) {
                normalized.append('\n');
            }
            String line = lines[lineIndex];
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            normalized.append(line, 0, end);
        }
        return normalized.toString();
    }
}
