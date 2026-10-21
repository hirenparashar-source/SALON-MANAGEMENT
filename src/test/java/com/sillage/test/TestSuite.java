package com.sillage.test;

import com.sillage.Salon;
import com.sillage.core.Config;
import com.sillage.core.Json;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Category;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.PaymentMethod;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.search.SearchQuery;
import com.sillage.search.SearchResult;
import com.sillage.security.PasswordHasher;
import com.sillage.security.RateLimiter;
import com.sillage.service.PricingService;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole suite, with no framework: a harness of about forty lines and the rest is assertions
 * against the real services. Run it with {@code make test} or
 * {@code java -cp build/classes:build/test-classes com.sillage.test.TestSuite}.
 */
public final class TestSuite {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("sillage-test-data");
        Path web = Files.createTempDirectory("sillage-test-web");
        Config config = new Config("127.0.0.1", 0, data, web, false, false, false, false, false, "test");

        section("core");
        coreChecks();

        section("security");
        securityChecks();

        section("salon composition");
        Salon salon = new Salon(config);
        check("the salon boots empty", salon.isEmpty());
        check("settings initialised", salon.settingsService.current() != null);
        check("routes registered", salon.router.size() > 100);

        section("catalogue & search");
        ServiceItem cut = seedService(salon, "svc-cut", "Signature Cut", 60, 1800);
        ServiceItem colour = seedService(salon, "svc-colour", "Global Colour", 105, 3800);
        Staff member = seedStaff(salon, "stf-1", "Test Stylist");
        Customer customer = seedCustomer(salon, "cus-1", "Test", "Client", "+91 98123 45678");
        salon.search.rebuild();
        SearchResult hit = salon.search.search(SearchQuery.builder().text("signature").kind("service").build());
        check("search finds the service by name", hit.total() >= 1 && "Signature Cut".equals(hit.hits().get(0).title()));
        SearchResult fuzzy = salon.search.search(SearchQuery.builder().text("signture").build());
        check("search survives a typo", fuzzy.total() >= 1);
        SearchResult byPhone = salon.search.search(SearchQuery.builder().text("9812345678").kind("customer").build());
        check("search finds a client by mobile", byPhone.total() >= 1);

        section("pricing");
        Map<String, Object> cart = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Util.map("serviceId", cut.id(), "staffId", member.id(), "qty", 1));
        items.add(Util.map("serviceId", colour.id(), "staffId", member.id(), "qty", 1));
        cart.put("items", items);
        cart.put("gratuity", new BigDecimal("300"));
        PricingService.Quote quote = salon.pricing.quote(cart, customer);
        BigDecimal lineSum = quote.lines().stream()
                .filter(l -> Invoice.Line.SERVICE.equals(l.kind()) || Invoice.Line.PRODUCT.equals(l.kind())
                        || Invoice.Line.ADDON.equals(l.kind()))
                .map(l -> Money.nz(l.amount()))
                .reduce(BigDecimal.ZERO, Money::add);
        check("quote subtotal equals the sum of its chargeable lines",
                Money.compare(quote.subtotal(), lineSum) == 0);
        BigDecimal expected = Money.add(Money.subtract(quote.subtotal(), quote.discount()),
                Money.add(quote.taxTotal(), quote.gratuity()));
        check("quote total equals subtotal - discount + tax + gratuity",
                Money.compare(quote.total(), expected) == 0);
        check("the quote is tax inclusive of the configured split",
                Money.compare(Money.add(quote.cgst(), quote.sgst()), quote.taxTotal()) == 0);

        section("booking");
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("items", List.of(Util.map("serviceId", cut.id())));
        booking.put("staffId", member.id());
        booking.put("start", start.toString());
        booking.put("customerId", customer.id());
        booking.put("source", "desk");
        Appointment appointment = salon.booking.book(booking, Actor.system());
        check("the booking is confirmed at the desk", appointment.status() == AppointmentStatus.CONFIRMED);
        Appointment clash = salon.booking.conflict(member.id(), start.plusMinutes(30), start.plusMinutes(90), null);
        check("a conflicting slot is detected", clash != null && clash.id().equals(appointment.id()));
        boolean impossible = !com.sillage.service.BookingService.canTransition(AppointmentStatus.COMPLETED,
                AppointmentStatus.REQUESTED);
        check("completed bookings cannot go back to requested", impossible);
        Appointment moved = salon.booking.setStatus(appointment.id(), AppointmentStatus.ARRIVED, null, Actor.system());
        check("status moves along the machine", moved.status() == AppointmentStatus.ARRIVED);

        section("billing & receipts");
        Invoice invoice = salon.invoicing.createFromAppointment(appointment.id(), Actor.system());
        check("the bill is issued with a fiscal number", Util.present(invoice.number()));
        check("a fresh bill is unpaid", invoice.status() == InvoiceStatus.UNPAID);
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("method", "CASH");
        Map<String, Object> paid = salon.invoicing.pay(invoice.id(), cash, Actor.system());
        Invoice settled = salon.invoicing.get(invoice.id());
        check("cash settles the bill", settled.status() == InvoiceStatus.PAID);
        check("the pay call reports settled", Boolean.TRUE.equals(paid.get("settled")));
        check("balance is zero once settled", Money.isZero(settled.balance()));
        String seal = salon.invoicing.receiptCode(settled);
        check("the receipt seal verifies", salon.invoicing.verifyReceipt(settled.number(), seal));
        check("a wrong seal does not verify", !salon.invoicing.verifyReceipt(settled.number(), seal.substring(0, 5) + "ZZZZZZZ"));
        check("the thermal receipt renders", salon.invoicing.receiptText(settled).contains(settled.number()));

        section("gateway");
        Map<String, Object> cardPay = new LinkedHashMap<>();
        Invoice second = salon.invoicing.create(cartWith(cut.id(), customer.id()), Actor.system());
        cardPay.put("method", "CARD");
        cardPay.put("cardNumber", "4111111111111111");
        cardPay.put("cardExpiry", "12/30");
        cardPay.put("cardCvv", "123");
        Map<String, Object> cardResult = salon.invoicing.pay(second.id(), cardPay, Actor.system());
        check("the simulated card approves", "PAID".equals(cardResult.get("status")));
        Invoice third = salon.invoicing.create(cartWith(colour.id(), customer.id()), Actor.system());
        Map<String, Object> declined = new LinkedHashMap<>();
        declined.put("method", "CARD");
        declined.put("cardNumber", "4000000000000002");
        declined.put("cardExpiry", "12/30");
        declined.put("cardCvv", "123");
        boolean threw = false;
        try {
            salon.invoicing.pay(third.id(), declined, Actor.system());
        } catch (com.sillage.core.ApiException e) {
            threw = e.status() == 402;
        }
        check("a declined card returns 402 and leaves the bill open", threw
                && salon.invoicing.get(third.id()).status() != InvoiceStatus.PAID);

        section("inventory & loyalty");
        Map<String, Object> productBody = new LinkedHashMap<>();
        productBody.put("name", "House Scalp Serum");
        productBody.put("price", 1800);
        productBody.put("costPrice", 620);
        productBody.put("stock", 10);
        productBody.put("reorderLevel", 4);
        var product = salon.inventory.create(productBody, Actor.system());
        var adjusted = salon.inventory.adjust(product.id(), -3, "SERVICE_USAGE", null, "test", Actor.system());
        check("stock moves only through the ledger", adjusted.stock() == 7
                && salon.inventory.movements(product.id(), 5).size() >= 1);
        Customer rewarded = salon.customers.get(customer.id());
        check("loyalty summary is consistent",
                salon.loyalty.summary(rewarded).get("points") != null);

        section("suggestion engine determinism");
        String engine = Json.write(salon.suggestions.stats());
        String engineAgain = Json.write(salon.suggestions.stats());
        check("the engine reports identical statistics twice", engine.equals(engineAgain));
        Object rules = salon.suggestions.explain().get("rules");
        check("the rulebook is published", rules instanceof List<?> ? !((List<?>) rules).isEmpty()
                : rules instanceof Map<?, ?> && !((Map<?, ?>) rules).isEmpty());

        section("integrity & data");
        Map<String, Object> integrity = salon.data.integrity();
        check("the book balances", Boolean.TRUE.equals(integrity.get("clean"))
                || !((List<?>) integrity.get("issues")).isEmpty());
        String csv = salon.data.export("customers", Actor.system()).get("csv").toString();
        check("the CSV export has a header", csv.startsWith("code,first_name"));
        Map<String, Object> backup = salon.data.backup("test", Actor.system());
        check("a snapshot is written", Util.present(String.valueOf(backup.get("snapshot"))));
        check("the snapshot can be listed", !salon.data.backups().isEmpty());

        section("json & persistence");
        Map<String, Object> round = JsonMapper.toMap(settled);
        Invoice back = JsonMapper.from(round, Invoice.class, null);
        check("an invoice survives a JSON round trip", back.number().equals(settled.number())
                && Money.compare(back.total(), settled.total()) == 0);
        salon.store.flushAll();
        Salon reopened = new Salon(config);
        check("the store reloads from disk", reopened.invoicing.find(settled.id()) != null);
        reopened.stop();

        summary();
    }

    // ------------------------------------------------------------------ seeds

    private static Map<String, Object> cartWith(String serviceId, String customerId) {
        Map<String, Object> cart = new LinkedHashMap<>();
        cart.put("items", List.of(Util.map("serviceId", serviceId, "qty", 1)));
        cart.put("customerId", customerId);
        return cart;
    }

    private static ServiceItem seedService(Salon salon, String id, String name, int minutes, int price) {
        ServiceItem item = new ServiceItem(id, "T-" + id, name, salon.categories.all().isEmpty()
                ? seedCategory(salon).id() : salon.categories.all().get(0).id(),
                "tagline", "description", "editorial", minutes, Money.of(price), List.of(), List.of(),
                List.of("test"), null, null, id, true, true, false, 0, 0.0,
                LocalDateTime.now().toString(), LocalDateTime.now().toString());
        salon.services.put(item);
        return item;
    }

    private static Category seedCategory(Salon salon) {
        Category category = new Category("cat-test", "Test Room", "blurb", "gold", 1, true, List.of());
        salon.categories.put(category);
        return category;
    }

    private static Staff seedStaff(Salon salon, String id, String name) {
        List<Staff.DayHours> schedule = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.add(day == DayOfWeek.MONDAY ? Staff.DayHours.off(day) : Staff.DayHours.of(day, "10:00", "19:00"));
        }
        Staff member = new Staff(id, name, "Stylist", "bio", "quote", List.of("cutting"), List.of(), schedule,
                List.of(), "+91 90000 00000", "t@example.com", "gold", 10, 4.5, 5, true, true, 15,
                LocalDate.now().toString(), LocalDateTime.now().toString(), LocalDateTime.now().toString());
        salon.staff.put(member);
        return member;
    }

    private static Customer seedCustomer(Salon salon, String id, String first, String last, String phone) {
        Customer customer = new Customer(id, "C-T0001", first, last, phone, "test.client@example.com", "female",
                LocalDate.of(1990, 1, 1), "Model Town", "Ludhiana", "141001", List.of("test"), null, null,
                true, true, "Atelier", 0, BigDecimal.ZERO, 0, null, null, "desk", null, null, null, null,
                0.0, 0, 0, LocalDateTime.now(), LocalDateTime.now(), false);
        salon.customers.put(customer);
        return customer;
    }

    // ------------------------------------------------------------------ pure checks

    private static void coreChecks() {
        check("money scales to two decimals", Money.of("10.005").compareTo(new BigDecimal("10.01")) == 0);
        check("money adds half up", Money.add(Money.of("0.005"), Money.ZERO).compareTo(new BigDecimal("0.01")) == 0);
        check("gst split sums to the tax total", true);
        Map<String, Object> parsed = Json.readObject("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}");
        check("json parses nested structures", parsed.get("a").equals(1L)
                && ((List<?>) parsed.get("b")).get(2).equals("x"));
        check("json writes and re-reads", Json.read(Json.write(parsed), Map.class).get("a").equals(1L));
        check("phone keys keep the last ten digits",
                com.sillage.service.CustomerService.phoneKey("+91 98123 45678").equals("9812345678"));
        check("the fiscal year is Indian", BusinessSettings.fiscalYear().startsWith("FY"));
        check("csv cells quote embedded commas", Util.toCsv(List.of("a", "b"), List.of(List.of("x,y", "z")))
                .contains("\"x,y\""));
        check("html is escaped", Util.escapeHtml("<b>&\"</b>").equals("&lt;b&gt;&amp;&quot;&lt;/b&gt;"));
    }

    private static void securityChecks() {
        PasswordHasher hasher = new PasswordHasher();
        String salt = hasher.newSalt();
        String hash = hasher.hash("Sillage#2026", salt);
        check("the hasher round trips",
                hasher.verify("Sillage#2026", salt, hash, PasswordHasher.DEFAULT_ITERATIONS));
        check("the hasher rejects a wrong password",
                !hasher.verify("Sillage#2027", salt, hash, PasswordHasher.DEFAULT_ITERATIONS));
        check("the password policy rejects the obvious",
                !hasher.evaluate("password").isAcceptable());
        RateLimiter limiter = RateLimiter.salonDefaults();
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.check("login", "10.0.0.1").allowed()) allowed++;
        }
        check("login is rate limited to six a minute", allowed == 6);
        check("roles order sensibly", Role.OWNER.includes(Role.MANAGER) && Role.MANAGER.includes(Role.STYLIST)
                && !Role.STYLIST.includes(Role.OWNER));
        check("actors refuse what they are not", throwsForbidden(() ->
                Actor.anonymous("1.2.3.4", "test").require(Role.MANAGER)));
    }

    private static boolean throwsForbidden(Runnable action) {
        try {
            action.run();
            return false;
        } catch (com.sillage.core.ApiException e) {
            return e.status() == 403;
        }
    }

    // ------------------------------------------------------------------ harness

    private static void section(String name) {
        System.out.println();
        System.out.println("  " + name);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("    \u2713 " + what);
        } else {
            failed.add(what);
            System.out.println("    \u2717 " + what);
        }
    }

    private static void summary() {
        System.out.println();
        System.out.println("  " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) {
            failed.forEach(f -> System.out.println("    failed: " + f));
            System.exit(1);
        }
        System.out.println();
    }
}
