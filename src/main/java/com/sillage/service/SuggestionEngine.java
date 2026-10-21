package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.Look;
import com.sillage.model.Product;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.model.Suggestion;
import com.sillage.model.SuggestionState;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The recommendation engine. Deliberately <strong>not</strong> machine learning: every number it
 * produces comes from counting, ratios and published thresholds over the salon's own book.
 *
 * <p>Techniques used:
 * <ul>
 *   <li><b>Co-occurrence (market-basket)</b> — support, confidence and lift over service pairs from
 *       completed appointments. This is the classic Apriori-style measure, computed exactly.</li>
 *   <li><b>First-order Markov transitions</b> — "after X the next chair is usually Y".</li>
 *   <li><b>RFM + interval estimation</b> — recency against each client's own median rebooking gap.</li>
 *   <li><b>Threshold rules</b> — reorder points, utilisation bands, churn bands, tier proximity.</li>
 *   <li><b>Calendar rules</b> — season and festival tables, birthdays, weekday demand curves.</li>
 * </ul>
 *
 * <p>Every {@link Suggestion} carries the rule ids that fired and a rationale sentence assembled
 * from the same numbers that produced the score, so a stylist can argue with it — and win.
 */
public final class SuggestionEngine {

    public static final String ENGINE = "Sillage Rule Engine v1 \u00b7 deterministic (co-occurrence, Markov, RFM, thresholds)";

    private static final double W_COOCCURRENCE = 0.42;
    private static final double W_MARKOV = 0.28;
    private static final double W_REBOOK = 0.30;
    private static final double W_ROTATION = 0.16;
    private static final double W_SEASON = 0.12;
    private static final double W_STAFF = 0.10;
    private static final double MIN_PAIR_SUPPORT = 2;
    private static final double MIN_LIFT = 1.15;

    /** Season keyword table — a plain lookup, no model. */
    private static final Map<String, List<String>> SEASONS = Map.of(
            "MONSOON", List.of("frizz", "smooth", "keratin", "anti", "humidity", "scalp", "clarify"),
            "WINTER", List.of("hydrat", "moistur", "repair", "mask", "gloss", "oil", "nourish"),
            "SUMMER", List.of("clarify", "sun", "protect", "cool", "detox", "volume", "colour"),
            "SPRING", List.of("colour", "balayage", "highlight", "blonde", "toner", "pastel"),
            "FESTIVE", List.of("bridal", "party", "makeup", "styling", "updo", "occasion", "glam"));

    /** Category rotation table: having had A but never B is a signal. */
    private static final Map<String, List<String>> ROTATION = Map.of(
            "colour", List.of("treatment", "spa", "hair spa", "olaplex", "gloss"),
            "cut", List.of("style", "blow", "finish", "treatment"),
            "spa", List.of("cut", "colour", "gloss"),
            "skin", List.of("facial", "spa", "brow", "lash"),
            "bridal", List.of("skin", "nail", "hair", "drape"),
            "nails", List.of("skin", "spa", "pedicure"));

    private final Repo<Appointment> appointments;
    private final Repo<Customer> customers;
    private final Repo<ServiceItem> services;
    private final Repo<Staff> staff;
    private final Repo<Product> products;
    private final Repo<Invoice> invoices;
    private final Repo<Look> looks;
    private final Repo<SuggestionState> states;
    private final SettingsService settings;

    public SuggestionEngine(Repo<Appointment> appointments, Repo<Customer> customers, Repo<ServiceItem> services,
                            Repo<Staff> staff, Repo<Product> products, Repo<Invoice> invoices, Repo<Look> looks,
                            Repo<SuggestionState> states, SettingsService settings) {
        this.appointments = appointments;
        this.customers = customers;
        this.services = services;
        this.staff = staff;
        this.products = products;
        this.invoices = invoices;
        this.looks = looks;
        this.states = states;
        this.settings = settings;
    }

    // ================================================================== statistics

    /** Completed visits as ordered service sequences per customer. */
    private Map<String, List<List<String>>> sequences() {
        Map<String, List<Appointment>> byCustomer = new LinkedHashMap<>();
        for (Appointment appointment : appointments.where(a -> a.status() == AppointmentStatus.COMPLETED && a.start() != null)) {
            String key = Util.present(appointment.customerId()) ? appointment.customerId() : "guest:" + Util.nz(appointment.guestPhone());
            byCustomer.computeIfAbsent(key, k -> new ArrayList<>()).add(appointment);
        }
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        byCustomer.forEach((key, list) -> {
            list.sort(Comparator.comparing(Appointment::start));
            List<List<String>> visits = new ArrayList<>();
            for (Appointment appointment : list) {
                List<String> ids = new ArrayList<>();
                for (Appointment.Item item : Util.nullSafe(appointment.items())) if (Util.present(item.serviceId())) ids.add(item.serviceId());
                if (!ids.isEmpty()) visits.add(ids);
            }
            if (!visits.isEmpty()) out.put(key, visits);
        });
        return out;
    }

    /**
     * Pair statistics over service ids: {@code [pairs, support, confidence(A\u2192B), lift]}.
     * A pair counts when both services appear in the same visit <em>or</em> in consecutive visits.
     */
    public Map<String, Map<String, double[]>> pairStatistics() {
        Map<String, Integer> serviceCount = new HashMap<>();
        Map<String, Map<String, Integer>> pairCount = new HashMap<>();
        int transactions = 0;

        for (List<List<String>> visits : sequences().values()) {
            Set<String> basket = new LinkedHashSet<>();
            for (List<String> visit : visits) basket.addAll(visit);
            transactions++;
            for (String id : basket) serviceCount.merge(id, 1, Integer::sum);

            List<String> flat = new ArrayList<>();
            for (List<String> visit : visits) flat.addAll(new ArrayList<>(new LinkedHashSet<>(visit)));
            for (int i = 0; i < flat.size(); i++) {
                for (int j = i + 1; j < flat.size() && j <= i + 3; j++) {
                    String a = flat.get(i);
                    String b = flat.get(j);
                    if (a.equals(b)) continue;
                    pairCount.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                    pairCount.computeIfAbsent(b, k -> new HashMap<>()).merge(a, 1, Integer::sum);
                }
            }
        }

        Map<String, Map<String, double[]>> out = new LinkedHashMap<>();
        int total = Math.max(1, transactions);
        for (Map.Entry<String, Map<String, Integer>> entry : pairCount.entrySet()) {
            String a = entry.getKey();
            int countA = serviceCount.getOrDefault(a, 0);
            if (countA == 0) continue;
            Map<String, double[]> row = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> pair : entry.getValue().entrySet()) {
                String b = pair.getKey();
                int countB = serviceCount.getOrDefault(b, 0);
                int pairs = pair.getValue();
                if (pairs < MIN_PAIR_SUPPORT || countB == 0) continue;
                double support = (double) pairs / total;
                double confidence = (double) pairs / countA;
                double expected = (double) countB / total;
                double lift = expected == 0 ? 0 : confidence / expected;
                row.put(b, new double[]{pairs, support, confidence, lift});
            }
            if (!row.isEmpty()) out.put(a, row);
        }
        return out;
    }

    /** First-order transition probabilities: after service A, the next visit's services. */
    public Map<String, Map<String, Double>> transitions() {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        for (List<List<String>> visits : sequences().values()) {
            for (int i = 0; i + 1 < visits.size(); i++) {
                Set<String> from = new LinkedHashSet<>(visits.get(i));
                Set<String> to = new LinkedHashSet<>(visits.get(i + 1));
                for (String a : from) {
                    totals.merge(a, 1, Integer::sum);
                    for (String b : to) {
                        if (a.equals(b)) continue;
                        counts.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        counts.forEach((a, row) -> {
            int total = Math.max(1, totals.getOrDefault(a, 0));
            Map<String, Double> probabilities = new LinkedHashMap<>();
            row.forEach((b, count) -> probabilities.put(b, Math.round((double) count / total * 1000) / 1000.0));
            out.put(a, probabilities);
        });
        return out;
    }

    /** Median gap in days between visits containing a given service, per customer. */
    private Map<String, Long> rebookIntervals(Customer customer) {
        Map<String, List<Long>> gaps = new HashMap<>();
        List<Appointment> history = appointments.where(a -> customer.id().equals(a.customerId())
                && a.status() == AppointmentStatus.COMPLETED && a.start() != null);
        history.sort(Comparator.comparing(Appointment::start));
        Map<String, LocalDateTime> lastSeen = new HashMap<>();
        for (Appointment appointment : history) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                String id = item.serviceId();
                if (id == null) continue;
                LocalDateTime previous = lastSeen.get(id);
                if (previous != null) {
                    long days = ChronoUnit.DAYS.between(previous, appointment.start());
                    if (days > 0) gaps.computeIfAbsent(id, k -> new ArrayList<>()).add(days);
                }
                lastSeen.put(id, appointment.start());
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        gaps.forEach((id, list) -> {
            list.sort(Comparator.naturalOrder());
            out.put(id, list.get(list.size() / 2));
        });
        return out;
    }

    // ================================================================== per-customer

    /** Next-best-service recommendations for one client, with full reasoning. */
    public List<Suggestion> forCustomer(String customerId, int limit, boolean includeDismissed) {
        Customer customer = customers.get(customerId);
        if (customer == null) throw ApiException.notFound("Customer " + Util.nz(customerId));
        BusinessSettings config = settings.current();

        List<Appointment> history = appointments.where(a -> customerId.equals(a.customerId())
                && a.status() == AppointmentStatus.COMPLETED && a.start() != null);
        history.sort(Comparator.comparing(Appointment::start));
        Set<String> booked = new LinkedHashSet<>();
        Map<String, LocalDateTime> lastBooked = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Appointment appointment : history) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                if (item.serviceId() == null) continue;
                booked.add(item.serviceId());
                lastBooked.put(item.serviceId(), appointment.start());
                counts.merge(item.serviceId(), 1, Integer::sum);
            }
        }
        List<String> lastVisitServices = history.isEmpty() ? List.of()
                : history.get(history.size() - 1).serviceIds();

        Map<String, Map<String, double[]>> pairs = pairStatistics();
        Map<String, Map<String, Double>> markov = transitions();
        Map<String, Long> intervals = rebookIntervals(customer);
        Set<String> categoriesSeen = new LinkedHashSet<>();
        for (String id : booked) {
            ServiceItem service = services.get(id);
            if (service != null) categoriesSeen.add(Util.normalise(service.categoryId()));
        }
        BigDecimal averageTicket = averageTicket(customer, history);
        String season = seasonKey(LocalDate.now(), config);
        List<String> seasonWords = SEASONS.getOrDefault(season, List.of());
        Staff favourite = Util.present(customer.preferredStaffId()) ? staff.get(customer.preferredStaffId()) : mostUsedStaff(history);

        List<Candidate> candidates = new ArrayList<>();
        for (ServiceItem service : services.where(s -> s.active() && s.onlineBookable())) {
            double score = 0;
            List<String> rules = new ArrayList<>();
            List<String> reasons = new ArrayList<>();

            // R1 co-occurrence
            double bestConfidence = 0;
            double bestLift = 0;
            String bestPartner = null;
            for (String past : booked) {
                double[] stats = pairs.getOrDefault(past, Map.of()).get(service.id());
                if (stats == null) continue;
                if (stats[2] > bestConfidence) {
                    bestConfidence = stats[2];
                    bestLift = stats[3];
                    bestPartner = past;
                }
            }
            if (bestPartner != null && bestLift >= MIN_LIFT) {
                double signal = Math.min(1, bestConfidence) * Math.min(1, Math.log(bestLift + 1) / Math.log(4));
                score += W_COOCCURRENCE * signal;
                rules.add("R-COOC-1");
                reasons.add("clients who booked " + name(bestPartner) + " took " + name(service.id())
                        + " in " + Math.round(bestConfidence * 100) + "% of cases (" + trim(bestLift) + "\u00d7 the base rate)");
            }

            // R2 Markov transition from the last visit
            double transition = 0;
            for (String last : lastVisitServices) transition = Math.max(transition, markov.getOrDefault(last, Map.of()).getOrDefault(service.id(), 0.0));
            if (transition >= 0.12) {
                score += W_MARKOV * Math.min(1, transition / 0.6);
                rules.add("R-MARKOV-2");
                reasons.add("after " + Util.humanList(lastVisitServices.stream().map(this::name).toList())
                        + " the next chair is usually this (" + Math.round(transition * 100) + "% of the time)");
            }

            // R3 rebooking interval
            long daysSince = customer.daysSinceLastVisit();
            long medianGap = medianInterval(intervals, booked, history);
            if (medianGap > 0 && daysSince >= 0) {
                double due = Math.min(1.2, daysSince / (double) medianGap);
                if (due >= 0.75 && (booked.contains(service.id()) || rules.contains("R-COOC-1"))) {
                    score += W_REBOOK * Math.min(1, due / 1.2);
                    rules.add("R-REBOOK-3");
                    reasons.add("it has been " + daysSince + " days against a personal median of " + medianGap);
                }
            }

            // R4 category rotation
            String category = Util.normalise(service.categoryId());
            boolean rotation = false;
            for (String seen : categoriesSeen) {
                for (String want : ROTATION.getOrDefault(seen, List.of())) {
                    if (category.contains(want) || Util.normalise(service.name()).contains(want)) { rotation = true; break; }
                }
                if (rotation) break;
            }
            if (rotation && !booked.contains(service.id())) {
                score += W_ROTATION;
                rules.add("R-ROTATE-4");
                reasons.add("it balances what is already on the record (" + Util.humanList(new ArrayList<>(categoriesSeen)) + ")");
            }

            // R5 season
            if (!seasonWords.isEmpty()) {
                String haystack = Util.normalise(Util.join(List.of(Util.nz(service.name()), Util.nz(service.tagline()),
                        Util.nz(service.description()), Util.join(Util.nullSafe(service.tags()), " ")), " "));
                for (String word : seasonWords) {
                    if (haystack.contains(word)) {
                        score += W_SEASON;
                        rules.add("R-SEASON-5");
                        reasons.add(Util.titleCase(season.toLowerCase(Locale.ROOT)) + " demand for " + word + " services");
                        break;
                    }
                }
            }

            // R6 staff affinity
            if (favourite != null && service.performedBy(favourite.id()) && favourite.certifiedFor(service.id())) {
                score += W_STAFF;
                rules.add("R-STAFF-6");
                reasons.add(favourite.firstName() + " is certified for it and is the usual chair");
            }

            if (score <= 0) continue;

            // hard filters
            if (averageTicket.signum() > 0 && service.price() != null
                    && service.price().compareTo(averageTicket.multiply(new BigDecimal("1.8"))) > 0
                    && !booked.contains(service.id())) {
                continue; // above this client's habitual spend band
            }
            LocalDateTime previous = lastBooked.get(service.id());
            if (previous != null) {
                long cooldown = Math.max(7, medianGap / 2);
                if (ChronoUnit.DAYS.between(previous, LocalDateTime.now()) < cooldown) continue;
            }
            candidates.add(new Candidate(service, score, rules, reasons));
        }

        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        List<Suggestion> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (out.size() >= limit) break;
            Suggestion suggestion = suggestion(Suggestion.NEXT_SERVICE, "audience:" + customerId,
                    "Offer " + candidate.service.name(),
                    Money.inr(candidate.service.price()) + " \u00b7 " + Util.humanDuration(candidate.service.durationMinutes()),
                    rationale(candidate.reasons),
                    candidate.score, customerId, "customer",
                    "book", "Book this chair",
                    Util.map("serviceId", candidate.service.id(), "serviceName", candidate.service.name(),
                            "price", candidate.service.price(), "durationMinutes", candidate.service.durationMinutes(),
                            "categoryId", candidate.service.categoryId(),
                            "staff", favourite == null ? null : favourite.name(),
                            "nextSlot", nextSlotFor(candidate.service.id(), favourite)),
                    candidate.rules, horizonOf(daysSinceLabel(customer)),
                    Money.inr(candidate.service.price()) + " per conversion");
            out.add(suggestion);
        }

        addOccasionSuggestions(out, customer, config, includeDismissed);
        addRebookReminder(out, customer, history, config);
        return filterDismissed(out, includeDismissed, limit + 2);
    }

    private void addOccasionSuggestions(List<Suggestion> out, Customer customer, BusinessSettings config, boolean includeDismissed) {
        if (customer.isBirthdayWithin(30)) {
            String perk = config.tierByName(customer.tier()) == null ? "a complimentary gloss" : config.tierByName(customer.tier()).perk();
            out.add(suggestion(Suggestion.OCCASION, "audience:" + customer.id(),
                    "Birthday chair for " + customer.firstName(),
                    "Within 30 days \u00b7 " + Util.nz(perk),
                    "Birthday on " + Util.formatDate(customer.birthday()) + "; tier perk \u201c" + Util.nz(perk) + "\u201d applies",
                    0.86, customer.id(), "customer", "message", "Send the birthday note",
                    Util.map("template", "birthday", "birthday", customer.birthday().toString()),
                    List.of("R-OCCASION-7"), "30 days", "retention"));
        }
    }

    private void addRebookReminder(List<Suggestion> out, Customer customer, List<Appointment> history, BusinessSettings config) {
        if (history.isEmpty()) return;
        Appointment last = history.get(history.size() - 1);
        long gap = ChronoUnit.DAYS.between(last.start(), LocalDateTime.now());
        Map<String, Long> intervals = rebookIntervals(customer);
        long median = medianInterval(intervals, new LinkedHashSet<>(last.serviceIds()), history);
        if (median > 0 && gap >= median) {
            out.add(0, suggestion(Suggestion.REBOOK, "audience:" + customer.id(),
                    "Rebook " + customer.firstName() + " for " + name(last.serviceIds().isEmpty() ? null : last.serviceIds().get(0)),
                    gap + " days since the last chair \u00b7 median " + median + " days",
                    "Personal median rebooking gap is " + median + " days and it has been " + gap,
                    Math.min(1, 0.5 + (gap - median) / 60.0), customer.id(), "customer",
                    "book", "Open the diary", Util.map("customerId", customer.id(), "daysOverdue", gap - median),
                    List.of("R-REBOOK-3"), "this week", Money.inr(Money.nz(last.price())) + " expected"));
        }
    }

    // ================================================================== desk & house

    /** Bundles worth putting on the menu, from lift and support. */
    public List<Suggestion> bundles(int limit) {
        Map<String, Map<String, double[]>> pairs = pairStatistics();
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Map.Entry<String, Map<String, double[]>>> entries = new ArrayList<>(pairs.entrySet());
        for (Map.Entry<String, Map<String, double[]>> entry : entries) {
            for (Map.Entry<String, double[]> pair : entry.getValue().entrySet()) {
                String key = pairKey(entry.getKey(), pair.getKey());
                if (!seen.add(key)) continue;
                double[] stats = pair.getValue();
                if (stats[0] < 3 || stats[3] < 1.3 || stats[2] < 0.22) continue;
                ServiceItem a = services.get(entry.getKey());
                ServiceItem b = services.get(pair.getKey());
                if (a == null || b == null || !a.active() || !b.active()) continue;
                BigDecimal combined = Money.add(a.price(), b.price());
                BigDecimal bundle = Money.scale(combined.multiply(new BigDecimal("0.92")));
                out.add(suggestion(Suggestion.BUNDLE, "audience:desk",
                        "Bundle " + a.name() + " + " + b.name(),
                        Money.inr(combined) + " \u2192 " + Money.inr(bundle) + " (8% paired price)",
                        "support " + trim(stats[1] * 100) + "%, confidence " + Math.round(stats[2] * 100)
                                + "%, lift " + trim(stats[3]) + "\u00d7 across " + (int) stats[0] + " co-bookings",
                        bundleScore(stats[2], stats[3]), null, "service",
                        "package", "Create the package",
                        Util.map("serviceIds", List.of(a.id(), b.id()), "combinedPrice", combined, "bundlePrice", bundle,
                                "support", stats[1], "confidence", stats[2], "lift", stats[3], "pairs", (int) stats[0]),
                        List.of("R-BUNDLE-8"), "menu review", Money.inr(Money.subtract(combined, bundle)) + " margin given, higher attach"));
            }
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Keeps bundle scores off the 1.0 ceiling so the ranking still means something. */
    private static double bundleScore(double confidence, double lift) {
        return Math.min(0.99, Math.min(1, confidence) * Math.min(1, (lift - 1) / 2) + 0.25);
    }

    /** Point-of-sale add-ons for a cart being built right now. */
    public List<Suggestion> upsellFor(List<String> cartServiceIds, String customerId, int limit) {
        Map<String, Map<String, double[]>> pairs = pairStatistics();
        List<Suggestion> out = new ArrayList<>();
        Set<String> already = new LinkedHashSet<>(Util.nullSafe(cartServiceIds));
        Map<String, Double> best = new LinkedHashMap<>();
        Map<String, String> because = new LinkedHashMap<>();
        for (String inCart : already) {
            for (Map.Entry<String, double[]> entry : pairs.getOrDefault(inCart, Map.of()).entrySet()) {
                if (already.contains(entry.getKey())) continue;
                double[] stats = entry.getValue();
                if (stats[3] < MIN_LIFT) continue;
                double signal = Math.min(1, stats[2]) * Math.min(1.5, stats[3]) / 1.5;
                if (signal > best.getOrDefault(entry.getKey(), 0.0)) {
                    best.put(entry.getKey(), signal);
                    because.put(entry.getKey(), Math.round(stats[2] * 100) + "% of clients who take " + name(inCart)
                            + " also take this (" + trim(stats[3]) + "\u00d7 base rate)");
                }
            }
        }
        Customer customer = Util.present(customerId) ? customers.get(customerId) : null;

        best.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> {
                    ServiceItem service = services.get(entry.getKey());
                    if (service == null || !service.active()) return;
                    if (customer != null && averageTicket(customer, List.of()).signum() > 0
                            && service.price().compareTo(averageTicket(customer, List.of()).multiply(new BigDecimal("1.6"))) > 0) return;
                    out.add(suggestion(Suggestion.UPSELL, "audience:desk",
                            "Add " + service.name() + " to this bill",
                            Money.inr(service.price()) + " \u00b7 " + Util.humanDuration(service.durationMinutes()),
                            because.get(entry.getKey()),
                            entry.getValue(), customerId, "service", "add", "Add to the bill",
                            Util.map("serviceId", service.id(), "price", service.price(),
                                    "minutes", service.durationMinutes()),
                            List.of("R-COOC-1", "R-UPSELL-9"), "this visit", Money.inr(service.price()) + " uplift"));
                });

        // retail attach: products linked to a service in the cart
        for (String inCart : already) {
            for (Product product : products.where(p -> p.active() && p.linkedServiceIds() != null
                    && p.linkedServiceIds().contains(inCart) && p.stock() > 0)) {
                if (out.size() >= limit) break;
                out.add(suggestion(Suggestion.UPSELL, "audience:desk",
                        "Retail attach: " + product.name(),
                        Money.inr(product.price()) + " \u00b7 " + product.stock() + " on the shelf",
                        "Consumed by " + name(inCart) + "; " + trim(product.usagePerService())
                                + " " + Util.nz(product.unit(), "unit") + " per service, so home care is the natural follow-up",
                        0.55, null, "product", "add", "Add to the bill",
                        Util.map("productId", product.id(), "price", product.price(), "margin", product.marginPct()),
                        List.of("R-RETAIL-10"), "this visit",
                        Money.inr(Money.percent(product.price(), product.marginPct().doubleValue())) + " margin"));
            }
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Win-back, churn, inventory, staffing, pricing, loyalty and housekeeping suggestions. */
    public List<Suggestion> operations(int limit) {
        List<Suggestion> out = new ArrayList<>();
        BusinessSettings config = settings.current();
        out.addAll(winback(config, 6));
        out.addAll(churnRisk(5));
        out.addAll(reorder(5));
        out.addAll(staffing(4));
        out.addAll(pricing(4));
        out.addAll(loyaltyPush(config, 4));
        out.addAll(housekeeping(4));
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> winback(BusinessSettings config, int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (Customer customer : customers.where(c -> !c.archived() && c.visits() >= 2)) {
            long days = customer.daysSinceLastVisit();
            if (days < 0) continue;
            List<Appointment> history = appointments.where(a -> customer.id().equals(a.customerId())
                    && a.status() == AppointmentStatus.COMPLETED && a.start() != null);
            Map<String, Long> intervals = rebookIntervals(customer);
            Set<String> booked = new LinkedHashSet<>();
            history.forEach(a -> booked.addAll(a.serviceIds()));
            long median = medianInterval(intervals, booked, history);
            long threshold = median > 0 ? Math.round(median * 1.5) : 75;
            if (days < threshold) continue;
            String favourite = favouriteService(history);
            double score = Math.min(1, 0.4 + (days - threshold) / 180.0
                    + (customer.lifetimeValue() == null ? 0 : Math.min(0.25, Money.toDouble(customer.lifetimeValue()) / 400000.0)));
            out.add(suggestion(Suggestion.WINBACK, "audience:" + customer.id(),
                    "Win back " + customer.fullName(),
                    days + " days quiet \u00b7 threshold " + threshold + " days \u00b7 " + Money.inr(Money.nz(customer.lifetimeValue())) + " lifetime",
                    "Personal median gap " + median + " days, now " + days + " days since the last chair; usual service "
                            + Util.nz(favourite, "unknown"),
                    score, customer.id(), "customer", "message", "Send a win-back note",
                    Util.map("template", "winback", "days", days, "medianGap", median,
                            "service", favourite, "phone", customer.phone(), "email", customer.email()),
                    List.of("R-WINBACK-11"), "this week",
                    Money.inr(averageTicket(customer, history)) + " expected per return"));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> churnRisk(int limit) {
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Customer customer : customers.where(c -> !c.archived() && c.visits() >= 3)) {
            List<Appointment> history = appointments.where(a -> customer.id().equals(a.customerId()) && a.start() != null);
            history.sort(Comparator.comparing(Appointment::start));
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < history.size(); i++) {
                if (history.get(i - 1).start() == null || history.get(i).start() == null) continue;
                gaps.add(ChronoUnit.DAYS.between(history.get(i - 1).start(), history.get(i).start()));
            }
            if (gaps.size() < 3) continue;
            List<Long> recent = gaps.subList(Math.max(0, gaps.size() - 3), gaps.size());
            List<Long> earlier = gaps.subList(0, Math.max(1, gaps.size() - 3));
            double recentAvg = recent.stream().mapToLong(Long::longValue).average().orElse(0);
            double earlierAvg = earlier.stream().mapToLong(Long::longValue).average().orElse(0);
            double drift = earlierAvg == 0 ? 0 : (recentAvg - earlierAvg) / earlierAvg;
            int noShows = customer.noShows();
            int cancellations = customer.cancellations();
            double rating = customer.averageRating();
            double score = 0;
            List<String> reasons = new ArrayList<>();
            if (drift > 0.25) { score += Math.min(0.45, drift * 0.5); reasons.add("visit gap widened " + Math.round(drift * 100) + "%"); }
            if (noShows >= 1) { score += Math.min(0.25, noShows * 0.12); reasons.add(noShows + " no-show(s)"); }
            if (cancellations >= 2) { score += 0.1; reasons.add(cancellations + " cancellations"); }
            if (rating > 0 && rating <= 3) { score += 0.2; reasons.add("last ratings averaged " + trim(rating) + "/5"); }
            long days = customer.daysSinceLastVisit();
            if (days > 60) { score += Math.min(0.25, days / 400.0); reasons.add(days + " days since the last visit"); }
            if (score < 0.35) continue;
            String band = score >= 0.7 ? "high" : score >= 0.5 ? "medium" : "watch";
            scored.add(Util.map("score", Math.min(1, score), "band", band, "reasons", Util.join(reasons, "; "),
                    "customer", customer));
        }
        scored.sort(Comparator.comparingDouble(m -> -((Double) m.get("score"))));
        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            Map<String, Object> entry = scored.get(i);
            Customer customer = (Customer) entry.get("customer");
            out.add(suggestion(Suggestion.RETENTION, "audience:" + customer.id(),
                    "Retention risk: " + customer.fullName() + " (" + entry.get("band") + ")",
                    Util.nz((String) entry.get("reasons")),
                    "Score " + trim((Double) entry.get("score")) + " from: " + entry.get("reasons"),
                    (Double) entry.get("score"), customer.id(), "customer", "call", "Call the client",
                    Util.map("band", entry.get("band"), "lifetimeValue", customer.lifetimeValue()),
                    List.of("R-CHURN-12"), "this week",
                    Money.inr(Money.nz(customer.lifetimeValue())) + " at stake"));
        }
        return out;
    }

    private List<Suggestion> reorder(int limit) {
        List<Suggestion> out = new ArrayList<>();
        Map<String, Integer> upcomingUsage = new LinkedHashMap<>();
        for (Appointment appointment : appointments.where(a -> !a.status().isClosed() && a.start() != null
                && a.start().isBefore(LocalDateTime.now().plusDays(14)))) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                for (Product product : products.where(p -> p.linkedServiceIds() != null
                        && p.linkedServiceIds().contains(item.serviceId()))) {
                    upcomingUsage.merge(product.id(), (int) Math.ceil(product.usagePerService()), Integer::sum);
                }
            }
        }
        for (Product product : products.where(Product::active)) {
            int forecast = product.stock() - upcomingUsage.getOrDefault(product.id(), 0);
            boolean trigger = product.isLow() || forecast <= product.reorderLevel();
            if (!trigger) continue;
            int qty = Math.max(product.reorderQty(), product.reorderLevel() * 2 - product.stock());
            qty = Math.max(1, qty);
            BigDecimal cost = Money.multiply(product.costPrice() == null ? BigDecimal.ZERO : product.costPrice(), qty);
            double score = product.isOut() ? 0.95 : Math.min(0.9, 0.4 + (double) (product.reorderLevel() - product.stock())
                    / Math.max(1, product.reorderLevel() + 4));
            out.add(suggestion(Suggestion.INVENTORY, "audience:house",
                    (product.isOut() ? "Out of stock: " : "Reorder: ") + product.name(),
                    product.stock() + " " + Util.nz(product.unit(), "unit") + " left \u00b7 reorder point "
                            + product.reorderLevel() + " \u00b7 forecast " + forecast + " in 14 days",
                    "Stock " + product.stock() + " is at or below the reorder point " + product.reorderLevel()
                            + (upcomingUsage.containsKey(product.id())
                            ? "; booked services will consume about " + upcomingUsage.get(product.id()) + " more" : ""),
                    score, product.id(), "product", "order", "Raise a purchase order",
                    Util.map("productId", product.id(), "qty", qty, "cost", cost, "supplier", product.supplier(),
                            "supplierPhone", product.supplierPhone()),
                    List.of("R-STOCK-13"), product.isOut() ? "today" : "this week", Money.inr(cost) + " to place"));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> staffing(int limit) {
        List<Suggestion> out = new ArrayList<>();
        Map<String, int[]> demand = new LinkedHashMap<>(); // weekday|hour -> [bookings, minutes]
        LocalDate from = LocalDate.now().minusDays(56);
        for (Appointment appointment : appointments.where(a -> a.start() != null && a.start().toLocalDate().isAfter(from)
                && a.status().occupiesChair())) {
            String key = appointment.start().getDayOfWeek().name() + "|" + appointment.start().getHour();
            int[] cell = demand.computeIfAbsent(key, k -> new int[2]);
            cell[0]++;
            cell[1] += appointment.durationMinutes();
        }
        List<Map.Entry<String, int[]>> ranked = new ArrayList<>(demand.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]));
        if (!ranked.isEmpty()) {
            Map.Entry<String, int[]> peak = ranked.get(0);
            String[] parts = peak.getKey().split("\\|");
            out.add(suggestion(Suggestion.STAFFING, "audience:house",
                    "Peak demand: " + Util.titleCase(parts[0].toLowerCase(Locale.ROOT)) + "s around "
                            + LocalTime.of(Integer.parseInt(parts[1]), 0).format(java.time.format.DateTimeFormatter.ofPattern("h a")),
                    peak.getValue()[0] + " bookings \u00b7 " + Util.humanDuration(peak.getValue()[1]) + " of chair time over 8 weeks",
                    "Highest chair-minute cell in the last 56 days; consider a second chair or a longer shift",
                    0.68, null, "hour", "open", "Open the rota",
                    Util.map("day", parts[0], "hour", Integer.parseInt(parts[1]), "bookings", peak.getValue()[0],
                            "minutes", peak.getValue()[1]),
                    List.of("R-DEMAND-14"), "next rota", "chair capacity"));
        }
        if (ranked.size() > 3) {
            Map.Entry<String, int[]> quiet = ranked.get(ranked.size() - 1);
            String[] parts = quiet.getKey().split("\\|");
            out.add(suggestion(Suggestion.STAFFING, "audience:house",
                    "Quiet cell: " + Util.titleCase(parts[0].toLowerCase(Locale.ROOT)) + "s around "
                            + LocalTime.of(Integer.parseInt(parts[1]), 0).format(java.time.format.DateTimeFormatter.ofPattern("h a")),
                    quiet.getValue()[0] + " bookings over 8 weeks",
                    "Lowest chair-minute cell; a promotion or a training block would cost less than an idle chair",
                    0.42, null, "hour", "promo", "Plan an offer",
                    Util.map("day", parts[0], "hour", Integer.parseInt(parts[1]), "bookings", quiet.getValue()[0]),
                    List.of("R-DEMAND-14"), "next rota", "idle chair cost"));
        }
        for (Staff member : staff.where(Staff::active)) {
            long booked = appointments.countWhere(a -> member.id().equals(a.staffId()) && a.start() != null
                    && a.start().isAfter(LocalDateTime.now()) && a.start().isBefore(LocalDateTime.now().plusDays(7))
                    && a.status().occupiesChair());
            if (booked == 0) {
                out.add(suggestion(Suggestion.STAFFING, "audience:house",
                        member.name() + " has an empty week",
                        "No chairs held in the next 7 days",
                        "Zero forward bookings for an active stylist; the desk can push their clients or open walk-ins",
                        0.6, member.id(), "staff", "open", "Open their diary",
                        Util.map("staffId", member.id()), List.of("R-ROTA-15"), "this week", "unused capacity"));
            }
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> pricing(int limit) {
        List<Suggestion> out = new ArrayList<>();
        Map<String, BigDecimal> categoryMedian = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> byCategory = new LinkedHashMap<>();
        for (ServiceItem service : services.where(ServiceItem::active)) {
            byCategory.computeIfAbsent(Util.nz(service.categoryId()), k -> new ArrayList<>()).add(Money.nz(service.price()));
        }
        byCategory.forEach((category, prices) -> {
            prices.sort(Comparator.naturalOrder());
            categoryMedian.put(category, prices.get(prices.size() / 2));
        });
        LocalDateTime from = LocalDateTime.now().minusDays(60);
        for (ServiceItem service : services.where(ServiceItem::active)) {
            long bookings = appointments.countWhere(a -> a.start() != null && a.start().isAfter(from)
                    && a.serviceIds().contains(service.id()) && a.status() == AppointmentStatus.COMPLETED);
            BigDecimal median = categoryMedian.getOrDefault(Util.nz(service.categoryId()), BigDecimal.ZERO);
            if (median.signum() <= 0) continue;
            double ratio = Money.nz(service.price()).divide(median, 3, java.math.RoundingMode.HALF_UP).doubleValue();
            if (bookings >= 12 && ratio < 0.85) {
                BigDecimal suggested = Money.scale(median.multiply(new BigDecimal("0.95")));
                out.add(suggestion(Suggestion.PRICING, "audience:house",
                        "Under-priced: " + service.name(),
                        Money.inr(service.price()) + " against a category median of " + Money.inr(median)
                                + " \u00b7 " + bookings + " chairs in 60 days",
                        "Demand is high (" + bookings + " completed) while the price sits at "
                                + Math.round(ratio * 100) + "% of the category median",
                        0.66, service.id(), "service", "review", "Review the price",
                        Util.map("serviceId", service.id(), "current", service.price(), "suggested", suggested,
                                "median", median, "bookings", bookings),
                        List.of("R-PRICE-16"), "next menu",
                        Money.inr(Money.multiply(Money.subtract(suggested, service.price()), (int) bookings)) + " per 60 days"));
            } else if (bookings <= 2 && ratio > 1.2) {
                out.add(suggestion(Suggestion.PRICING, "audience:house",
                        "Slow mover: " + service.name(),
                        Money.inr(service.price()) + " \u00b7 only " + bookings + " chairs in 60 days",
                        "Priced at " + Math.round(ratio * 100) + "% of the category median with almost no demand; "
                                + "an introductory offer or a lookbook feature would test it",
                        0.48, service.id(), "service", "promo", "Run an offer",
                        Util.map("serviceId", service.id(), "current", service.price(), "bookings", bookings),
                        List.of("R-PRICE-16"), "next menu", "unused menu space"));
            }
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> loyaltyPush(BusinessSettings config, int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (Customer customer : customers.where(c -> !c.archived() && c.lifetimeValue() != null)) {
            BusinessSettings.Tier tier = config.tierFor(customer.lifetimeValue());
            BusinessSettings.Tier next = null;
            for (BusinessSettings.Tier candidate : Util.nullSafe(config.loyaltyTiers())) {
                BigDecimal min = candidate.minSpend() == null ? BigDecimal.ZERO : candidate.minSpend();
                if (min.compareTo(Money.nz(customer.lifetimeValue())) > 0
                        && (next == null || min.compareTo(next.minSpend()) < 0)) next = candidate;
            }
            if (next == null || tier == null) continue;
            BigDecimal gap = Money.subtract(next.minSpend(), customer.lifetimeValue());
            double proximity = 1 - Money.toDouble(gap) / Math.max(1, Money.toDouble(next.minSpend()));
            if (proximity < 0.8) continue;
            out.add(suggestion(Suggestion.LOYALTY, "audience:" + customer.id(),
                    customer.fullName() + " is close to " + next.name(),
                    Money.inr(gap) + " away \u00b7 currently " + tier.name(),
                    "Lifetime value is " + Money.inr(customer.lifetimeValue()) + " against the "
                            + Money.inr(next.minSpend()) + " threshold for " + next.name()
                            + " (" + Math.round(proximity * 100) + "% of the way)",
                    Math.min(1, 0.5 + proximity * 0.4), customer.id(), "customer", "message",
                    "Offer the tier push", Util.map("tier", next.name(), "gap", gap, "perk", next.perk()),
                    List.of("R-TIER-17"), "this month", Money.inr(gap) + " to convert"));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<Suggestion> housekeeping(int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (ServiceItem service : services.where(s -> s.active() && Util.blank(s.editorial()) && Util.blank(s.description()))) {
            out.add(suggestion(Suggestion.PRICING, "audience:house", "Menu copy missing: " + service.name(),
                    "No description or editorial line", "The public menu shows a blank beside a "
                            + Money.inr(service.price()) + " service", 0.35, service.id(), "service",
                    "edit", "Write the copy", Util.map("serviceId", service.id()),
                    List.of("R-HOUSE-18"), "this month", "conversion"));
        }
        for (Look look : looks.where(l -> l.active() && (l.serviceIds() == null || l.serviceIds().isEmpty()))) {
            out.add(suggestion(Suggestion.PRICING, "audience:house", "Lookbook entry not bookable: " + look.title(),
                    "No services linked", "A look with no linked services cannot be booked from the editorial page",
                    0.3, look.id(), "look", "edit", "Link services", Util.map("lookId", look.id()),
                    List.of("R-HOUSE-18"), "this month", "conversion"));
        }
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    // ================================================================== state & meta

    /** Records staff feedback (dismiss / save / done) against a suggestion key. */
    public SuggestionState setState(String key, String status, String note, String entityId, String kind, Actor actor) {
        SuggestionState existing = states.firstWhere(s -> key.equals(s.key())).orElse(null);
        SuggestionState state = new SuggestionState(existing == null ? Util.id("sgs") : existing.id(), key,
                status == null ? SuggestionState.DISMISSED : status.toUpperCase(Locale.ROOT),
                com.sillage.security.Validator.sanitise(note),
                Util.nz(entityId, kindOf(key)), Util.nz(kind, kindOf(key)), LocalDateTime.now(),
                actor == null ? "system" : actor.label());
        states.put(state);
        return state;
    }

    private static String kindOf(String key) {
        if (key == null) return null;
        int at = key.indexOf(':');
        return at < 0 ? key : key.substring(0, at);
    }

    public List<SuggestionState> states(String status) {
        return states.where(s -> status == null || status.equalsIgnoreCase(s.status()));
    }

    private List<Suggestion> filterDismissed(List<Suggestion> suggestions, boolean includeDismissed, int limit) {
        if (includeDismissed) return suggestions.size() > limit ? new ArrayList<>(suggestions.subList(0, limit)) : suggestions;
        Set<String> dismissed = new LinkedHashSet<>();
        for (SuggestionState state : states.where(s -> SuggestionState.DISMISSED.equals(s.status()))) dismissed.add(state.key());
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            if (dismissed.contains(suggestion.id()) || dismissed.contains(suggestion.kind() + ":" + suggestion.entityId())) continue;
            out.add(suggestion);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The published rule catalogue \u2014 what fired, why, and how to tune it. */
    public Map<String, Object> explain() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("R-COOC-1", "Co-occurrence (market basket)", Suggestion.NEXT_SERVICE, W_COOCCURRENCE,
                "service pairs from completed visits",
                "signal = min(1, confidence(A\u2192B)) \u00b7 min(1, ln(lift+1)/ln(4)); lift = confidence / base rate of B",
                "support \u2265 " + (int) MIN_PAIR_SUPPORT + " pairs, lift \u2265 " + MIN_LIFT));
        rules.add(rule("R-MARKOV-2", "First-order transition", Suggestion.NEXT_SERVICE, W_MARKOV,
                "the client's last completed visit",
                "signal = P(B follows A) from the transition table, scaled over 0.6",
                "probability \u2265 0.12"));
        rules.add(rule("R-REBOOK-3", "Rebooking interval (RFM)", Suggestion.REBOOK, W_REBOOK,
                "median gap between visits containing the same service",
                "due = daysSince / medianGap; signal = min(1, due / 1.2)",
                "due \u2265 0.75"));
        rules.add(rule("R-ROTATE-4", "Category rotation", Suggestion.NEXT_SERVICE, W_ROTATION,
                "categories already on the client's record",
                "flat signal when a complementary category has never been booked",
                "rotation table match, service not yet booked"));
        rules.add(rule("R-SEASON-5", "Season table", Suggestion.NEXT_SERVICE, W_SEASON,
                "calendar month and festival windows",
                "flat signal when the service text matches a seasonal keyword",
                "keyword list per season"));
        rules.add(rule("R-STAFF-6", "Chair affinity", Suggestion.NEXT_SERVICE, W_STAFF,
                "preferred stylist or most-used stylist",
                "flat signal when that stylist is certified for the service",
                "stylist active and certified"));
        rules.add(rule("R-OCCASION-7", "Birthday / occasion", Suggestion.OCCASION, 0.86,
                "date of birth against today",
                "flat high score inside the window",
                "birthday within 30 days"));
        rules.add(rule("R-BUNDLE-8", "Bundle candidates", Suggestion.BUNDLE, 1.0,
                "pair support, confidence and lift",
                "score = confidence \u00b7 lift / 2",
                "support \u2265 3 pairs, confidence \u2265 22%, lift \u2265 1.3"));
        rules.add(rule("R-UPSELL-9", "Point-of-sale add-on", Suggestion.UPSELL, 1.0,
                "services already in the cart",
                "same co-occurrence signal, filtered by the client's spend band",
                "price \u2264 1.6 \u00d7 average ticket"));
        rules.add(rule("R-RETAIL-10", "Retail attach", Suggestion.UPSELL, 0.55,
                "products linked to a service in the cart",
                "flat signal when stock is available",
                "stock > 0"));
        rules.add(rule("R-WINBACK-11", "Win-back", Suggestion.WINBACK, 1.0,
                "days since last visit against 1.5 \u00d7 personal median gap",
                "score = 0.4 + overshoot/180 + lifetime value factor",
                "gap > 1.5 \u00d7 median (or 75 days)"));
        rules.add(rule("R-CHURN-12", "Churn risk", Suggestion.RETENTION, 1.0,
                "gap drift, no-shows, cancellations, ratings, recency",
                "sum of weighted factors, banded at 0.5 and 0.7",
                "\u2265 3 visits on record"));
        rules.add(rule("R-STOCK-13", "Reorder point", Suggestion.INVENTORY, 1.0,
                "stock, reorder level and forecast consumption from booked services",
                "qty = max(reorderQty, 2 \u00d7 reorderLevel \u2212 stock)",
                "stock \u2264 reorder level or forecast \u2264 reorder level"));
        rules.add(rule("R-DEMAND-14", "Demand curve", Suggestion.STAFFING, 1.0,
                "chair minutes per weekday-hour over 56 days",
                "peak and quiet cells reported",
                "\u2265 8 weeks of history"));
        rules.add(rule("R-ROTA-15", "Empty diary", Suggestion.STAFFING, 0.6,
                "forward bookings per stylist",
                "flat signal when the next 7 days hold nothing",
                "stylist active"));
        rules.add(rule("R-PRICE-16", "Price position", Suggestion.PRICING, 1.0,
                "service price against its category median and 60-day demand",
                "suggested price = 0.95 \u00d7 median",
                "under: \u226512 bookings and price < 85% of median; over: \u22642 bookings and price > 120%"));
        rules.add(rule("R-TIER-17", "Tier proximity", Suggestion.LOYALTY, 1.0,
                "lifetime value against the next tier threshold",
                "score = 0.5 + 0.4 \u00d7 proximity",
                "proximity \u2265 80%"));
        rules.add(rule("R-HOUSE-18", "Housekeeping", Suggestion.PRICING, 0.35,
                "menu copy and lookbook linkage",
                "flat low score",
                "missing description or unlinked look"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", ENGINE);
        out.put("rules", rules);
        out.put("weights", Util.map("cooccurrence", W_COOCCURRENCE, "markov", W_MARKOV, "rebook", W_REBOOK,
                "rotation", W_ROTATION, "season", W_SEASON, "staff", W_STAFF));
        out.put("hardFilters", List.of("services above 1.8 \u00d7 the client's average ticket are dropped",
                "services booked inside their cooldown window are dropped",
                "retired or non-bookable services are dropped"));
        out.put("noModel", "No training, no weights learned from data, no third-party API. Every score is reproducible "
                + "from the collections in ./data by re-running the same arithmetic.");
        out.put("seasonTable", SEASONS);
        out.put("rotationTable", ROTATION);
        return out;
    }

    private static Map<String, Object> rule(String id, String name, String kind, double weight, String inputs,
                                            String formula, String threshold) {
        return Util.map("id", id, "name", name, "kind", kind, "weight", weight, "inputs", inputs,
                "formula", formula, "threshold", threshold);
    }

    public Map<String, Object> stats() {
        Map<String, Map<String, double[]>> pairs = pairStatistics();
        int pairCount = pairs.values().stream().mapToInt(Map::size).sum();
        return Util.map(
                "engine", ENGINE,
                "completedVisits", appointments.countWhere(a -> a.status() == AppointmentStatus.COMPLETED),
                "customersWithHistory", sequences().size(),
                "servicePairs", pairCount,
                "transitionRows", transitions().size(),
                "rulesPublished", 18,
                "statesRecorded", states.size());
    }

    // ================================================================== helpers

    private record Candidate(ServiceItem service, double score, List<String> rules, List<String> reasons) {}

    private Suggestion suggestion(String kind, String audience, String title, String detail, String rationale,
                                  double score, String entityId, String entityKind, String action, String actionLabel,
                                  Map<String, Object> payload, List<String> rules, String horizon, String impact) {
        String id = kind + ":" + Util.nz(entityId) + ":" + Util.sha256Hex(title + detail).substring(0, 6);
        return new Suggestion(id, ENGINE, kind, title, detail, rationale, Math.round(Math.min(1, Math.max(0, score)) * 1000) / 1000.0,
                Suggestion.confidenceFor(score), audience, entityId, entityKind, action, actionLabel,
                payload == null ? Map.of() : payload, rules, horizon, impact, LocalDateTime.now());
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private String rationale(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return "Rule threshold met";
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(reasons));
        return Util.join(unique.subList(0, Math.min(3, unique.size())), "; ");
    }

    private String name(String serviceId) {
        if (Util.blank(serviceId)) return "that service";
        ServiceItem service = services.get(serviceId);
        return service == null ? "that service" : service.name();
    }

    private BigDecimal averageTicket(Customer customer, List<Appointment> history) {
        List<Invoice> bills = invoices.where(i -> customer.id().equals(i.customerId()));
        if (bills.isEmpty()) {
            return Money.nz(history.isEmpty() ? BigDecimal.ZERO
                    : history.stream().map(Appointment::price).reduce(BigDecimal.ZERO, Money::add));
        }
        BigDecimal sum = bills.stream().map(Invoice::total).reduce(BigDecimal.ZERO, Money::add);
        return Money.divide(sum, BigDecimal.valueOf(bills.size()));
    }

    private long medianInterval(Map<String, Long> intervals, Set<String> booked, List<Appointment> history) {
        List<Long> values = new ArrayList<>();
        for (String id : booked) {
            Long value = intervals.get(id);
            if (value != null && value > 0) values.add(value);
        }
        if (values.isEmpty()) {
            List<Long> gaps = new ArrayList<>();
            List<Appointment> sorted = new ArrayList<>(history);
            sorted.sort(Comparator.comparing(Appointment::start, Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).start() == null || sorted.get(i - 1).start() == null) continue;
                long days = ChronoUnit.DAYS.between(sorted.get(i - 1).start(), sorted.get(i).start());
                if (days > 0) gaps.add(days);
            }
            values = gaps;
        }
        if (values.isEmpty()) return 0;
        values.sort(Comparator.naturalOrder());
        return values.get(values.size() / 2);
    }

    private String favouriteService(List<Appointment> history) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Appointment appointment : history) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                counts.merge(item.serviceId(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private Staff mostUsedStaff(List<Appointment> history) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Appointment appointment : history) counts.merge(appointment.staffId(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> staff.get(e.getKey())).orElse(null);
    }

    private String seasonKey(LocalDate date, BusinessSettings config) {
        String holiday = config.holidayNameOn(date);
        if (holiday != null) return "FESTIVE";
        for (BusinessSettings.Holiday h : Util.nullSafe(config.holidays())) {
            if (h.date() == null) continue;
            long days = ChronoUnit.DAYS.between(date, h.date().withYear(date.getYear()));
            if (days >= 0 && days <= 21) return "FESTIVE";
        }
        int month = date.getMonthValue();
        if (month >= 6 && month <= 9) return "MONSOON";
        if (month >= 10 || month <= 1) return "WINTER";
        if (month >= 2 && month <= 3) return "SPRING";
        return "SUMMER";
    }

    private String nextSlotFor(String serviceId, Staff member) {
        LocalDate date = LocalDate.now();
        for (int i = 0; i < 10; i++) {
            List<LocalDateTime> free = freeSlots(serviceId, member == null ? null : member.id(), date.plusDays(i));
            if (!free.isEmpty()) return free.get(0).toString();
        }
        return null;
    }

    private List<LocalDateTime> freeSlots(String serviceId, String staffId, LocalDate date) {
        List<LocalDateTime> out = new ArrayList<>();
        BusinessSettings config = settings.current();
        if (config.isClosedOn(date)) return out;
        ServiceItem service = services.get(serviceId);
        int duration = service == null ? config.slotMinutes() : service.durationMinutes();
        List<Staff> members = Util.present(staffId) && staff.get(staffId) != null
                ? List.of(staff.get(staffId)) : staff.where(Staff::active);
        for (Staff member : members) {
            Staff.DayHours hours = member.hoursFor(date.getDayOfWeek());
            if (hours == null || hours.closed() || member.offOn(date)) continue;
            LocalTime cursor = LocalTime.parse(hours.start().length() == 5 ? hours.start() + ":00" : hours.start());
            LocalTime close = LocalTime.parse(hours.end().length() == 5 ? hours.end() + ":00" : hours.end());
            while (cursor.plusMinutes(duration).isBefore(close)) {
                LocalDateTime start = date.atTime(cursor);
                if (start.isAfter(LocalDateTime.now().plusMinutes(config.minAdvanceMinutes())) && !taken(member.id(), start, duration, config)) {
                    out.add(start);
                    if (out.size() >= 4) return out;
                }
                cursor = cursor.plusMinutes(Math.max(15, config.slotMinutes()));
            }
        }
        return out;
    }

    private boolean taken(String staffId, LocalDateTime start, int duration, BusinessSettings config) {
        LocalDateTime end = start.plusMinutes(duration + config.bufferMinutes());
        for (Appointment appointment : appointments.where(a -> staffId.equals(a.staffId()) && a.status().occupiesChair()
                && a.start() != null && a.end() != null)) {
            if (start.isBefore(appointment.end()) && appointment.start().isBefore(end)) return true;
        }
        return false;
    }

    private String daysSinceLabel(Customer customer) {
        long days = customer.daysSinceLastVisit();
        return days < 0 ? "new client" : days + " days since the last visit";
    }

    private String horizonOf(String label) {
        return Util.present(label) ? label : "this month";
    }

    private static String trim(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    /** Combined feed for the console home page. */
    public Map<String, Object> feed(Actor actor, int limit, boolean includeDismissed) {
        long started = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Suggestion> desk = new ArrayList<>();
        desk.addAll(operations(6));
        desk.addAll(bundles(2));
        List<Suggestion> clients = new ArrayList<>();
        for (Customer customer : topClients(6)) {
            clients.addAll(filterDismissed(forCustomer(customer.id(), 2, includeDismissed), includeDismissed, 2));
        }
        out.put("desk", filterDismissed(desk, includeDismissed, limit));
        out.put("clients", filterDismissed(clients, includeDismissed, limit));
        out.put("engine", ENGINE);
        out.put("tookMillis", (System.nanoTime() - started) / 1_000_000);
        out.put("generatedAt", LocalDateTime.now().toString());
        out.put("ruleCount", 18);
        return out;
    }

    private List<Customer> topClients(int limit) {
        List<Customer> all = customers.where(c -> !c.archived() && c.visits() >= 2);
        all.sort(Comparator.comparing((Customer c) -> Money.nz(c.lifetimeValue())).reversed());
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** Today's actionable list for the front desk. */
    public List<Suggestion> today(int limit) {
        List<Suggestion> out = new ArrayList<>();
        BusinessSettings config = settings.current();
        for (Appointment appointment : appointments.where(a -> a.start() != null
                && a.start().toLocalDate().equals(LocalDate.now()) && a.status() == AppointmentStatus.REQUESTED)) {
            Customer customer = appointment.customerId() == null ? null : customers.get(appointment.customerId());
            out.add(suggestion(Suggestion.REBOOK, "audience:desk",
                    "Confirm " + Util.nz(customer == null ? appointment.guestName() : customer.fullName()),
                    Util.formatTime(appointment.start()) + " \u00b7 " + Util.join(appointment.serviceNames(), ", "),
                    "An online request is still unconfirmed and the chair is "
                            + ChronoUnit.HOURS.between(LocalDateTime.now(), appointment.start()) + " hours away",
                    0.8, appointment.id(), "appointment", "confirm", "Confirm the chair",
                    Util.map("appointmentId", appointment.id()), List.of("R-DESK-19"), "today",
                    Money.inr(appointment.price()) + " at risk"));
        }
        out.addAll(reorder(3));
        for (Customer customer : customers.where(c -> !c.archived() && c.isBirthdayWithin(7))) {
            out.add(suggestion(Suggestion.OCCASION, "audience:desk",
                    "Birthday this week: " + customer.fullName(),
                    Util.formatDate(customer.birthday()) + " \u00b7 " + Util.nz(customer.tier()),
                    "Birthday falls inside the next 7 days; tier perk applies",
                    0.75, customer.id(), "customer", "message", "Send the note",
                    Util.map("template", "birthday"), List.of("R-OCCASION-7"), "this week", "retention"));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return filterDismissed(out, false, limit);
    }

    /** Weekday-hour demand grid, exposed so the console can draw the heat map. */
    public List<Map<String, Object>> demandGrid(int weeks) {
        LocalDate from = LocalDate.now().minusWeeks(Math.max(1, weeks));
        Map<String, int[]> cells = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            for (int hour = 8; hour <= 21; hour++) cells.put(day.name() + "|" + hour, new int[2]);
        }
        for (Appointment appointment : appointments.where(a -> a.start() != null && a.start().isAfter(from.atStartOfDay())
                && a.status().occupiesChair())) {
            String key = appointment.start().getDayOfWeek().name() + "|" + appointment.start().getHour();
            int[] cell = cells.get(key);
            if (cell == null) continue;
            cell[0]++;
            cell[1] += appointment.durationMinutes();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        cells.forEach((key, cell) -> {
            String[] parts = key.split("\\|");
            out.add(Util.map("day", parts[0], "hour", Integer.parseInt(parts[1]),
                    "bookings", cell[0], "minutes", cell[1]));
        });
        return out;
    }
}
