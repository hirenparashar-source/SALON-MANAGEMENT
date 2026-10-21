package com.sillage.core;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal levelled logger: no dependencies, ISO timestamps, optional ANSI colour on a TTY. */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Level threshold = Level.valueOf(System.getenv().getOrDefault("SILLAGE_LOG", "INFO").toUpperCase());
    private static final boolean COLOR = System.console() != null && !"1".equals(System.getenv("NO_COLOR"));
    private static final PrintStream OUT = System.out;

    private Log() {}

    public static void setLevel(Level level) { threshold = level; }

    public static Level level() { return threshold; }

    public static void debug(String message, Object... args) { log(Level.DEBUG, message, args); }
    public static void info(String message, Object... args)  { log(Level.INFO, message, args); }
    public static void warn(String message, Object... args)  { log(Level.WARN, message, args); }
    public static void error(String message, Object... args) { log(Level.ERROR, message, args); }

    public static void error(String message, Throwable t) {
        StringBuilder sb = new StringBuilder(message).append(" :: ")
                .append(t.getClass().getName()).append(": ").append(t.getMessage());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(6, frames.length); i++) {
            sb.append("\n        at ").append(frames[i]);
        }
        if (frames.length > 6) sb.append("\n        \u2026 ").append(frames.length - 6).append(" more frames");
        log(Level.ERROR, sb.toString());
    }

    private static void log(Level level, String message, Object... args) {
        if (level.ordinal() < threshold.ordinal()) return;
        String text = args == null || args.length == 0 ? message : String.format(message.replace("{}", "%s"), args);
        String line = TS.format(LocalDateTime.now()) + "  " + pad(level.name()) + "  " + text;
        synchronized (Log.class) {
            if (COLOR) OUT.println(color(level) + line + "\u001B[0m");
            else OUT.println(line);
            OUT.flush();
        }
    }

    private static String color(Level level) {
        return switch (level) {
            case DEBUG -> "\u001B[90m";
            case INFO  -> "\u001B[36m";
            case WARN  -> "\u001B[33m";
            case ERROR -> "\u001B[31m";
        };
    }

    private static String pad(String s) {
        return s.length() >= 5 ? s : s + " ".repeat(5 - s.length());
    }
}
