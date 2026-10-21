package com.sillage.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runtime configuration from environment variables and command-line flags.
 *
 * <pre>
 * SILLAGE_HOST      bind address            (default 0.0.0.0)
 * SILLAGE_PORT      tcp port                (default 8080)
 * SILLAGE_DATA      document store folder   (default ./data)
 * SILLAGE_WEB       front-end folder        (default ./web)
 * SILLAGE_LOG       DEBUG | INFO | WARN     (default INFO)
 * SILLAGE_TRUST_PROXY   honour X-Forwarded-* (default true — set false when exposed directly)
 * SILLAGE_SECURE_COOKIES add the Secure flag (default false, auto-enabled on https)
 *
 * flags: --port=N --host=H --data=DIR --web=DIR --reset --no-seed --dev --help
 * </pre>
 */
public record Config(
        String host,
        int port,
        Path dataDir,
        Path webDir,
        boolean trustProxy,
        boolean secureCookies,
        boolean seedIfEmpty,
        boolean resetData,
        boolean dev,
        String instanceId) {

    public static Config from(String[] args) {
        String host = env("SILLAGE_HOST", "0.0.0.0");
        int port = Util.parseInt(env("SILLAGE_PORT", "8080"), 8080);
        Path dataDir = Paths.get(env("SILLAGE_DATA", "data")).toAbsolutePath().normalize();
        Path webDir = Paths.get(env("SILLAGE_WEB", "web")).toAbsolutePath().normalize();
        boolean trustProxy = !env("SILLAGE_TRUST_PROXY", "true").equalsIgnoreCase("false");
        boolean secureCookies = env("SILLAGE_SECURE_COOKIES", "false").equalsIgnoreCase("true");
        boolean seedIfEmpty = !env("SILLAGE_SEED", "true").equalsIgnoreCase("false");
        boolean resetData = false;
        boolean dev = env("SILLAGE_DEV", "false").equalsIgnoreCase("true");

        for (String arg : args == null ? new String[0] : args) {
            String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : null;
            String key = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            switch (key) {
                case "--port", "-p" -> port = Util.parseInt(value, port);
                case "--host", "-h" -> host = Util.nz(value, host);
                case "--data" -> dataDir = Paths.get(Util.nz(value, dataDir.toString())).toAbsolutePath().normalize();
                case "--web" -> webDir = Paths.get(Util.nz(value, webDir.toString())).toAbsolutePath().normalize();
                case "--reset" -> resetData = true;
                case "--no-seed" -> seedIfEmpty = false;
                case "--dev", "--verbose" -> { dev = true; Log.setLevel(Log.Level.DEBUG); }
                case "--quiet" -> Log.setLevel(Log.Level.WARN);
                case "--trust-proxy=false" -> trustProxy = false;
                case "--secure-cookies" -> secureCookies = true;
                default -> {
                    if (!key.startsWith("-")) Log.warn("ignoring unknown argument '%s'", key);
                }
            }
        }
        if (System.getenv("SILLAGE_LOG") != null) Log.setLevel(Log.Level.valueOf(System.getenv("SILLAGE_LOG").toUpperCase()));
        if (dev) Log.setLevel(Log.Level.DEBUG);

        return new Config(host, port, dataDir, webDir, trustProxy, secureCookies, seedIfEmpty, resetData, dev,
                Util.randomToken(6));
    }

    public static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static String usage() {
        return """
                Sillage \u2014 salon management suite (pure Java, zero dependencies)

                  java -jar build/sillage.jar [options]

                Options
                  --port=8080          TCP port to listen on
                  --host=0.0.0.0       Interface to bind
                  --data=./data        Document store directory (JSON collections)
                  --web=./web          Front-end directory
                  --reset              Wipe the store and reseed demo data
                  --no-seed            Start with an empty salon instead of demo data
                  --dev                Verbose logging
                  --help               Show this message

                Environment
                  SILLAGE_HOST, SILLAGE_PORT, SILLAGE_DATA, SILLAGE_WEB,
                  SILLAGE_LOG, SILLAGE_TRUST_PROXY, SILLAGE_SECURE_COOKIES
                """;
    }
}
