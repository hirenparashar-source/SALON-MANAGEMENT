package com.sillage;

import com.sillage.core.Config;
import com.sillage.core.Log;
import com.sillage.seed.SeedData;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Entry point. Boots the composition root, starts the HTTP server on a daemon-free thread pool and
 * prints everything you need to start using it.
 *
 * <pre>
 *   javac --release 17 -d build/classes $(find src/main/java -name '*.java')
 *   java -cp build/classes com.sillage.SillageApp --port=8080
 * </pre>
 */
public final class SillageApp {

    public static final String VERSION = "1.0.0";
    public static final String APP = "SILLAGE";

    private SillageApp() {}

    public static void main(String[] args) {
        for (String arg : args == null ? new String[0] : args) {
            if ("--help".equals(arg) || "-help".equals(arg)) {
                System.out.println(Config.usage());
                return;
            }
        }

        Config config = Config.from(args);
        banner(config);

        if (config.resetData()) {
            Log.warn("--reset given: clearing %s before boot", config.dataDir());
        }

        Salon salon;
        try {
            salon = new Salon(config);
        } catch (RuntimeException e) {
            Log.error("the salon could not be assembled", e);
            System.err.println();
            System.err.println("Startup failed: " + e.getMessage());
            System.err.println("Check that " + config.dataDir() + " is writable and that no other copy is running.");
            System.exit(1);
            return;
        }

        try {
            salon.start();
        } catch (IOException e) {
            Log.error("could not bind the port", e);
            System.err.println();
            System.err.println("Could not listen on " + config.host() + ":" + config.port() + " \u2014 " + e.getMessage());
            System.err.println("Try another port:  java -cp build/classes com.sillage.SillageApp --port=8090");
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutting down");
            salon.stop();
        }, "sillage-shutdown"));

        ready(salon, config);

        // Keep the JVM alive: the HTTP server's threads are non-daemon, so this only matters for
        // the shutdown hook to have something to interrupt cleanly.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            salon.stop();
        }
    }

    private static void banner(Config config) {
        System.out.println();
        System.out.println("  \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510");
        System.out.println("  \u2502   S I L L A G E   \u00b7   maison de coiffure & beaut\u00e9        \u2502");
        System.out.println("  \u2502   salon management suite \u00b7 pure Java \u00b7 zero dependencies  \u2502");
        System.out.println("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
        System.out.println();
        System.out.println("  version " + VERSION + "  \u00b7  java " + System.getProperty("java.version")
                + "  \u00b7  data " + config.dataDir());
    }

    private static void ready(Salon salon, Config config) {
        String host = config.host().equals("0.0.0.0") || config.host().equals("::") ? "localhost" : config.host();
        String base = "http://" + host + ":" + salon.server.port();
        System.out.println();
        System.out.println("  Ready in " + salon.uptimeMillis() + " ms");
        System.out.println("  " + SeedData.summary(salon));
        System.out.println();
        System.out.println("  Landing page   " + base + "/");
        System.out.println("  Public booking " + base + "/book");
        System.out.println("  Studio console " + base + "/studio");
        System.out.println("  API health     " + base + "/api/health");
        System.out.println("  API bootstrap  " + base + "/api/bootstrap");
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local.getHostAddress() != null && !local.getHostAddress().equals("127.0.0.1")) {
                System.out.println("  On your network http://" + local.getHostAddress() + ":" + salon.server.port() + "/");
            }
        } catch (Exception ignored) {
            // a sandbox without a hostname is not worth complaining about
        }
        System.out.println();
        System.out.println("  Demo sign-in (optional \u2014 everything public works without one)");
        System.out.println("    owner     meher     / Sillage#2026");
        System.out.println("    manager   frontdesk / FrontDesk#2026");
        System.out.println("    stylist   ishaan    / Colour#2026");
        System.out.println("    client    ritika    / Ritika#2026");
        System.out.println();
        System.out.println("  Press Ctrl-C to stop. Data lives in " + config.dataDir() + " as plain JSON.");
        System.out.println();
    }
}
