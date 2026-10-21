package com.sillage.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Money helpers. Every rupee amount in the system is a {@link BigDecimal} scaled to 2 places
 * with HALF_UP rounding — the only way GST arithmetic stays exact.
 */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final ThreadLocal<DecimalFormat> INR = ThreadLocal.withInitial(() -> {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
        symbols.setGroupingSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##,##0.00", symbols);
        format.setDecimalFormatSymbols(symbols);
        return format;
    });

    private Money() {}

    public static BigDecimal of(Object value) {
        return scale(JsonMapper.asDecimal(value));
    }

    public static BigDecimal of(String value) {
        if (Util.blank(value)) return ZERO;
        return scale(new BigDecimal(value.trim().replaceAll("[^0-9.\\-]", "")));
    }

    public static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) { return scale(nz(a).add(nz(b))); }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) { return scale(nz(a).subtract(nz(b))); }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) { return scale(nz(a).multiply(nz(b))); }

    public static BigDecimal multiply(BigDecimal a, int qty) { return scale(nz(a).multiply(BigDecimal.valueOf(qty))); }

    public static BigDecimal percent(BigDecimal amount, double pct) {
        return scale(nz(amount).multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }

    /** Percentage expressed in basis-safe form: {@code rate(18)} == 0.18 */
    public static BigDecimal rate(double percent) {
        return BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) return ZERO;
        return nz(a).divide(b, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) { return v == null ? ZERO : v; }

    public static BigDecimal max(BigDecimal a, BigDecimal b) { return nz(a).compareTo(nz(b)) >= 0 ? nz(a) : nz(b); }

    public static BigDecimal min(BigDecimal a, BigDecimal b) { return nz(a).compareTo(nz(b)) <= 0 ? nz(a) : nz(b); }

    public static boolean isPositive(BigDecimal v) { return v != null && v.signum() > 0; }

    public static boolean isZero(BigDecimal v) { return v == null || v.signum() == 0; }

    public static int compare(BigDecimal a, BigDecimal b) { return nz(a).compareTo(nz(b)); }

    /** Indian digit grouping: 12500 -&gt; {@code 12,500.00} */
    public static String format(BigDecimal amount) {
        return INR.get().format(scale(amount).doubleValue());
    }

    public static String inr(BigDecimal amount) {
        return "\u20B9" + format(amount);
    }

    /** Nearest 1.00 rounding used on printed receipts (the "round off" line). */
    public static BigDecimal roundTo(BigDecimal amount, BigDecimal step) {
        if (step == null || step.signum() == 0) return scale(amount);
        return nz(amount).divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

    public static double toDouble(BigDecimal amount) { return nz(amount).doubleValue(); }
}
