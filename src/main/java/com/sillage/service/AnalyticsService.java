package com.sillage.service;

import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.Customer;
import com.sillage.model.Expense;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.Payment;
import com.sillage.model.Product;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reporting: revenue, chair utilisation, retention, channel mix, RFM segments and a deliberately
 * simple forecast (weekday-weighted moving average \u2014 explainable, no model).
 */
public final class AnalyticsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM");

    private final Repo<Invoice> invoices;
    private final Repo<Appointment> appointments;
    private final Repo<Customer> customers;
    private final Repo<ServiceItem> services;
    private final Repo<Staff> staff;
    private final Repo<Product> products;
    private final Repo<Expense> expenses;

    public AnalyticsService(Repo<Invoice> invoices, Repo<Appointment> appointments, Repo<Customer> customers,
                            Repo<ServiceItem> services, Repo<Staff> staff, Repo<Product> products, Repo<Expense> expenses) {
        this.invoices = invoices;
        this.appointments = appointments;
        this.customers = customers;
        this.services = services;
        this.staff = staff;
        this.products = products;
        this.expenses = expenses;
    }

    public LocalDate[] rangeOf(String range) {
        LocalDate to = LocalDate.now();
        LocalDate from = switch (Util.nz(range, "30d")) {
            case "7d", "week" -> to.minusDays(6);
            case "30d", "month" -> to.minusDays(29);
            case "90d", "quarter" -> to.minusDays(89);
            case "365d", "year" -> to.minusDays(364);
            case "mtd" -> to.withDayOfMonth(1);
            case "ytd" -> to.withDayOfYear(1);
            default -> to.minusDays(29);
        };
        return new LocalDate[]{from, to};
    }

    /** Headline numbers for the console home page, with the previous period for comparison. */
    public Map<String, Object> overview(String range) {
        LocalDate[] window = rangeOf(range);
        LocalDate from = window[0];
        LocalDate to = window[1];
        int span = (int) ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(span);
        LocalDate previousTo = from.minusDays(1);

        Map<String, Object> current = window(from, to);
        Map<String, Object> previous = window(previousFrom, previousTo);

        Map<String, Object> out = new LinkedHashMap<>(current);
        out.put("range", Util.map("from", from.toString(), "to", to.toString(), "days", span, "label", labelFor(range)));
        out.put("previous", previous);
        out.put("deltas", deltas(current, previous));
        out.put("series", series(from, to, span > 90 ? "week" : "day"));
        out.put("topServices", servicePerformance(from, to, 6));
        out.put("staffPerformance", staffPerformance(from, to));
        out.put("channelMix", channelMix(from, to));
        out.put("generatedAt", LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> window(LocalDate from, LocalDate to) {
        List<Invoice> bills = invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to) && i.status() != InvoiceStatus.VOID);
        List<Appointment> bookings = appointments.where(a -> a.start() != null && !a.start().toLocalDate().isBefore(from)
                && !a.start().toLocalDate().isAfter(to));

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal tips = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal retail = BigDecimal.ZERO;
        BigDecimal serviceRevenue = BigDecimal.ZERO;
        for (Invoice invoice : bills) {
            revenue = Money.add(revenue, invoice.total());
            tax = Money.add(tax, invoice.taxTotal());
            discounts = Money.add(discounts, invoice.discount());
            tips = Money.add(tips, invoice.gratuity());
            outstanding = Money.add(outstanding, invoice.balance());
            for (Payment payment : Util.nullSafe(invoice.payments())) {
                if (payment.refunded() != null) refunds = Money.add(refunds, payment.refunded());
            }
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                if (Invoice.Line.PRODUCT.equals(line.kind())) retail = Money.add(retail, line.amount());
                if (Invoice.Line.SERVICE.equals(line.kind()) || Invoice.Line.ADDON.equals(line.kind())) {
                    serviceRevenue = Money.add(serviceRevenue, line.amount());
                }
            }
        }

        List<Expense> costs = expenses.where(e -> e.date() != null && !e.date().isBefore(from) && !e.date().isAfter(to));
        BigDecimal expenseTotal = costs.stream().map(Expense::amount).reduce(BigDecimal.ZERO, Money::add);
        BigDecimal stockValue = products.all().stream().map(Product::stockValue).reduce(BigDecimal.ZERO, Money::add);

        long completed = bookings.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count();
        long cancelled = bookings.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count();
        long noShows = bookings.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count();
        long newCustomers = customers.countWhere(c -> c.createdAt() != null && !c.createdAt().toLocalDate().isBefore(from)
                && !c.createdAt().toLocalDate().isAfter(to));
        long returning = bookings.stream().filter(a -> Util.present(a.customerId())).map(Appointment::customerId).distinct().count();

        int chairMinutes = bookings.stream().filter(a -> a.status().occupiesChair()).mapToInt(Appointment::durationMinutes).sum();
        int capacity = capacityMinutes(from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revenue", Money.subtract(revenue, refunds));
        out.put("gross", revenue);
        out.put("taxCollected", tax);
        out.put("discountsGiven", discounts);
        out.put("tips", tips);
        out.put("refunds", refunds);
        out.put("outstanding", outstanding);
        out.put("retailRevenue", retail);
        out.put("serviceRevenue", serviceRevenue);
        out.put("retailShare", revenue.signum() == 0 ? 0 : Util.percent(Money.toDouble(retail), Money.toDouble(revenue)));
        out.put("expenses", expenseTotal);
        out.put("estimatedProfit", Money.subtract(Money.subtract(revenue, refunds), expenseTotal));
        out.put("stockValue", stockValue);
        out.put("invoices", bills.size());
        out.put("averageTicket", bills.isEmpty() ? BigDecimal.ZERO : Money.divide(revenue, BigDecimal.valueOf(bills.size())));
        out.put("appointments", bookings.size());
        out.put("completed", completed);
        out.put("cancelled", cancelled);
        out.put("noShows", noShows);
        out.put("noShowRate", completed + noShows == 0 ? 0 : Util.percent(noShows, completed + noShows));
        out.put("cancellationRate", bookings.isEmpty() ? 0 : Util.percent(cancelled, bookings.size()));
        out.put("newCustomers", newCustomers);
        out.put("uniqueClients", returning);
        out.put("chairMinutes", chairMinutes);
        out.put("capacityMinutes", capacity);
        out.put("occupancy", capacity == 0 ? 0 : Util.percent(chairMinutes, capacity));
        out.put("revenuePerChairHour", capacity == 0 ? BigDecimal.ZERO
                : Money.divide(Money.subtract(revenue, refunds), BigDecimal.valueOf(capacity / 60.0)));
        return out;
    }

    private int capacityMinutes(LocalDate from, LocalDate to) {
        int total = 0;
        for (Staff member : staff.where(Staff::active)) {
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                Staff.DayHours hours = member.hoursFor(date.getDayOfWeek());
                if (hours == null || hours.closed() || member.offOn(date)) continue;
                total += StaffService.capacityMinutes(hours);
            }
        }
        return total;
    }

    private Map<String, Object> deltas(Map<String, Object> current, Map<String, Object> previous) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("revenue", "invoices", "averageTicket", "appointments", "newCustomers",
                "occupancy", "noShowRate", "retailShare", "estimatedProfit")) {
            Object now = current.get(key);
            Object before = previous.get(key);
            if (now instanceof BigDecimal a && before instanceof BigDecimal b) {
                double pct = b.signum() == 0 ? (a.signum() == 0 ? 0 : 100) : Util.percent(Money.toDouble(Money.subtract(a, b)), Money.toDouble(b));
                out.put(key, Util.map("value", a, "previous", b, "changePct", pct, "direction", direction(pct)));
            } else if (now instanceof Number a && before instanceof Number b) {
                double pct = b.doubleValue() == 0 ? (a.doubleValue() == 0 ? 0 : 100) : Util.percent(a.doubleValue() - b.doubleValue(), b.doubleValue());
                out.put(key, Util.map("value", a, "previous", b, "changePct", pct, "direction", direction(pct)));
            }
        }
        return out;
    }

    private static String direction(double pct) {
        if (pct > 1) return "up";
        if (pct < -1) return "down";
        return "flat";
    }

    /** Revenue and volume over time, grouped by day or week. */
    public List<Map<String, Object>> series(LocalDate from, LocalDate to, String granularity) {
        boolean weekly = "week".equalsIgnoreCase(granularity);
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> chairs = new LinkedHashMap<>();
        LocalDate cursor = weekly ? from.with(DayOfWeek.MONDAY) : from;
        while (!cursor.isAfter(to)) {
            revenue.put(keyOf(cursor, weekly), BigDecimal.ZERO);
            counts.put(keyOf(cursor, weekly), 0);
            chairs.put(keyOf(cursor, weekly), 0);
            cursor = cursor.plusDays(weekly ? 7 : 1);
        }
        for (Invoice invoice : invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to) && i.status() != InvoiceStatus.VOID)) {
            String key = keyOf(invoice.createdAt().toLocalDate(), weekly);
            revenue.merge(key, Money.nz(invoice.total()), Money::add);
            counts.merge(key, 1, Integer::sum);
        }
        for (Appointment appointment : appointments.where(a -> a.start() != null && !a.start().toLocalDate().isBefore(from)
                && !a.start().toLocalDate().isAfter(to) && a.status() == AppointmentStatus.COMPLETED)) {
            chairs.merge(keyOf(appointment.start().toLocalDate(), weekly), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : revenue.entrySet()) {
            out.add(Util.map("label", entry.getKey(), "revenue", Money.scale(entry.getValue()),
                    "invoices", counts.getOrDefault(entry.getKey(), 0),
                    "chairs", chairs.getOrDefault(entry.getKey(), 0)));
        }
        return out;
    }

    private static String keyOf(LocalDate date, boolean weekly) {
        return weekly ? "w/c " + DAY.format(date.with(DayOfWeek.MONDAY)) : DAY.format(date);
    }

    public List<Map<String, Object>> servicePerformance(LocalDate from, LocalDate to, int limit) {
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> minutes = new LinkedHashMap<>();
        for (Invoice invoice : invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to) && i.status() != InvoiceStatus.VOID)) {
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                if (!Invoice.Line.SERVICE.equals(line.kind()) && !Invoice.Line.ADDON.equals(line.kind())) continue;
                revenue.merge(line.name(), Money.nz(line.amount()), Money::add);
                counts.merge(line.name(), Math.max(1, line.qty()), Integer::sum);
            }
        }
        for (Appointment appointment : appointments.where(a -> a.start() != null && !a.start().toLocalDate().isBefore(from)
                && !a.start().toLocalDate().isAfter(to) && a.status() == AppointmentStatus.COMPLETED)) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) minutes.merge(Util.nz(item.name()), item.durationMinutes(), Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : revenue.entrySet()) {
            int count = counts.getOrDefault(entry.getKey(), 0);
            int chairMinutes = minutes.getOrDefault(entry.getKey(), 0);
            out.add(Util.map("name", entry.getKey(), "revenue", Money.scale(entry.getValue()), "count", count,
                    "chairMinutes", chairMinutes,
                    "revenuePerHour", chairMinutes == 0 ? BigDecimal.ZERO
                            : Money.divide(entry.getValue(), BigDecimal.valueOf(chairMinutes / 60.0))));
        }
        out.sort(Comparator.comparing(m -> (BigDecimal) m.get("revenue"), Comparator.reverseOrder()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public List<Map<String, Object>> staffPerformance(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Staff member : staff.all()) {
            List<Appointment> mine = appointments.where(a -> member.id().equals(a.staffId()) && a.start() != null
                    && !a.start().toLocalDate().isBefore(from) && !a.start().toLocalDate().isAfter(to));
            long completed = mine.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count();
            BigDecimal revenue = mine.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED)
                    .map(Appointment::price).reduce(BigDecimal.ZERO, Money::add);
            int minutes = mine.stream().filter(a -> a.status().occupiesChair()).mapToInt(Appointment::durationMinutes).sum();
            int capacity = 0;
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                Staff.DayHours hours = member.hoursFor(date.getDayOfWeek());
                if (hours == null || hours.closed() || member.offOn(date)) continue;
                capacity += StaffService.capacityMinutes(hours);
            }
            double ratingSum = 0;
            int ratings = 0;
            for (Appointment appointment : mine) {
                if (appointment.rating() != null && appointment.rating() > 0) { ratingSum += appointment.rating(); ratings++; }
            }
            out.add(Util.map("id", member.id(), "name", member.name(), "title", member.title(),
                    "colour", member.colorKey(), "active", member.active(),
                    "chairs", completed, "revenue", Money.scale(revenue),
                    "chairMinutes", minutes, "capacityMinutes", capacity,
                    "occupancy", capacity == 0 ? 0 : Util.percent(minutes, capacity),
                    "commissionPct", member.commissionPct(),
                    "commission", Money.percent(revenue, member.commissionPct()),
                    "rating", ratings == 0 ? member.rating() : Math.round(ratingSum / ratings * 10) / 10.0,
                    "ratings", ratings,
                    "noShows", mine.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count()));
        }
        out.sort(Comparator.comparing(m -> (BigDecimal) m.get("revenue"), Comparator.reverseOrder()));
        return out;
    }

    public Map<String, Object> channelMix(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> methods = new LinkedHashMap<>();
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (Invoice invoice : invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to) && i.status() != InvoiceStatus.VOID)) {
            sources.merge(Util.nz(invoice.source(), "desk"), 1, Integer::sum);
            for (Payment payment : Util.nullSafe(invoice.payments())) {
                if (!payment.isSuccessful()) continue;
                methods.merge(payment.method().label(), Money.nz(payment.amount()), Money::add);
                methodCounts.merge(payment.method().label(), 1, Integer::sum);
            }
        }
        for (Appointment appointment : appointments.where(a -> a.start() != null && !a.start().toLocalDate().isBefore(from)
                && !a.start().toLocalDate().isAfter(to))) {
            sources.merge("booking:" + Util.nz(appointment.source(), "desk"), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("methods", methods.entrySet().stream()
                .map(e -> Util.map("method", e.getKey(), "amount", Money.scale(e.getValue()),
                        "count", methodCounts.getOrDefault(e.getKey(), 0))).toList());
        out.put("sources", sources);
        return out;
    }

    /** Repeat rate, cohort curve and deterministic RFM segments. */
    public Map<String, Object> retention() {
        List<Customer> all = customers.where(c -> !c.archived());
        long repeat = all.stream().filter(c -> c.visits() >= 2).count();
        Map<String, Integer> cohorts = new LinkedHashMap<>();
        for (Customer customer : all) {
            if (customer.firstVisitAt() == null) continue;
            String month = customer.firstVisitAt().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            cohorts.merge(month, 1, Integer::sum);
        }
        Map<String, Integer> segments = new LinkedHashMap<>();
        List<Map<String, Object>> segmentDetail = new ArrayList<>();
        for (Customer customer : all) {
            String segment = segmentOf(customer);
            segments.merge(segment, 1, Integer::sum);
            if (segmentDetail.size() < 40 && ("At risk".equals(segment) || "Champion".equals(segment))) {
                segmentDetail.add(Util.map("id", customer.id(), "name", customer.fullName(), "segment", segment,
                        "visits", customer.visits(), "daysSince", customer.daysSinceLastVisit(),
                        "lifetimeValue", customer.lifetimeValue(), "tier", customer.tier()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clients", all.size());
        out.put("repeatClients", repeat);
        out.put("repeatRate", all.isEmpty() ? 0 : Util.percent(repeat, all.size()));
        out.put("cohorts", cohorts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Util.map("month", e.getKey(), "clients", e.getValue())).toList());
        out.put("segments", segments);
        out.put("segmentDetail", segmentDetail);
        out.put("segmentRules", List.of(
                "Champion: 6+ visits and last visit within 45 days",
                "Loyal: 3\u20135 visits and last visit within 90 days",
                "New: first visit within 60 days",
                "At risk: 2+ visits and last visit beyond 1.5\u00d7 the 60-day norm (90+ days)",
                "Dormant: last visit beyond 180 days"));
        return out;
    }

    private String segmentOf(Customer customer) {
        long days = customer.daysSinceLastVisit();
        if (days < 0) return "Never visited";
        if (customer.visits() >= 6 && days <= 45) return "Champion";
        if (customer.visits() >= 3 && days <= 90) return "Loyal";
        if (days <= 60 && customer.visits() <= 2) return "New";
        if (days > 180) return "Dormant";
        if (customer.visits() >= 2 && days > 90) return "At risk";
        return "Regular";
    }

    /** Weekday-weighted moving average projection for the next {@code days} days. */
    public Map<String, Object> forecast(int days) {
        int horizon = Util.clamp(days, 1, 30);
        LocalDate today = LocalDate.now();
        List<LocalDate> history = new ArrayList<>();
        for (int i = 56; i >= 1; i--) history.add(today.minusDays(i));

        Map<DayOfWeek, List<BigDecimal>> byWeekday = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) byWeekday.put(day, new ArrayList<>());
        for (LocalDate date : history) {
            BigDecimal sum = invoices.where(i -> i.createdAt() != null && i.createdAt().toLocalDate().equals(date)
                            && i.status() != InvoiceStatus.VOID).stream()
                    .map(Invoice::total).reduce(BigDecimal.ZERO, Money::add);
            byWeekday.get(date.getDayOfWeek()).add(sum);
        }
        Map<DayOfWeek, BigDecimal> averages = new LinkedHashMap<>();
        BigDecimal overall = BigDecimal.ZERO;
        int weeks = 0;
        for (Map.Entry<DayOfWeek, List<BigDecimal>> entry : byWeekday.entrySet()) {
            BigDecimal sum = entry.getValue().stream().reduce(BigDecimal.ZERO, Money::add);
            averages.put(entry.getKey(), Money.divide(sum, BigDecimal.valueOf(Math.max(1, entry.getValue().size()))));
            overall = Money.add(overall, sum);
            weeks = Math.max(weeks, entry.getValue().size());
        }
        BigDecimal dailyMean = Money.divide(overall, BigDecimal.valueOf(Math.max(1, history.size())));

        List<Map<String, Object>> projection = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= horizon; i++) {
            LocalDate date = today.plusDays(i);
            BigDecimal base = averages.getOrDefault(date.getDayOfWeek(), dailyMean);
            // gentle upward drift from the recent trend, capped at \u00b115%
            double trend = trendFactor(history);
            BigDecimal value = Money.scale(base.multiply(BigDecimal.valueOf(trend)));
            total = Money.add(total, value);
            projection.add(Util.map("date", date.toString(), "label", DAY.format(date),
                    "weekday", Util.dayName(date.getDayOfWeek()), "projected", value,
                    "basis", "average of the last " + weeks + " " + date.getDayOfWeek() + "s \u00d7 trend " + trim(trend)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", projection);
        out.put("total", total);
        out.put("dailyAverage", dailyMean);
        out.put("weekdayAverages", averages.entrySet().stream()
                .map(e -> Util.map("day", e.getKey().name(), "average", e.getValue())).toList());
        out.put("trendFactor", trendFactor(history));
        out.put("method", "weekday-weighted moving average over 8 weeks, scaled by the ratio of the last 2 weeks to the prior 6. No model is fitted and no external data is used.");
        out.put("confidenceNote", "A deterministic baseline: useful for stock and rota planning, not a promise.");
        return out;
    }

    private double trendFactor(List<LocalDate> history) {
        if (history.size() < 21) return 1.0;
        List<LocalDate> recent = history.subList(history.size() - 14, history.size());
        List<LocalDate> older = history.subList(0, history.size() - 14);
        BigDecimal recentSum = sumRevenue(recent);
        BigDecimal olderSum = sumRevenue(older);
        double recentAvg = Money.toDouble(recentSum) / Math.max(1, recent.size());
        double olderAvg = Money.toDouble(olderSum) / Math.max(1, older.size());
        if (olderAvg <= 0) return 1.0;
        return Math.max(0.85, Math.min(1.15, recentAvg / olderAvg));
    }

    private BigDecimal sumRevenue(List<LocalDate> dates) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LocalDate date : dates) {
            sum = Money.add(sum, invoices.where(i -> i.createdAt() != null && i.createdAt().toLocalDate().equals(date)
                            && i.status() != InvoiceStatus.VOID).stream()
                    .map(Invoice::total).reduce(BigDecimal.ZERO, Money::add));
        }
        return sum;
    }

    /** Hour-of-day and weekday heat maps for the occupancy panel. */
    public Map<String, Object> occupancyHeat(String range) {
        LocalDate[] window = rangeOf(range);
        Map<String, int[]> byHour = new LinkedHashMap<>();
        Map<String, int[]> byDay = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) byDay.put(day.name(), new int[2]);
        for (int hour = 8; hour <= 21; hour++) byHour.put(String.valueOf(hour), new int[2]);
        for (Appointment appointment : appointments.where(a -> a.start() != null && a.status().occupiesChair()
                && !a.start().toLocalDate().isBefore(window[0]) && !a.start().toLocalDate().isAfter(window[1]))) {
            int[] hour = byHour.get(String.valueOf(appointment.start().getHour()));
            if (hour != null) { hour[0]++; hour[1] += appointment.durationMinutes(); }
            int[] day = byDay.get(appointment.start().getDayOfWeek().name());
            if (day != null) { day[0]++; day[1] += appointment.durationMinutes(); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byHour", byHour.entrySet().stream()
                .map(e -> Util.map("hour", Integer.parseInt(e.getKey()), "bookings", e.getValue()[0], "minutes", e.getValue()[1])).toList());
        out.put("byDay", byDay.entrySet().stream()
                .map(e -> Util.map("day", e.getKey(), "bookings", e.getValue()[0], "minutes", e.getValue()[1])).toList());
        return out;
    }

    private static String labelFor(String range) {
        return switch (Util.nz(range, "30d")) {
            case "7d", "week" -> "Last 7 days";
            case "90d", "quarter" -> "Last 90 days";
            case "365d", "year" -> "Last 12 months";
            case "mtd" -> "Month to date";
            case "ytd" -> "Year to date";
            default -> "Last 30 days";
        };
    }

    private static String trim(double value) {
        return String.valueOf(Math.round(value * 1000) / 1000.0);
    }

    /** Product-level sell-through, for the inventory report. */
    public List<Map<String, Object>> productPerformance(int limit) {
        LocalDate from = LocalDate.now().minusDays(90);
        Map<String, Integer> sold = new LinkedHashMap<>();
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        for (Invoice invoice : invoices.where(i -> i.createdAt() != null && i.createdAt().toLocalDate().isAfter(from)
                && i.status() != InvoiceStatus.VOID)) {
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                if (!Invoice.Line.PRODUCT.equals(line.kind())) continue;
                sold.merge(Util.nz(line.name()), Math.max(1, line.qty()), Integer::sum);
                revenue.merge(Util.nz(line.name()), Money.nz(line.amount()), Money::add);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product product : products.all()) {
            out.add(Util.map("id", product.id(), "name", product.name(), "brand", product.brand(),
                    "sold90d", sold.getOrDefault(product.name(), 0),
                    "revenue90d", Money.scale(revenue.getOrDefault(product.name(), BigDecimal.ZERO)),
                    "stock", product.stock(), "reorderLevel", product.reorderLevel(),
                    "margin", product.marginPct(), "status", product.isOut() ? "out" : product.isLow() ? "low" : "ok"));
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("sold90d")).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public Map<String, Object> taxReport(LocalDate from, LocalDate to) {
        List<Invoice> bills = invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to) && i.status() != InvoiceStatus.VOID);
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : bills) {
            taxable = Money.add(taxable, invoice.taxable());
            cgst = Money.add(cgst, invoice.cgst());
            sgst = Money.add(sgst, invoice.sgst());
            total = Money.add(total, invoice.total());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("invoices", bills.size());
        out.put("taxableValue", taxable);
        out.put("cgst", cgst);
        out.put("sgst", sgst);
        out.put("taxTotal", Money.add(cgst, sgst));
        out.put("invoiceTotal", total);
        out.put("effectiveRate", taxable.signum() == 0 ? BigDecimal.ZERO
                : Money.add(cgst, sgst).multiply(BigDecimal.valueOf(100)).divide(taxable, 2, RoundingMode.HALF_UP));
        return out;
    }

    public String label() {
        return "Analytics \u00b7 " + services.size() + " services, " + staff.size() + " stylists, "
                + customers.size() + " clients";
    }

    public String locale() {
        return Locale.getDefault().toString();
    }

    public long completedSince(LocalDate date) {
        return appointments.countWhere(a -> a.status() == AppointmentStatus.COMPLETED && a.start() != null
                && !a.start().toLocalDate().isBefore(date));
    }

    public List<Appointment> recentCompleted(int limit) {
        List<Appointment> out = appointments.where(a -> a.status() == AppointmentStatus.COMPLETED && a.start() != null);
        out.sort(Comparator.comparing(Appointment::start).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public List<ServiceItem> topServicesByBookings(int limit) {
        List<ServiceItem> all = services.all();
        all.sort(Comparator.comparingInt(ServiceItem::timesBooked).reversed());
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }
}
