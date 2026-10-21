package com.sillage;

import com.sillage.api.Api;
import com.sillage.core.Config;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Util;
import com.sillage.data.DataStore;
import com.sillage.data.Repo;
import com.sillage.model.Appointment;
import com.sillage.model.AuditEvent;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Category;
import com.sillage.model.Credential;
import com.sillage.model.Customer;
import com.sillage.model.Expense;
import com.sillage.model.GiftCard;
import com.sillage.model.Invoice;
import com.sillage.model.Look;
import com.sillage.model.Notification;
import com.sillage.model.Payment;
import com.sillage.model.PaymentIntent;
import com.sillage.model.Product;
import com.sillage.model.PromoCode;
import com.sillage.model.ServiceItem;
import com.sillage.model.SessionRecord;
import com.sillage.model.Staff;
import com.sillage.model.StockMovement;
import com.sillage.model.SuggestionState;
import com.sillage.model.Testimonial;
import com.sillage.model.UserAccount;
import com.sillage.search.SearchIndex;
import com.sillage.security.AuditLog;
import com.sillage.security.RateLimiter;
import com.sillage.security.SessionManager;
import com.sillage.seed.SeedData;
import com.sillage.service.AnalyticsService;
import com.sillage.service.AuditService;
import com.sillage.service.AuthService;
import com.sillage.service.BookingService;
import com.sillage.service.CatalogueService;
import com.sillage.service.CustomerService;
import com.sillage.service.DataService;
import com.sillage.service.InventoryService;
import com.sillage.service.InvoiceService;
import com.sillage.service.LoyaltyService;
import com.sillage.service.NotificationService;
import com.sillage.service.PaymentGateway;
import com.sillage.service.PricingService;
import com.sillage.service.SearchService;
import com.sillage.service.SettingsService;
import com.sillage.service.StaffService;
import com.sillage.service.SuggestionEngine;
import com.sillage.web.Router;
import com.sillage.web.SalonServer;
import com.sillage.web.StaticFiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The composition root. One object owns the store, every repository, every service and the HTTP
 * server, wired by hand \u2014 no framework, no reflection scanning, no annotations to interpret.
 *
 * <p>Everything is built once at boot; the fields are public and final so the API layer can read
 * them without a wall of getters.
 */
public final class Salon {

    public final Config config;
    public final DataStore store;

    // -------------------------------------------------------------- repositories
    public final Repo<BusinessSettings> settings;
    public final Repo<ServiceItem> services;
    public final Repo<Category> categories;
    public final Repo<Look> looks;
    public final Repo<Testimonial> testimonials;
    public final Repo<Staff> staff;
    public final Repo<Customer> customers;
    public final Repo<Appointment> appointments;
    public final Repo<Invoice> invoices;
    public final Repo<Payment> payments;
    public final Repo<PaymentIntent> intents;
    public final Repo<Product> products;
    public final Repo<StockMovement> movements;
    public final Repo<GiftCard> giftCards;
    public final Repo<PromoCode> promos;
    public final Repo<Notification> outbox;
    public final Repo<Expense> expenses;
    public final Repo<UserAccount> users;
    public final Repo<Credential> credentials;
    public final Repo<SessionRecord> sessions;
    public final Repo<AuditEvent> auditEvents;
    public final Repo<SuggestionState> suggestionStates;

    // -------------------------------------------------------------- security
    public final AuditLog auditLog;
    public final SessionManager sessionManager;
    public final RateLimiter rateLimiter;

    // -------------------------------------------------------------- services
    public final AuditService audit;
    public final SettingsService settingsService;
    public final SearchService search;
    public final NotificationService notifications;
    public final LoyaltyService loyalty;
    public final CatalogueService catalogue;
    public final CustomerService customerService;
    public final StaffService staffService;
    public final BookingService booking;
    public final InventoryService inventory;
    public final PricingService pricing;
    public final PaymentGateway gateway;
    public final InvoiceService invoicing;
    public final SuggestionEngine suggestions;
    public final AnalyticsService analytics;
    public final AuthService auth;
    public final DataService data;

    // -------------------------------------------------------------- web
    public final SearchIndex index;
    public final Router router;
    public final StaticFiles staticFiles;
    public final SalonServer server;

    private final long bootedAt = System.nanoTime();

    public Salon(Config config) {
        this.config = config;
        this.store = new DataStore(config.dataDir());

        this.settings = repo("settings", BusinessSettings.class, BusinessSettings::id);
        this.services = repo("services", ServiceItem.class, ServiceItem::id).index("code", ServiceItem::code);
        this.categories = repo("categories", Category.class, Category::id).index("name", c -> lower(c.name()));
        this.looks = repo("looks", Look.class, Look::id);
        this.testimonials = repo("testimonials", Testimonial.class, Testimonial::id);
        this.staff = repo("staff", Staff.class, Staff::id).index("email", s -> lower(s.email()));
        this.customers = repo("customers", Customer.class, Customer::id)
                .index("code", Customer::code)
                .index("phone", c -> com.sillage.service.CustomerService.phoneKey(c.phone()))
                .index("email", c -> lower(c.email()))
                .index("userId", Customer::userId);
        this.appointments = repo("appointments", Appointment.class, Appointment::id)
                .index("code", Appointment::code)
                .index("customerId", Appointment::customerId)
                .index("staffId", Appointment::staffId);
        this.invoices = repo("invoices", Invoice.class, Invoice::id)
                .index("number", Invoice::number)
                .index("customerId", Invoice::customerId);
        this.payments = repo("payments", Payment.class, Payment::id);
        this.intents = repo("payment_intents", PaymentIntent.class, PaymentIntent::id)
                .index("invoiceId", PaymentIntent::invoiceId)
                .index("idempotencyKey", PaymentIntent::idempotencyKey);
        this.products = repo("products", Product.class, Product::id).index("sku", Product::sku);
        this.movements = repo("stock_movements", StockMovement.class, StockMovement::id)
                .index("productId", StockMovement::productId);
        this.giftCards = repo("gift_cards", GiftCard.class, GiftCard::id).index("code", g -> upper(g.code()));
        this.promos = repo("promo_codes", PromoCode.class, PromoCode::id).index("code", p -> upper(p.code()));
        this.outbox = repo("notifications", Notification.class, Notification::id)
                .index("customerId", Notification::customerId)
                .index("refId", Notification::refId);
        this.expenses = repo("expenses", Expense.class, Expense::id);
        this.users = repo("users", UserAccount.class, UserAccount::id)
                .index("username", u -> lower(u.username()))
                .index("email", u -> lower(u.email()));
        this.credentials = repo("credentials", Credential.class, Credential::id)
                .index("userId", Credential::userId);
        this.sessions = repo("sessions", SessionRecord.class, SessionRecord::id)
                .index("userId", SessionRecord::userId);
        this.auditEvents = repo("audit_events", AuditEvent.class, AuditEvent::id)
                .index("entityId", AuditEvent::entityId);
        this.suggestionStates = repo("suggestion_states", SuggestionState.class, SuggestionState::id)
                .index("key", SuggestionState::key);

        this.store.load();
        if (config.resetData()) {
            Log.warn("--reset: clearing every collection before boot");
            this.store.wipe();
        }

        this.auditLog = new AuditLog(auditEvents);
        this.sessionManager = new SessionManager(sessions);
        this.rateLimiter = RateLimiter.salonDefaults();

        this.audit = new AuditService(auditLog);
        this.settingsService = new SettingsService(settings);
        this.index = SearchIndex.salonDefault();
        this.search = new SearchService(index, customers, services, staff, invoices, products, appointments,
                looks, giftCards);
        this.notifications = new NotificationService(outbox, settingsService);
        this.loyalty = new LoyaltyService(customers, settingsService, search);
        this.catalogue = new CatalogueService(services, categories, looks, testimonials, staff, search);
        this.customerService = new CustomerService(customers, appointments, invoices, staff, outbox, search, audit);
        this.staffService = new StaffService(staff, appointments, invoices, services, search, audit);
        this.booking = new BookingService(appointments, services, staff, customers, settingsService,
                customerService, search, audit, notifications);
        this.inventory = new InventoryService(products, movements, services, search, audit);
        this.pricing = new PricingService(settingsService, loyalty, repoHolder());
        this.gateway = new PaymentGateway(intents, settingsService);
        this.invoicing = new InvoiceService(invoices, appointments, customers, giftCards, intents, staff,
                settingsService, pricing, gateway, loyalty, inventory, booking, search, audit, notifications,
                config.dataDir());
        this.suggestions = new SuggestionEngine(appointments, customers, services, staff, products, invoices,
                looks, suggestionStates, settingsService);
        this.analytics = new AnalyticsService(invoices, appointments, customers, services, staff, products, expenses);
        this.auth = new AuthService(users, credentials, sessions, customers, staff, sessionManager, audit, search,
                settingsService, customerService, config);
        this.data = new DataService(store, customers, appointments, invoices, products, staff, services, movements,
                giftCards, outbox, expenses, customerService, inventory, audit, search, gateway, notifications, auth);

        this.router = Api.routes(this);
        this.staticFiles = new StaticFiles(config.webDir());
        this.server = new SalonServer(config, router, staticFiles, sessionManager, users, auditLog, rateLimiter,
                pageRoutes());

        if (config.seedIfEmpty() && isEmpty()) {
            SeedData.populate(this);
        }
        Map<String, Object> indexReport = search.rebuild();
        int indexed = JsonMapper.asInt(indexReport.get("documents"), 0);
        audit.system("boot", "Salon ready \u00b7 " + services.size() + " services, " + customers.size()
                + " clients, " + staff.size() + " stylists, " + indexed + " documents indexed");
        Log.info("composition root built in %d ms (%d collections, %d routes, %d indexed documents)",
                (System.nanoTime() - bootedAt) / 1_000_000, store.collectionNames().size(), router.size(), indexed);
    }

    private <T> Repo<T> repo(String name, Class<T> type, java.util.function.Function<T, String> idOf) {
        return store.repo(name, type, idOf);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** The read-only view {@link PricingService} needs to price a cart. */
    private PricingService.RepoHolder repoHolder() {
        return new PricingService.RepoHolder() {
            @Override public ServiceItem service(String id) { return id == null ? null : services.get(id); }
            @Override public Product product(String id) { return id == null ? null : products.get(id); }
            @Override public Staff staff(String id) { return id == null ? null : staff.get(id); }
            @Override public PromoCode promo(String code) {
                if (Util.blank(code)) return null;
                String key = code.trim().toUpperCase(Locale.ROOT);
                return promos.firstWhere(p -> key.equals(Util.nz(p.code()).toUpperCase(Locale.ROOT))).orElse(null);
            }
        };
    }

    /** Pretty URLs the server answers with a page instead of the API. */
    private static Map<String, String> pageRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/", "index.html");
        routes.put("/home", "index.html");
        routes.put("/menu", "index.html");
        routes.put("/book", "book.html");
        routes.put("/booking", "book.html");
        routes.put("/studio", "studio.html");
        routes.put("/console", "studio.html");
        routes.put("/admin", "studio.html");
        routes.put("/login", "studio.html");
        routes.put("/privacy", "index.html");
        return routes;
    }

    public boolean isEmpty() {
        return services.isEmpty() && staff.isEmpty() && customers.isEmpty();
    }

    public void start() throws IOException {
        server.start();
        Log.info("listening on http://%s:%d  \u00b7  data %s  \u00b7  web %s",
                config.host().equals("0.0.0.0") ? "localhost" : config.host(), server.port(),
                config.dataDir(), config.webDir());
    }

    public void stop() {
        try {
            store.close();
        } catch (RuntimeException e) {
            Log.warn("store close: %s", e.getMessage());
        }
        server.stop();
    }

    public long uptimeMillis() {
        return (System.nanoTime() - bootedAt) / 1_000_000;
    }

    /** The single place that describes whether the salon is healthy. */
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("app", "SILLAGE \u00b7 salon management");
        out.put("version", SillageApp.VERSION);
        out.put("instance", config.instanceId());
        out.put("uptimeMillis", uptimeMillis());
        out.put("java", System.getProperty("java.version"));
        out.put("threads", Thread.activeCount());
        Runtime runtime = Runtime.getRuntime();
        out.put("memoryUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        out.put("memoryMaxMb", runtime.maxMemory() / (1024 * 1024));
        out.put("requests", server.requestCount());
        out.put("errors", server.errorCount());
        out.put("routes", router.size());
        out.put("data", store.stats());
        out.put("search", search.stats());
        out.put("open", settingsService.openNowStatus(settingsService.current()));
        return out;
    }
}
