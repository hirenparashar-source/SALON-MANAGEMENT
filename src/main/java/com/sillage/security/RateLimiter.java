package com.sillage.security;

import java.time.LocalDateTime;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter used to blunt credential stuffing, OTP guessing, booking spam and
 * denial-of-wallet attacks on the payment endpoint.
 *
 * <p>Each key (typically {@code ip} or {@code ip + ":" + route}) keeps a deque of hit timestamps;
 * a request is admitted when fewer than {@code limit} hits fall inside the window.
 */
public final class RateLimiter {

    private static final class Rule {
        final int limit;
        final long windowMillis;

        Rule(int limit, long windowMillis) {
            this.limit = limit;
            this.windowMillis = windowMillis;
        }
    }

    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    public RateLimiter rule(String name, int limit, long windowMillis) {
        rules.put(name, new Rule(limit, windowMillis));
        return this;
    }

    /** Outcome of an admission check. */
    public record Decision(boolean allowed, int remaining, long retryAfterSeconds, int limit, long windowMillis) {}

    public Decision check(String ruleName, String key) {
        Rule rule = rules.get(ruleName);
        if (rule == null) return new Decision(true, Integer.MAX_VALUE, 0, 0, 0);
        long now = System.currentTimeMillis();
        sweep(now);
        Deque<Long> bucket = hits.computeIfAbsent(ruleName + "|" + key, k -> new ConcurrentLinkedDeque<>());
        synchronized (bucket) {
            long cutoff = now - rule.windowMillis;
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) bucket.pollFirst();
            if (bucket.size() >= rule.limit) {
                long oldest = bucket.peekFirst();
                long retryAfter = Math.max(1, (oldest + rule.windowMillis - now + 999) / 1000);
                return new Decision(false, 0, retryAfter, rule.limit, rule.windowMillis);
            }
            bucket.addLast(now);
            return new Decision(true, rule.limit - bucket.size(), 0, rule.limit, rule.windowMillis);
        }
    }

    public boolean allow(String ruleName, String key) {
        return check(ruleName, key).allowed();
    }

    /** Forgets a key — used after a successful login so a good actor is not punished. */
    public void reset(String ruleName, String key) {
        Deque<Long> bucket = hits.remove(ruleName + "|" + key);
        if (bucket != null) bucket.clear();
    }

    public void resetAll() {
        hits.clear();
    }

    public Map<String, Object> stats() {
        return Map.of("rules", rules.size(), "trackedKeys", hits.size(), "checkedAt", LocalDateTime.now().toString());
    }

    private void sweep(long now) {
        if (now - lastSweep < 60_000) return;
        lastSweep = now;
        for (Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Deque<Long>> entry = it.next();
            Deque<Long> bucket = entry.getValue();
            synchronized (bucket) {
                if (bucket.isEmpty()) it.remove();
            }
        }
    }

    /** The default rule set: generous for browsing, tight for credentials and money. */
    public static RateLimiter salonDefaults() {
        return new RateLimiter()
                .rule("login", 6, 60_000)
                .rule("register", 5, 300_000)
                .rule("otp", 5, 300_000)
                .rule("payment", 25, 60_000)
                .rule("booking", 20, 60_000)
                .rule("search", 240, 60_000)
                .rule("api", 900, 60_000)
                .rule("export", 8, 300_000);
    }
}
