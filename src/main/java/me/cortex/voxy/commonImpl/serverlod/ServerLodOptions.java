package me.cortex.voxy.commonImpl.serverlod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ServerLodOptions {
    private ServerLodOptions() {
    }

    static Map<String, String> parse(String rawOptions) {
        Map<String, String> options = new LinkedHashMap<>();
        if (rawOptions == null || rawOptions.isBlank()) {
            return options;
        }

        int i = 0;
        int length = rawOptions.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(rawOptions.charAt(i))) {
                i++;
            }
            if (i >= length) {
                break;
            }

            int keyStart = i;
            while (i < length && rawOptions.charAt(i) != '=' && !Character.isWhitespace(rawOptions.charAt(i))) {
                i++;
            }
            if (i >= length || rawOptions.charAt(i) != '=') {
                while (i < length && !Character.isWhitespace(rawOptions.charAt(i))) {
                    i++;
                }
                continue;
            }

            String key = rawOptions.substring(keyStart, i).toLowerCase(Locale.ROOT);
            i++; // '='
            String value;
            if (i < length && (rawOptions.charAt(i) == '"' || rawOptions.charAt(i) == '\'')) {
                char quote = rawOptions.charAt(i++);
                StringBuilder quoted = new StringBuilder();
                while (i < length) {
                    char ch = rawOptions.charAt(i++);
                    if (ch == quote) {
                        break;
                    }
                    if (ch == '\\' && i < length && rawOptions.charAt(i) == quote) {
                        quoted.append(rawOptions.charAt(i++));
                    } else {
                        quoted.append(ch);
                    }
                }
                value = quoted.toString();
            } else {
                int valueStart = i;
                while (i < length && !Character.isWhitespace(rawOptions.charAt(i))) {
                    i++;
                }
                value = stripQuotes(rawOptions.substring(valueStart, i));
            }

            options.put(key, value);
        }
        return options;
    }

    static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
