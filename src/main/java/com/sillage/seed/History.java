package com.sillage.seed;

import com.sillage.Salon;
import com.sillage.core.Log;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Expense;
import com.sillage.model.GiftCard;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.Notification;
import com.sillage.model.Payment;
import com.sillage.model.PaymentMethod;
import com.sillage.model.PaymentStatus;
import com.sillage.model.Product;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.model.SuggestionState;
import com.sillage.service.PricingService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Twelve weeks of trading history plus three weeks of diary. The point of the seed is that the
 * analytics, the rule engine and the search index all have something real to work on from the first
 * second \u2014 and that the numbers agree with each other.
 */
final class History {

    private static final DateTimeFormatter CODE_DAY = DateTimeFormatter.ofPattern("ddMM");

    /** Services that plausibly share a chair visit. */
    private static final String[][] PAIRS = {
            {"svc-signature-cut", "svc-blow-dry"},
            {"svc-global-colour", "svc-toner-gloss"},
            {"svc-balayage", "svc-olaplex"},
            {"svc-signature-facial", "svc-brow"},
            {"svc-manicure", "svc-pedicure"},
            {"svc-gents-cut", "svc-shave"},
            {"svc-hair-spa", "svc-champissage"},
            {"svc-hydra-facial", "svc-lash-lift"},
            {"svc-bridal-full", "svc-family-drape"},
            {"svc-roots", "svc-fringe"},
            {"svc-gel-manicure", "svc-brow"},
            {"svc-highlights", "svc-olaplex"},
    };

    private static final String[] RETAIL = {"prd-olaplex3", "prd-kerastase-shampoo", "prd-moroccanoil",
            "prd-scalp-serum", "prd-heat-protect", "prd-beard-oil", "prd-silk-scrunchie", "prd-spf50",
            "prd-cuticle-oil"};

    private History() {}

    static void populate(Salon salon, Random rnd) {
        BusinessSettings config = salon.settingsService.current();
        LocalDate today = LocalDate.now();

        List<Appointment> diary = diary(salon, config, rnd, today);
        Map<String, Invoice> invoices = bill(salon, config, rnd, diary, today);
        rollUpClients(salon, config, invoices);
        counters(salon);
        giftCards(salon, rnd);
        expenses(salon, rnd, today);
        outbox(salon, today);
        suggestionStates(salon, today);

        Log.info("seeded %d appointments and %d invoices", diary.size(), invoices.size());
    }

    // ------------------------------------------------------------------ diary

    private static List<Appointment> diary(Salon salon, BusinessSettings config, Random rnd, LocalDate today) {
        List<Staff> team = People.TEAM;
        List<Customer> clients = People.CLIENTS;
        Map<String, List<LocalDateTime[]>> held = new LinkedHashMap<>();
        List<Appointment> created = new ArrayList<>();
        Map<LocalDate, Integer> codeCounters = new LinkedHashMap<>();

        for (int offset = -120; offset <= 21; offset++) {
            LocalDate date = today.plusDays(offset);
            if (config.isClosedOn(date)) continue;
            int count = demandFor(date, rnd);
            for (int i = 0; i < count; i++) {
                Staff member = team.get(rnd.nextInt(team.size()));
                List<ServiceItem> options = certifiedFor(member, salon);
                if (options.isEmpty()) continue;
                ServiceItem service = options.get(rnd.nextInt(options.size()));
                Staff.DayHours hours = member.hoursFor(date.getDayOfWeek());
                if (hours == null || hours.closed()) continue;
                LocalTime start = pickTime(hours, service.durationMinutes(), rnd);
                if (start == null) continue;
                LocalDateTime from = date.atTime(start);
                LocalDateTime to = from.plusMinutes(service.durationMinutes());
                if (!free(held, member.id(), from, to, config.bufferMinutes())) continue;
                hold(held, member.id(), from, to);

                List<Appointment.Item> items = new ArrayList<>();
                items.add(new Appointment.Item(service.id(), null, service.name(), service.durationMinutes(),
                        service.price()));
                int minutes = service.durationMinutes();

                if (rnd.nextInt(100) < 24) {
                    ServiceItem partner = partnerFor(service, salon, rnd);
                    if (partner != null && member.certifiedFor(partner.id())) {
                        items.add(new Appointment.Item(partner.id(), null, partner.name(),
                                partner.durationMinutes(), partner.price()));
                        minutes += partner.durationMinutes();
                    }
                }
                if (rnd.nextInt(100) < 14 && !service.variants().isEmpty()) {
                    ServiceItem.Variant variant = service.variants().get(rnd.nextInt(service.variants().size()));
                    items.set(0, new Appointment.Item(service.id(), variant.id(),
                            service.name() + " \u00b7 " + variant.label(),
                            service.durationMinutes() + variant.extraMinutes(),
                            Money.add(service.price(), variant.extraPrice())));
                    minutes += variant.extraMinutes();
                }

                BigDecimal price = items.stream().map(Appointment.Item::price).reduce(BigDecimal.ZERO, Money::add);
                Customer customer = pickClient(clients, member, rnd);
                boolean online = rnd.nextInt(100) < 38;
                AppointmentStatus status = statusFor(date, from, today, online, rnd);
                BigDecimal deposit = price.compareTo(new BigDecimal("10000")) > 0 && config.requireDeposit()
                        ? Money.percent(price, config.depositPct().doubleValue()) : BigDecimal.ZERO;

                int queue = codeCounters.merge(date, 1, Integer::sum);
                String code = "A" + date.format(CODE_DAY) + "-" + String.format("%03d", queue);
                LocalDateTime booked = from.minusDays(1 + rnd.nextInt(online ? 12 : 4));

                Appointment appointment = new Appointment(Util.id("apt"), code,
                        customer == null ? null : customer.id(),
                        customer == null ? guestName(rnd) : null,
                        customer == null ? guestPhone(rnd) : null,
                        customer == null ? null : null,
                        member.id(), items, from, to, minutes, status, price, deposit,
                        noteFor(service, rnd), null,
                        online ? "web" : (rnd.nextInt(100) < 30 ? "phone" : "desk"),
                        queue + 1, null,
                        booked, booked,
                        status == AppointmentStatus.REQUESTED ? null : booked.plusHours(rnd.nextInt(6)),
                        status == AppointmentStatus.COMPLETED ? to : null,
                        status == AppointmentStatus.CANCELLED ? from.minusHours(rnd.nextInt(20) + 1) : null,
                        status == AppointmentStatus.CANCELLED ? cancellationReason(rnd) : null,
                        status == AppointmentStatus.CONFIRMED || status == AppointmentStatus.COMPLETED
                                ? from.minusDays(1) : null,
                        status == AppointmentStatus.COMPLETED && rnd.nextInt(100) < 62 ? 4 + rnd.nextInt(2) : null,
                        status == AppointmentStatus.COMPLETED && rnd.nextInt(100) < 20
                                ? ratingComment(rnd) : null,
                        online ? customer == null ? "guest" : customer.firstName() : "Front desk");
                created.add(appointment);
                salon.appointments.put(appointment);
            }
        }
        return created;
    }

    private static int demandFor(LocalDate date, Random rnd) {
        int base = switch (date.getDayOfWeek()) {
            case TUESDAY, WEDNESDAY -> 4;
            case THURSDAY -> 5;
            case FRIDAY -> 7;
            case SATURDAY -> 9;
            case SUNDAY -> 6;
            default -> 0;
        };
        return Math.max(1, base + rnd.nextInt(3) - 1);
    }

    private static List<ServiceItem> certifiedFor(Staff member, Salon salon) {
        List<ServiceItem> out = new ArrayList<>();
        for (String id : Util.nullSafe(member.serviceIds())) {
            ServiceItem service = salon.services.get(id);
            if (service != null && service.active() && service.durationMinutes() > 0) out.add(service);
        }
        return out;
    }

    private static ServiceItem partnerFor(ServiceItem service, Salon salon, Random rnd) {
        for (String[] pair : PAIRS) {
            if (pair[0].equals(service.id())) return salon.services.get(pair[1]);
            if (pair[1].equals(service.id())) return salon.services.get(pair[0]);
        }
        return rnd.nextInt(100) < 20 ? salon.services.get("svc-toner-gloss") : null;
    }

    private static LocalTime pickTime(Staff.DayHours hours, int duration, Random rnd) {
        LocalTime open = parse(hours.start());
        LocalTime close = parse(hours.end());
        if (open == null || close == null) return null;
        int minutes = (int) java.time.Duration.between(open, close).toMinutes() - duration;
        if (minutes <= 15) return null;
        int step = 30;
        int slots = minutes / step;
        return open.plusMinutes((long) rnd.nextInt(Math.max(1, slots)) * step);
    }

    private static LocalTime parse(String value) {
        if (Util.blank(value)) return null;
        return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
    }

    private static boolean free(Map<String, List<LocalDateTime[]>> held, String staffId, LocalDateTime from,
                                LocalDateTime to, int buffer) {
        LocalDateTime guarded = to.plusMinutes(Math.max(0, buffer));
        for (LocalDateTime[] window : held.getOrDefault(staffId, List.of())) {
            if (from.isBefore(window[1]) && window[0].isBefore(guarded)) return false;
        }
        return true;
    }

    private static void hold(Map<String, List<LocalDateTime[]>> held, String staffId, LocalDateTime from,
                             LocalDateTime to) {
        held.computeIfAbsent(staffId, k -> new ArrayList<>())
                .add(new LocalDateTime[]{from, to.plusMinutes(15)});
    }

    private static Customer pickClient(List<Customer> clients, Staff member, Random rnd) {
        if (rnd.nextInt(100) < 8) return null; // walk-in guest with no record yet
        List<Customer> fans = clients.stream().filter(c -> member.id().equals(c.preferredStaffId())).toList();
        if (!fans.isEmpty() && rnd.nextInt(100) < 65) return fans.get(rnd.nextInt(fans.size()));
        return clients.get(rnd.nextInt(clients.size()));
    }

    private static AppointmentStatus statusFor(LocalDate date, LocalDateTime start, LocalDate today, boolean online,
                                               Random rnd) {
        if (date.isBefore(today)) {
            int roll = rnd.nextInt(100);
            if (roll < 87) return AppointmentStatus.COMPLETED;
            if (roll < 94) return AppointmentStatus.CANCELLED;
            return AppointmentStatus.NO_SHOW;
        }
        if (date.isEqual(today)) {
            if (start.isBefore(LocalDateTime.now())) {
                int roll = rnd.nextInt(100);
                if (roll < 60) return AppointmentStatus.COMPLETED;
                if (roll < 80) return AppointmentStatus.IN_CHAIR;
                if (roll < 92) return AppointmentStatus.ARRIVED;
                return AppointmentStatus.NO_SHOW;
            }
            return online && rnd.nextInt(100) < 40 ? AppointmentStatus.REQUESTED : AppointmentStatus.CONFIRMED;
        }
        if (online) return rnd.nextInt(100) < 35 ? AppointmentStatus.REQUESTED : AppointmentStatus.CONFIRMED;
        return rnd.nextInt(100) < 5 ? AppointmentStatus.WAITLISTED : AppointmentStatus.CONFIRMED;
    }

    private static String noteFor(ServiceItem service, Random rnd) {
        String[] notes = {"Prefers the usual finish.", "In a hurry \u2014 meeting at four.",
                "Bring the reference photograph.", "First visit with this stylist.",
                "Allergic to fragrance \u2014 check the shelf.", "Wants to keep the length.",
                "Travelling from Jalandhar; keep to the hour."};
        return rnd.nextInt(100) < 35 ? notes[rnd.nextInt(notes.length)] : null;
    }

    private static String cancellationReason(Random rnd) {
        String[] reasons = {"Unwell", "Travel", "Family function", "Rescheduled by phone", "Weather"};
        return reasons[rnd.nextInt(reasons.length)];
    }

    private static String ratingComment(Random rnd) {
        String[] comments = {"Exactly what I asked for.", "Booked the next one before leaving.",
                "The colour is settling beautifully.", "A little late to start, worth the wait.",
                "Best blow dry in the city."};
        return comments[rnd.nextInt(comments.length)];
    }

    private static String guestName(Random rnd) {
        String[] first = {"Neha", "Simran", "Aman", "Rohit", "Fatima", "Jaskirat", "Pooja", "Daniel"};
        String[] last = {"Sharma", "Grewal", "Kohli", "Nanda", "Sidhu", "Khosa", "Bhatti"};
        return first[rnd.nextInt(first.length)] + " " + last[rnd.nextInt(last.length)];
    }

    private static String guestPhone(Random rnd) {
        return "+91 98" + (100 + rnd.nextInt(800)) + " " + (60000 + rnd.nextInt(39000));
    }

    // ------------------------------------------------------------------ billing

    private static Map<String, Invoice> bill(Salon salon, BusinessSettings config, Random rnd,
                                             List<Appointment> diary, LocalDate today) {
        Map<String, Invoice> out = new LinkedHashMap<>();
        List<Appointment> completed = diary.stream()
                .filter(a -> a.status() == AppointmentStatus.COMPLETED && a.start() != null)
                .sorted((a, b) -> a.start().compareTo(b.start()))
                .toList();

        for (Appointment appointment : completed) {
            Customer customer = appointment.customerId() == null ? null : salon.customers.get(appointment.customerId());
            Map<String, Object> cart = new LinkedHashMap<>();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                items.add(Util.map("serviceId", item.serviceId(), "variantId", item.variantId(),
                        "staffId", appointment.staffId()));
            }
            cart.put("items", items);
            cart.put("staffId", appointment.staffId());

            List<Map<String, Object>> retail = new ArrayList<>();
            if (rnd.nextInt(100) < 28) {
                Product product = salon.products.get(RETAIL[rnd.nextInt(RETAIL.length)]);
                if (product != null) retail.add(Util.map("productId", product.id(), "qty", 1 + rnd.nextInt(2)));
            }
            if (!retail.isEmpty()) cart.put("products", retail);
            if (rnd.nextInt(100) < 16) cart.put("gratuity", new BigDecimal(100 + rnd.nextInt(9) * 100));
            if (rnd.nextInt(100) < 12) {
                String promo = promoFor(appointment, rnd);
                // only offer a code the bill actually clears, so nothing has to be re-quoted
                if (promo != null && appointment.price().compareTo(minimumFor(promo)) >= 0) {
                    cart.put("promoCode", promo);
                }
            }

            PricingService.Quote quote;
            try {
                quote = salon.pricing.quote(cart, customer);
            } catch (RuntimeException e) {
                Log.warn("seed invoice skipped for %s: %s", appointment.code(), e.getMessage());
                continue;
            }

            LocalDateTime when = appointment.end() == null ? appointment.start() : appointment.end();
            String number = salon.settingsService.nextInvoiceNumber(null);
            List<Invoice.Line> lines = quote.lines().stream().map(PricingService.QuoteLine::toInvoiceLine).toList();
            Payment payment = tender(salon, rnd, quote.total(), number, when, appointment.staffId());
            List<Payment> payments = payment == null ? List.of() : List.of(payment);
            BigDecimal paid = payment == null ? BigDecimal.ZERO : payment.amount();
            InvoiceStatus status = Money.compare(paid, quote.total()) >= 0 ? InvoiceStatus.PAID
                    : (paid.signum() > 0 ? InvoiceStatus.PARTIAL : InvoiceStatus.UNPAID);
            int points = customer == null ? 0 : salon.loyalty.earn(quote.total(), customer, config);

            Invoice invoice = new Invoice(Util.id("inv"), number,
                    customer == null ? null : customer.id(),
                    customer == null ? Util.nz(appointment.guestName(), "Walk-in guest") : customer.fullName(),
                    customer == null ? appointment.guestPhone() : customer.phone(),
                    customer == null ? null : customer.email(),
                    List.of(appointment.id()), lines,
                    quote.subtotal(), quote.discount(), quote.promoCode(), quote.loyaltyRedemption(),
                    quote.pointsRedeemed(), quote.gratuity(), quote.taxable(), quote.cgst(), quote.sgst(),
                    quote.taxTotal(), quote.roundOff(), quote.total(), paid,
                    Money.subtract(quote.total(), paid), status, payments, points,
                    null, null, when, status == InvoiceStatus.PAID ? when : null,
                    billedBy(rnd), appointment.staffId(), BusinessSettings.fiscalYear(), config.gstin(),
                    "web".equals(appointment.source()) ? "online" : "desk", null, false, 0);

            String code = salon.invoicing.receiptCode(invoice);
            invoice = withSeal(invoice, code);
            salon.invoices.put(invoice);
            out.put(appointment.id(), invoice);

            // tie the booking to its bill
            Appointment linked = new Appointment(appointment.id(), appointment.code(), appointment.customerId(),
                    appointment.guestName(), appointment.guestPhone(), appointment.guestEmail(), appointment.staffId(),
                    appointment.items(), appointment.start(), appointment.end(), appointment.durationMinutes(),
                    appointment.status(), appointment.price(), appointment.deposit(), appointment.notes(),
                    appointment.internalNotes(), appointment.source(), appointment.queueNumber(), invoice.id(),
                    appointment.createdAt(), appointment.updatedAt(), appointment.confirmedAt(),
                    appointment.completedAt(), appointment.cancelledAt(), appointment.cancellationReason(),
                    appointment.reminderSentAt(), appointment.rating(), appointment.ratingComment(),
                    appointment.createdBy());
            salon.appointments.put(linked);
        }
        return out;
    }

    private static Invoice withSeal(Invoice invoice, String seal) {
        return new Invoice(invoice.id(), invoice.number(), invoice.customerId(), invoice.customerName(),
                invoice.customerPhone(), invoice.customerEmail(), invoice.appointmentIds(), invoice.lines(),
                invoice.subtotal(), invoice.discount(), invoice.promoCode(), invoice.loyaltyRedemption(),
                invoice.pointsRedeemed(), invoice.gratuity(), invoice.taxable(), invoice.cgst(), invoice.sgst(),
                invoice.taxTotal(), invoice.roundOff(), invoice.total(), invoice.paid(), invoice.balance(),
                invoice.status(), invoice.payments(), invoice.pointsEarned(), invoice.notes(), invoice.footerNote(),
                invoice.createdAt(), invoice.settledAt(), invoice.createdBy(), invoice.staffId(),
                invoice.fiscalYear(), invoice.gstin(), invoice.source(), seal, invoice.printed(),
                invoice.printCount());
    }

    private static BigDecimal minimumFor(String code) {
        return switch (Util.nz(code)) {
            case "ATELIER500", "MONSOONGLOSS" -> new BigDecimal("3000");
            case "FIRSTSILLAGE" -> new BigDecimal("1500");
            default -> BigDecimal.ZERO;
        };
    }

    private static String promoFor(Appointment appointment, Random rnd) {
        boolean colour = Util.nullSafe(appointment.items()).stream()
                .anyMatch(i -> "svc-global-colour".equals(i.serviceId()) || "svc-balayage".equals(i.serviceId())
                        || "svc-highlights".equals(i.serviceId()));
        if (colour && rnd.nextInt(100) < 50) return "MONSOONGLOSS";
        return rnd.nextInt(100) < 50 ? "ATELIER500" : "FIRSTSILLAGE";
    }

    private static String billedBy(Random rnd) {
        String[] names = {"Front desk", "Kiran Dhillon", "Meher Kapur"};
        return names[rnd.nextInt(names.length)];
    }

    private static Payment tender(Salon salon, Random rnd, BigDecimal amount, String number, LocalDateTime when,
                                  String staffId) {
        if (amount.signum() <= 0) return null;
        int roll = rnd.nextInt(100);
        PaymentMethod method;
        if (roll < 30) method = PaymentMethod.CASH;
        else if (roll < 68) method = PaymentMethod.UPI;
        else if (roll < 88) method = PaymentMethod.CARD;
        else if (roll < 94) method = PaymentMethod.NETBANKING;
        else if (roll < 98) method = PaymentMethod.WALLET;
        else method = PaymentMethod.GIFT_CARD;

        String gatewayRef = null;
        String authCode = null;
        String brand = null;
        String last4 = null;
        String expiry = null;
        String token = null;
        String vpa = null;
        String bank = null;
        String wallet = null;
        String giftCode = null;
        boolean threeDs = false;

        switch (method) {
            case CASH -> gatewayRef = "CASH_" + Util.randomDigits(6);
            case UPI -> {
                vpa = "client" + Util.randomDigits(4) + "@" + new String[]{"okhdfcbank", "ybl", "paytm", "ibl"}[rnd.nextInt(4)];
                gatewayRef = "UPI_" + Util.randomDigits(12);
            }
            case CARD -> {
                String pan = rnd.nextBoolean() ? "4" + Util.randomDigits(15) : "5" + Util.randomDigits(15);
                brand = com.sillage.service.PaymentGateway.brandOf(pan);
                last4 = pan.substring(pan.length() - 4);
                expiry = "12/" + (LocalDate.now().getYear() % 100 + 2 + rnd.nextInt(4));
                token = salon.gateway.tokenize(pan);
                gatewayRef = "CARD_" + Util.randomDigits(12);
                authCode = Util.randomDigits(6);
                threeDs = rnd.nextInt(100) < 70;
            }
            case NETBANKING -> {
                bank = new String[]{"HDFC Bank", "ICICI Bank", "Punjab National Bank", "Axis Bank"}[rnd.nextInt(4)];
                gatewayRef = "NB_" + Util.randomDigits(12);
            }
            case WALLET -> {
                wallet = new String[]{"Paytm", "PhonePe", "Amazon Pay"}[rnd.nextInt(3)];
                gatewayRef = "WL_" + Util.randomDigits(12);
            }
            case GIFT_CARD -> {
                giftCode = "SIL-" + Util.randomDigits(3) + "-" + Util.randomDigits(3);
                gatewayRef = "GC_" + giftCode;
            }
            default -> gatewayRef = "PAY_" + Util.randomDigits(8);
        }
        return new Payment(Util.id("pay"), method, amount, PaymentStatus.CAPTURED, gatewayRef, authCode, brand,
                last4, expiry, token, vpa, bank, wallet, giftCode, null, threeDs, null, null, when, when,
                BigDecimal.ZERO, List.of(), "Front desk", null, rnd.nextInt(35), number);
    }

    // ------------------------------------------------------------------ roll-ups

    private record Aggregate(int visits, LocalDateTime first, LocalDateTime last, BigDecimal spend, int points,
                             BigDecimal ratingSum, int ratings, int noShows, int cancellations) {}

    private static void rollUpClients(Salon salon, BusinessSettings config, Map<String, Invoice> invoices) {
        Map<String, Aggregate> rollup = new LinkedHashMap<>();
        invoices.forEach((appointmentId, invoice) -> {
            if (invoice.customerId() == null) return;
            Aggregate current = rollup.get(invoice.customerId());
            Aggregate next = new Aggregate(
                    (current == null ? 0 : current.visits()) + 1,
                    current == null || current.first() == null ? invoice.createdAt() : earliest(current.first(), invoice.createdAt()),
                    current == null || current.last() == null ? invoice.createdAt() : latest(current.last(), invoice.createdAt()),
                    Money.add(current == null ? BigDecimal.ZERO : current.spend(), invoice.total()),
                    (current == null ? 0 : current.points()) + invoice.pointsEarned(),
                    current == null ? BigDecimal.ZERO : current.ratingSum(),
                    current == null ? 0 : current.ratings(),
                    current == null ? 0 : current.noShows(),
                    current == null ? 0 : current.cancellations());
            rollup.put(invoice.customerId(), next);
        });

        for (Appointment appointment : salon.appointments.all()) {
            if (appointment.customerId() == null) continue;
            Aggregate current = rollup.get(appointment.customerId());
            if (current == null) continue;
            double rating = appointment.rating() == null ? 0 : appointment.rating();
            Aggregate next = new Aggregate(current.visits(), current.first(), current.last(), current.spend(),
                    current.points(),
                    Money.add(current.ratingSum(), rating > 0 ? BigDecimal.valueOf(rating) : BigDecimal.ZERO),
                    current.ratings() + (rating > 0 ? 1 : 0),
                    current.noShows() + (appointment.status() == AppointmentStatus.NO_SHOW ? 1 : 0),
                    current.cancellations() + (appointment.status() == AppointmentStatus.CANCELLED ? 1 : 0));
            rollup.put(appointment.customerId(), next);
        }

        rollup.forEach((customerId, aggregate) -> {
            Customer customer = salon.customers.get(customerId);
            if (customer == null) return;
            BigDecimal spend = Money.nz(aggregate.spend());
            BusinessSettings.Tier tier = config.tierFor(spend);
            double average = aggregate.ratings() == 0 ? 0
                    : Math.round(aggregate.ratingSum().doubleValue() / aggregate.ratings() * 10) / 10.0;
            Customer updated = customer.withVisit(spend, aggregate.last(), tier == null ? customer.tier() : tier.name(),
                    aggregate.points());
            updated = new Customer(updated.id(), updated.code(), updated.firstName(), updated.lastName(),
                    updated.phone(), updated.email(), updated.gender(), updated.birthday(), updated.address(),
                    updated.city(), updated.pincode(), updated.tags(), updated.notes(), updated.allergies(),
                    updated.marketingConsent(), updated.dataConsent(), updated.tier(), aggregate.points(), spend,
                    aggregate.visits(), aggregate.first(), aggregate.last(), updated.source(),
                    updated.preferredStaffId(), updated.preferredTime(), updated.userId(), updated.styleProfile(),
                    average, aggregate.noShows(), aggregate.cancellations(), updated.createdAt(),
                    LocalDateTime.now(), updated.archived());
            salon.customers.put(updated);
        });
    }

    private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** Service popularity, ratings and shelf sell-through, derived from the seeded book. */
    private static void counters(Salon salon) {
        Map<String, Integer> bookings = new LinkedHashMap<>();
        Map<String, List<Integer>> ratings = new LinkedHashMap<>();
        for (Appointment appointment : salon.appointments.all()) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                if (appointment.status() == AppointmentStatus.COMPLETED) {
                    bookings.merge(item.serviceId(), 1, Integer::sum);
                }
                if (appointment.rating() != null && appointment.rating() > 0) {
                    ratings.computeIfAbsent(item.serviceId(), k -> new ArrayList<>()).add(appointment.rating());
                }
            }
        }
        Map<String, Integer> sold = new LinkedHashMap<>();
        for (Invoice invoice : salon.invoices.all()) {
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                if (Invoice.Line.PRODUCT.equals(line.kind())) sold.merge(line.refId(), Math.max(1, line.qty()), Integer::sum);
            }
        }
        for (ServiceItem service : salon.services.all()) {
            int count = bookings.getOrDefault(service.id(), 0);
            List<Integer> scores = ratings.getOrDefault(service.id(), List.of());
            double average = scores.isEmpty() ? 4.5
                    : scores.stream().mapToInt(Integer::intValue).average().orElse(4.5);
            salon.services.put(new ServiceItem(service.id(), service.code(), service.name(), service.categoryId(),
                    service.tagline(), service.description(), service.editorial(), service.durationMinutes(),
                    service.price(), service.variants(), service.staffIds(), service.tags(), service.prepNotes(),
                    service.aftercareNotes(), service.imageKey(), service.active(), service.onlineBookable(),
                    service.featured(), count, Math.round(average * 10) / 10.0, service.createdAt(),
                    LocalDateTime.now().toString()));
        }
        for (Product product : salon.products.all()) {
            int count = sold.getOrDefault(product.id(), 0);
            if (count == 0) continue;
            salon.products.put(product.withSold(count));
        }
    }

    // ------------------------------------------------------------------ the rest of the book

    private static void giftCards(Salon salon, Random rnd) {
        LocalDate today = LocalDate.now();
        salon.giftCards.put(new GiftCard(Util.id("gc"), "SIL-482-119", "Kabir Sethi", "+91 98145 61222",
                "Simran (daughter)", "For your eighteenth \u2014 spend it on something bold.",
                new BigDecimal("10000.00"), new BigDecimal("6250.00"), GiftCard.ACTIVE, today.plusYears(2),
                LocalDateTime.now().minusDays(70), LocalDateTime.now().minusDays(20), "Front desk", null, 2));
        salon.giftCards.put(new GiftCard(Util.id("gc"), "SIL-771-405", "Anjali Rajput", "+91 98145 61225",
                "Wedding party", "Thank-you for the bridal team.", new BigDecimal("25000.00"),
                new BigDecimal("25000.00"), GiftCard.ACTIVE, today.plusYears(2),
                LocalDateTime.now().minusDays(15), null, "Meher Kapur", null, 0));
        salon.giftCards.put(new GiftCard(Util.id("gc"), "SIL-118-902", "Nikhil Sood", "+91 98145 61210",
                "Sahil Narang", "Happy birthday, brother.", new BigDecimal("5000.00"), BigDecimal.ZERO.setScale(2),
                GiftCard.EXHAUSTED, today.plusYears(1), LocalDateTime.now().minusDays(200),
                LocalDateTime.now().minusDays(40), "Front desk", null, 4));
        salon.giftCards.put(new GiftCard(Util.id("gc"), "SIL-964-330", "Kiran Bhatnagar", "+91 98145 61230",
                "The salon team", "Diwali gift for the chairs.", new BigDecimal("20000.00"),
                new BigDecimal("18500.00"), GiftCard.ACTIVE, today.plusDays(45),
                LocalDateTime.now().minusDays(320), LocalDateTime.now().minusDays(300), "Meher Kapur", null, 1));
    }

    private static void expenses(Salon salon, Random rnd, LocalDate today) {
        String[][] template = {
                {"Rent", "Ferozepur Road property", "Monthly rent, ground floor + mezzanine", "285000"},
                {"Salaries", "Payroll", "Seven chairs, front of house and housekeeping", "640000"},
                {"Product", "L'Or\u00e9al Professional", "Colour stock replenishment", "82000"},
                {"Product", "Wella India", "Toners and lighteners", "46500"},
                {"Utilities", "PSPCL", "Electricity, including the steam units", "38400"},
                {"Utilities", "Water and softener", "Softener salt and filter service", "7200"},
                {"Marketing", "Instagram & Meta", "Festive campaign spend", "34000"},
                {"Marketing", "Photographer", "Lookbook shoot, four looks", "28000"},
                {"Maintenance", "Chair service", "Hydraulics and upholstery on chairs two and five", "16500"},
                {"Maintenance", "Laundry", "Towels and capes, weekly collection", "9800"},
                {"Software", "Sillage build", "Hosting, domain and the receipt paper", "6000"},
                {"Insurance", "Public liability", "Annual premium, paid quarterly", "42000"},
        };
        for (int month = 3; month >= 0; month--) {
            LocalDate base = today.minusMonths(month).withDayOfMonth(Math.min(28, today.getDayOfMonth()));
            for (String[] row : template) {
                if (rnd.nextInt(100) < 12) continue;
                BigDecimal amount = new BigDecimal(row[3]).multiply(new BigDecimal("0.92"))
                        .add(BigDecimal.valueOf(rnd.nextInt(9000)));
                salon.expenses.put(new Expense(Util.id("exp"), base.minusDays(rnd.nextInt(20)), row[0], row[1],
                        row[2], Money.scale(amount), row[0].equals("Rent") ? "NEFT" : "UPI",
                        "Meher Kapur", LocalDateTime.now().minusMonths(month)));
            }
        }
    }

    private static void outbox(Salon salon, LocalDate today) {
        Customer first = People.CLIENTS.get(0);
        Customer second = People.CLIENTS.get(4);
        Appointment upcoming = salon.appointments.all().stream()
                .filter(a -> a.start() != null && a.start().isAfter(LocalDateTime.now())
                        && a.status() == AppointmentStatus.CONFIRMED && a.customerId() != null)
                .min((a, b) -> a.start().compareTo(b.start())).orElse(null);
        if (upcoming != null) {
            Customer customer = salon.customers.get(upcoming.customerId());
            if (customer != null) {
                salon.notifications.queueForAppointment("booking.confirmed", upcoming, customer,
                        Util.join(upcoming.serviceNames(), ", "), salon.staffService.name(upcoming.staffId()),
                        Notification.WHATSAPP, "appointment");
            }
        }
        salon.notifications.queue("loyalty.tier", Notification.EMAIL, first.email(), first,
                Map.of("tier", "Maison", "perk", "Priority weekend chairs"), "customer", first.id(), "Seeding");
        salon.notifications.queue("birthday", Notification.SMS, second.phone(), second,
                Map.of("perk", "a complimentary gloss"), "customer", second.id(), "Seeding");
        salon.notifications.queue("winback", Notification.SMS, People.CLIENTS.get(12).phone(),
                People.CLIENTS.get(12), Map.of("days", "96"), "customer", People.CLIENTS.get(12).id(), "Seeding");
    }

    private static void suggestionStates(Salon salon, LocalDate today) {
        salon.suggestionStates.put(new SuggestionState(Util.id("sgs"), "inventory:prd-cuticle-oil", SuggestionState.SAVED,
                "Order placed with CND Asia on the 14th", "prd-cuticle-oil", "inventory",
                LocalDateTime.now().minusDays(6), "Kiran Dhillon"));
        salon.suggestionStates.put(new SuggestionState(Util.id("sgs"), "pricing:svc-threading", SuggestionState.DISMISSED,
                "Kept at \u20B9700 deliberately \u2014 it is the door-opener", "svc-threading", "pricing",
                LocalDateTime.now().minusDays(11), "Meher Kapur"));
        salon.suggestionStates.put(new SuggestionState(Util.id("sgs"), "staffing:stf-simran", SuggestionState.DONE,
                "Extended Saturday hours for the wedding season", "stf-simran", "staffing",
                LocalDateTime.now().minusDays(2), "Meher Kapur"));
    }
}
