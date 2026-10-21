package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.AtomicFiles;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.AuditEvent;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.GiftCard;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.Notification;
import com.sillage.model.Payment;
import com.sillage.model.PaymentIntent;
import com.sillage.model.PaymentMethod;
import com.sillage.model.PaymentStatus;
import com.sillage.model.Product;
import com.sillage.model.Role;
import com.sillage.model.Staff;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Billing: quotes become invoices, invoices take tender, tender can be refunded, and every
 * settled document can be printed, exported and independently verified.
 */
public final class InvoiceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private final Repo<Invoice> invoices;
    private final Repo<Appointment> appointments;
    private final Repo<Customer> customers;
    private final Repo<GiftCard> giftCards;
    private final Repo<PaymentIntent> intents;
    private final Repo<Staff> staffRepo;
    private final SettingsService settings;
    private final PricingService pricing;
    private final PaymentGateway gateway;
    private final LoyaltyService loyalty;
    private final InventoryService inventory;
    private final BookingService booking;
    private final SearchService search;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Path receiptKeyFile;
    private volatile String receiptKey;

    public InvoiceService(Repo<Invoice> invoices, Repo<Appointment> appointments, Repo<Customer> customers,
                          Repo<GiftCard> giftCards, Repo<PaymentIntent> intents, Repo<Staff> staffRepo,
                          SettingsService settings, PricingService pricing, PaymentGateway gateway, LoyaltyService loyalty,
                          InventoryService inventory, BookingService booking, SearchService search,
                          AuditService audit, NotificationService notifications, Path dataDir) {
        this.invoices = invoices;
        this.appointments = appointments;
        this.customers = customers;
        this.giftCards = giftCards;
        this.intents = intents;
        this.staffRepo = staffRepo;
        this.settings = settings;
        this.pricing = pricing;
        this.gateway = gateway;
        this.loyalty = loyalty;
        this.inventory = inventory;
        this.booking = booking;
        this.search = search;
        this.audit = audit;
        this.notifications = notifications;
        this.receiptKeyFile = dataDir.resolve("receipt.key");
    }

    public Repo<Invoice> repo() { return invoices; }

    // ------------------------------------------------------------------ quoting & creation

    public Map<String, Object> quote(Map<String, Object> cart, Actor actor) {
        Customer customer = resolveCustomer(cart, actor);
        PricingService.Quote quote = pricing.quote(cart, customer);
        Map<String, Object> out = pricing.view(quote);
        out.put("number", settings.previewInvoiceNumber());
        out.put("customer", customer == null ? null : Util.map("id", customer.id(), "name", customer.fullName(),
                "tier", customer.tier(), "points", customer.points()));
        out.put("loyaltySummary", loyalty.summary(customer));
        return out;
    }

    private Customer resolveCustomer(Map<String, Object> cart, Actor actor) {
        String customerId = JsonMapper.asText(cart.get("customerId"));
        if (Util.present(customerId)) return customers.get(customerId);
        if (actor != null && Util.present(actor.customerId())) return customers.get(actor.customerId());
        String phone = JsonMapper.asText(cart.get("phone"));
        if (Util.present(phone)) return lookupByPhone(phone);
        return null;
    }

    private Customer lookupByPhone(String phone) {
        String key = com.sillage.service.CustomerService.phoneKey(phone);
        if (key == null) return null;
        for (Customer candidate : customers.byIndex("phone", key)) if (candidate != null) return candidate;
        return null;
    }

    /** Creates an invoice from a cart (desk POS or online checkout). */
    public Invoice create(Map<String, Object> cart, Actor actor) {
        BusinessSettings config = settings.current();
        Customer customer = resolveCustomer(cart, actor);
        if (customer == null && !config.allowGuestCheckout()) {
            throw ApiException.badRequest("A customer record is required for billing");
        }
        PricingService.Quote quote = pricing.quote(cart, customer);
        List<String> appointmentIds = JsonMapper.asStringList(cart.get("appointmentIds"));
        String staffId = JsonMapper.asText(cart.get("staffId"));
        if (Util.blank(staffId)) {
            for (PricingService.QuoteLine line : quote.lines()) {
                if (Util.present(line.staffId())) { staffId = line.staffId(); break; }
            }
        }

        List<Invoice.Line> lines = quote.lines().stream().map(PricingService.QuoteLine::toInvoiceLine).toList();
        String number = settings.nextInvoiceNumber(config);
        Invoice invoice = new Invoice(
                Util.id("inv"), number,
                customer == null ? null : customer.id(),
                customer == null ? com.sillage.security.Validator.sanitise(JsonMapper.asText(cart.get("customerName"))) : customer.fullName(),
                customer == null ? JsonMapper.asText(cart.get("phone")) : customer.phone(),
                customer == null ? JsonMapper.asText(cart.get("email")) : customer.email(),
                appointmentIds, lines,
                quote.subtotal(), quote.discount(), quote.promoCode(), quote.loyaltyRedemption(), quote.pointsRedeemed(),
                quote.gratuity(), quote.taxable(), quote.cgst(), quote.sgst(), quote.taxTotal(), quote.roundOff(),
                quote.total(), BigDecimal.ZERO, quote.total(),
                quote.total().signum() == 0 ? InvoiceStatus.PAID : InvoiceStatus.UNPAID,
                List.of(), quote.pointsRedeemed() > 0 ? 0 : loyalty.earn(quote.total(), customer, config),
                com.sillage.security.Validator.sanitise(JsonMapper.asText(cart.get("notes"))),
                JsonMapper.asText(cart.get("footerNote")),
                LocalDateTime.now(), quote.total().signum() == 0 ? LocalDateTime.now() : null,
                actor == null ? "system" : actor.label(), staffId, BusinessSettings.fiscalYear(),
                config.gstin(), Util.nz(JsonMapper.asText(cart.get("source")), actor != null && actor.isStaff() ? "desk" : "online"),
                null, false, 0);

        String code = receiptCode(invoice);
        invoice = withReceiptCode(invoice, code);
        invoices.put(invoice);
        search.index(invoice);

        if (quote.pointsRedeemed() > 0 && customer != null) {
            customers.put(customer.withPoints(Math.max(0, customer.points() - quote.pointsRedeemed())));
            search.index(customers.get(customer.id()));
        }
        if (Money.isZero(invoice.total())) {
            afterSettled(invoice, actor);
        }
        audit.record("invoice.create", "invoice", invoice.id(), "Raised " + number + " for " + Money.inr(invoice.total()),
                actor, AuditEvent.INFO, Util.map("customer", invoice.customerName(), "lines", lines.size()));
        return invoice;
    }

    /** Builds a cart from a completed appointment so the desk can bill in one tap. */
    public Map<String, Object> cartFromAppointment(String appointmentId) {
        Appointment appointment = booking.get(appointmentId);
        Map<String, Object> cart = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Appointment.Item item : Util.nullSafe(appointment.items())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("serviceId", item.serviceId());
            if (Util.present(item.variantId())) entry.put("variantId", item.variantId());
            entry.put("staffId", appointment.staffId());
            items.add(entry);
        }
        cart.put("items", items);
        cart.put("staffId", appointment.staffId());
        cart.put("customerId", appointment.customerId());
        cart.put("appointmentIds", List.of(appointment.id()));
        cart.put("customerName", appointment.guestName());
        cart.put("phone", appointment.guestPhone());
        cart.put("email", appointment.guestEmail());
        return cart;
    }

    public Invoice createFromAppointment(String appointmentId, Actor actor) {
        Appointment appointment = booking.get(appointmentId);
        if (Util.present(appointment.invoiceId()) && invoices.get(appointment.invoiceId()) != null) {
            throw ApiException.conflict("That booking is already billed (" + invoices.get(appointment.invoiceId()).number() + ")");
        }
        Invoice invoice = create(cartFromAppointment(appointmentId), actor);
        booking.attachInvoice(appointmentId, invoice.id());
        return invoice;
    }

    // ------------------------------------------------------------------ settlement

    /**
     * Takes tender against an invoice. Online methods go through the gateway (and may come back
     * asking for a 3-D Secure code); cash, gift card and pay-at-salon settle immediately.
     */
    public Map<String, Object> pay(String invoiceId, Map<String, Object> body, Actor actor) {
        Invoice invoice = get(invoiceId);
        if (invoice.status() == InvoiceStatus.VOID) throw ApiException.conflict("That invoice is void");
        BigDecimal balance = Money.nz(invoice.balance());
        if (balance.signum() <= 0) throw ApiException.conflict("This invoice is already settled");

        PaymentMethod method = PaymentMethod.parse(JsonMapper.asText(body.get("method")));
        BigDecimal requested = body.containsKey("amount") ? Money.of(body.get("amount")) : balance;
        if (requested.signum() <= 0) throw ApiException.badRequest("Enter an amount greater than zero");
        if (requested.compareTo(balance) > 0) {
            if (!Money.isZero(Money.subtract(requested, balance))) {
                throw ApiException.conflict("That is " + Money.inr(Money.subtract(requested, balance)) + " more than the balance");
            }
        }
        requested = Money.min(requested, balance);
        if (method == PaymentMethod.LOYALTY) {
            throw ApiException.badRequest("Loyalty points are redeemed while building the bill, not as tender");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Payment payment;

        if (method.needsGateway()) {
            String intentId = JsonMapper.asText(body.get("intentId"));
            PaymentIntent intent = Util.present(intentId) ? gateway.intent(intentId)
                    : gateway.createIntent(requested, method, invoice.id(), invoice.number(),
                    firstAppointment(invoice), invoice.customerId(),
                    JsonMapper.asText(body.get("idempotencyKey")), actor == null ? "desk" : actor.ip());
            if (Money.compare(intent.amount(), requested) != 0) {
                intent = gateway.createIntent(requested, method, invoice.id(), invoice.number(),
                        firstAppointment(invoice), invoice.customerId(), null, actor == null ? "desk" : actor.ip());
            }
            PaymentGateway.Tender tender = new PaymentGateway.Tender(method, requested,
                    JsonMapper.asText(body.get("cardNumber")), JsonMapper.asText(body.get("cardExpiry")),
                    JsonMapper.asText(body.get("cardCvv")), JsonMapper.asText(body.get("cardHolder")),
                    JsonMapper.asText(body.get("upiVpa")), JsonMapper.asText(body.get("bank")),
                    JsonMapper.asText(body.get("wallet")), JsonMapper.asText(body.get("giftCardCode")),
                    JsonMapper.asText(body.get("idempotencyKey")), actor == null ? null : actor.ip(),
                    velocity(actor));
            PaymentGateway.Authorisation authorisation = gateway.confirm(intent, tender,
                    Util.present(intent.clientSecret()) ? intent.clientSecret() : JsonMapper.asText(body.get("clientSecret")),
                    JsonMapper.asText(body.get("otp")));

            out.put("intentId", intent.id());
            out.put("riskBand", PaymentGateway.riskBand(authorisation.riskScore()));

            if (!authorisation.approved()) {
                if (authorisation.threeDsRequired()) {
                    out.put("status", "AUTHENTICATION_REQUIRED");
                    out.put("challengeId", authorisation.challengeId());
                    out.put("message", authorisation.failureReason());
                    out.put("maskedDestination", authorisation.details().get("maskedDestination"));
                    if (gateway.testMode()) out.put("testOtp", authorisation.details().get("testOtp"));
                    out.put("clientSecret", intent.clientSecret());
                    out.put("invoice", view(invoice));
                    return out;
                }
                Payment failed = gateway.toPayment(authorisation, tender, invoice.number(), actorName(actor));
                failed = new Payment(failed.id(), failed.method(), requested, PaymentStatus.FAILED, failed.gatewayRef(),
                        failed.authCode(), failed.cardBrand(), failed.cardLast4(), failed.cardExpiry(), failed.cardToken(),
                        failed.upiVpa(), failed.bank(), failed.wallet(), failed.giftCardCode(), failed.idempotencyKey(),
                        failed.threeDsVerified(), failed.failureCode(), failed.failureReason(), failed.createdAt(),
                        null, BigDecimal.ZERO, List.of(), failed.takenBy(), null, failed.riskScore(), invoice.number());
                List<Payment> history = new ArrayList<>(Util.nullSafe(invoice.payments()));
                history.add(failed);
                invoices.put(withPayments(invoice, history, invoice.paid(), invoice.balance(), invoice.status()));
                audit.record("payment.failed", "invoice", invoice.id(),
                        method.label() + " of " + Money.inr(requested) + " failed: " + Util.nz(failed.failureCode()),
                        actor, AuditEvent.WARN, Util.map("code", failed.failureCode(), "risk", failed.riskScore()));
                throw new ApiException(402, "PAYMENT_FAILED", Util.nz(authorisation.failureReason(), "The payment was declined"),
                        Map.of("code", Util.nz(authorisation.failureCode()), "riskBand", PaymentGateway.riskBand(authorisation.riskScore())));
            }
            payment = gateway.toPayment(authorisation, tender, invoice.number(), actorName(actor));
            payment = withAmount(payment, requested);
        } else if (method == PaymentMethod.GIFT_CARD) {
            payment = redeemGiftCard(invoice, body, requested, actor);
        } else {
            payment = offlinePayment(invoice, method, requested, body, actor);
        }

        Invoice updated = applyPayment(invoice, payment, actor);
        out.put("status", updated.status().name());
        out.put("payment", JsonMapper.toMap(payment));
        out.put("invoice", view(updated));
        out.put("receipt", receiptMeta(updated));
        out.put("settled", updated.status() == InvoiceStatus.PAID);
        return out;
    }

    private Invoice applyPayment(Invoice invoice, Payment payment, Actor actor) {
        List<Payment> history = new ArrayList<>(Util.nullSafe(invoice.payments()));
        history.add(payment);
        BigDecimal paid = Money.add(invoice.paid(), payment.amount());
        BigDecimal balance = Money.subtract(invoice.total(), paid);
        InvoiceStatus status = balance.signum() <= 0 ? InvoiceStatus.PAID
                : (paid.signum() > 0 ? InvoiceStatus.PARTIAL : InvoiceStatus.UNPAID);
        Invoice updated = invoice.withPayments(history, Money.scale(paid), Money.scale(balance), status);
        invoices.put(updated);
        search.index(updated);

        audit.record("payment.captured", "invoice", invoice.id(),
                payment.method().label() + " of " + Money.inr(payment.amount()) + " on " + invoice.number(),
                actor, AuditEvent.INFO, Util.map("gatewayRef", payment.gatewayRef(), "risk", payment.riskScore(),
                        "balance", balance.toPlainString()));

        if (status == InvoiceStatus.PAID) afterSettled(updated, actor);
        return updated;
    }

    /** Everything that happens once an invoice is fully settled. */
    private void afterSettled(Invoice invoice, Actor actor) {
        Customer customer = invoice.customerId() == null ? null : customers.get(invoice.customerId());
        BusinessSettings config = settings.current();

        if (customer != null) {
            Customer updated = loyalty.afterPurchase(customer, invoice.total(), invoice.pointsRedeemed(), config);
            if (updated != null && updated.tier() != null && !updated.tier().equals(customer.tier())) {
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("tier", updated.tier());
                BusinessSettings.Tier tier = config.tierByName(updated.tier());
                vars.put("perk", tier == null ? "" : tier.perk());
                notifications.queue("loyalty.tier", Notification.EMAIL, updated.email(), updated, vars,
                        "invoice", invoice.id(), actorName(actor));
            }
        }

        int earned = invoice.pointsEarned();
        if (earned > 0) {
            Invoice stamped = new Invoice(invoice.id(), invoice.number(), invoice.customerId(), invoice.customerName(),
                    invoice.customerPhone(), invoice.customerEmail(), invoice.appointmentIds(), invoice.lines(),
                    invoice.subtotal(), invoice.discount(), invoice.promoCode(), invoice.loyaltyRedemption(),
                    invoice.pointsRedeemed(), invoice.gratuity(), invoice.taxable(), invoice.cgst(), invoice.sgst(),
                    invoice.taxTotal(), invoice.roundOff(), invoice.total(), invoice.paid(), invoice.balance(),
                    invoice.status(), invoice.payments(), earned, invoice.notes(), invoice.footerNote(),
                    invoice.createdAt(), invoice.settledAt(), invoice.createdBy(), invoice.staffId(),
                    invoice.fiscalYear(), invoice.gstin(), invoice.source(), invoice.receiptCode(),
                    invoice.printed(), invoice.printCount());
            invoices.put(stamped);
        }

        try {
            inventory.consumeForInvoice(invoice, actor);
        } catch (RuntimeException e) {
            Log.warn("stock consumption failed for %s: %s", invoice.number(), e.getMessage());
        }

        for (String appointmentId : Util.nullSafe(invoice.appointmentIds())) {
            Appointment appointment = appointments.get(appointmentId);
            if (appointment == null) continue;
            if (Util.blank(appointment.invoiceId())) booking.attachInvoice(appointmentId, invoice.id());
            if (appointment.status() == AppointmentStatus.IN_CHAIR || appointment.status() == AppointmentStatus.ARRIVED
                    || appointment.status() == AppointmentStatus.CONFIRMED) {
                booking.setStatus(appointmentId, AppointmentStatus.COMPLETED, "Billed on " + invoice.number(), actor);
            }
        }

        if (Util.present(invoice.promoCode())) {
            // promo usage counting lives with the code record; keep it best-effort
            Log.debug("promo %s used on %s", invoice.promoCode(), invoice.number());
        }

        notifications.queueForInvoice("invoice.paid", invoice, Notification.EMAIL, actorName(actor), null);
        audit.record("invoice.settled", "invoice", invoice.id(), invoice.number() + " settled at "
                + Money.inr(invoice.paid()), actor);
    }

    private Payment offlinePayment(Invoice invoice, PaymentMethod method, BigDecimal amount, Map<String, Object> body, Actor actor) {
        String note = com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("note")));
        return new Payment(Util.id("pay"), method, amount, PaymentStatus.CAPTURED,
                method == PaymentMethod.CASH ? "CASH_" + Util.randomDigits(6) : null,
                null, null, null, null, null, null,
                JsonMapper.asText(body.get("bank")), JsonMapper.asText(body.get("wallet")), null,
                JsonMapper.asText(body.get("idempotencyKey")), false, null, null,
                LocalDateTime.now(), LocalDateTime.now(), BigDecimal.ZERO, List.of(), actorName(actor), note, 0,
                invoice.number());
    }

    private Payment redeemGiftCard(Invoice invoice, Map<String, Object> body, BigDecimal amount, Actor actor) {
        String raw = JsonMapper.asText(body.get("giftCardCode"));
        if (Util.blank(raw)) raw = JsonMapper.asText(body.get("code"));
        final String code = Util.nz(raw).trim();
        GiftCard card = giftCards.firstWhere(g -> Util.nz(g.code()).equalsIgnoreCase(code)).orElse(null);
        if (card == null) throw ApiException.notFound("Gift card " + Util.nz(code));
        if (!card.isUsable()) throw ApiException.conflict("That gift card is " + card.status().toLowerCase(Locale.ROOT));
        BigDecimal use = Money.min(amount, card.balance());
        giftCards.put(card.afterRedemption(use));
        search.index(giftCards.firstWhere(g -> g.id().equals(card.id())).orElse(card));
        audit.record("giftcard.redeem", "giftcard", card.id(), Money.inr(use) + " redeemed on " + invoice.number(), actor);
        return new Payment(Util.id("pay"), PaymentMethod.GIFT_CARD, use, PaymentStatus.CAPTURED,
                "GC_" + card.code(), null, null, null, null, null, null, null, null, card.code(),
                null, false, null, null, LocalDateTime.now(), LocalDateTime.now(), BigDecimal.ZERO, List.of(),
                actorName(actor), "Gift card " + card.code(), 0, invoice.number());
    }

    // ------------------------------------------------------------------ refunds & void

    public Map<String, Object> refund(String invoiceId, String paymentId, BigDecimal amount, String reason, Actor actor) {
        actor.require(Role.MANAGER);
        Invoice invoice = get(invoiceId);
        Payment payment = Util.nullSafe(invoice.payments()).stream()
                .filter(p -> p.id().equals(paymentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Payment " + Util.nz(paymentId)));
        Map<String, Object> result = gateway.refund(payment, amount, reason);
        BigDecimal refunded = Money.of(result.get("totalRefunded"));
        PaymentStatus status = Boolean.TRUE.equals(result.get("fullyRefunded"))
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        Payment.Refund leg = new Payment.Refund(Util.id("ref"), Money.of(result.get("amount")),
                com.sillage.security.Validator.sanitise(Util.nz(reason)), String.valueOf(result.get("refundId")),
                LocalDateTime.now(), actor.label());
        Payment updated = payment.withRefund(leg, refunded, status);

        List<Payment> history = new ArrayList<>(Util.nullSafe(invoice.payments()));
        for (int i = 0; i < history.size(); i++) if (history.get(i).id().equals(paymentId)) history.set(i, updated);
        BigDecimal paid = Money.subtract(invoice.paid(), Money.of(result.get("amount")));
        BigDecimal balance = Money.subtract(invoice.total(), paid);
        InvoiceStatus invoiceStatus = paid.signum() <= 0 ? InvoiceStatus.REFUNDED
                : (balance.signum() > 0 ? InvoiceStatus.PARTIAL : InvoiceStatus.PAID);
        Invoice next = invoice.withPayments(history, Money.scale(paid), Money.scale(balance), invoiceStatus);
        invoices.put(next);
        search.index(next);

        Customer customer = next.customerId() == null ? null : customers.get(next.customerId());
        if (customer != null) {
            BusinessSettings config = settings.current();
            int clawback = loyalty.earn(Money.of(result.get("amount")), customer, config);
            if (clawback > 0) {
                Customer adjusted = customer.withPoints(Math.max(0, customer.points() - clawback));
                customers.put(adjusted);
                search.index(adjusted);
            }
        }

        notifications.queueForInvoice("invoice.refund", next, Notification.EMAIL, actor.label(),
                Map.of("method", updated.method().label()));
        audit.record("payment.refund", "invoice", invoice.id(),
                Money.inr(Money.of(result.get("amount"))) + " refunded on " + invoice.number()
                        + (Util.present(reason) ? " (" + reason + ")" : ""), actor, AuditEvent.WARN,
                Util.map("payment", paymentId, "gatewayRef", result.get("refundId")));
        result.put("invoice", view(next));
        return result;
    }

    public Invoice voidInvoice(String invoiceId, String reason, Actor actor) {
        actor.require(Role.OWNER);
        Invoice invoice = get(invoiceId);
        if (Money.isPositive(invoice.paid())) throw ApiException.conflict("Refund the settled amount before voiding");
        Invoice voided = invoice.withStatus(InvoiceStatus.VOID).withNotes(
                Util.nz(invoice.notes()) + (Util.blank(invoice.notes()) ? "" : " \u00b7 ") + "VOIDED: " + Util.nz(reason, "no reason given"));
        invoices.put(voided);
        search.index(voided);
        audit.record("invoice.void", "invoice", invoice.id(), invoice.number() + " voided", actor, AuditEvent.WARN,
                Util.map("reason", Util.nz(reason)));
        return voided;
    }

    // ------------------------------------------------------------------ reads

    public Invoice get(String id) {
        Invoice invoice = invoices.get(id);
        if (invoice == null) throw ApiException.notFound("Invoice " + Util.nz(id));
        return invoice;
    }

    public Invoice find(String id) { return invoices.get(id); }

    public Invoice byNumber(String number) {
        if (Util.blank(number)) return null;
        return invoices.firstWhere(i -> Util.nz(i.number()).equalsIgnoreCase(number.trim())).orElse(null);
    }

    public List<Invoice> list(InvoiceStatus status, LocalDate from, LocalDate to, String customerId, int limit) {
        List<Invoice> out = invoices.where(i -> (status == null || i.status() == status)
                && (customerId == null || customerId.equals(i.customerId()))
                && (from == null || (i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)))
                && (to == null || (i.createdAt() != null && !i.createdAt().toLocalDate().isAfter(to))));
        out.sort(Comparator.comparing(Invoice::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public List<Invoice> forCustomer(String customerId) {
        return list(null, null, null, customerId, 500);
    }

    public Map<String, Object> view(Invoice invoice) {
        BusinessSettings config = settings.current();
        Map<String, Object> out = JsonMapper.toMap(invoice);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            lines.add(Util.map("id", line.id(), "kind", line.kind(), "name", line.name(), "detail", line.detail(),
                    "qty", line.qty(), "unitPrice", line.unitPrice(), "taxPct", line.taxPct(),
                    "amount", line.amount(), "staffId", line.staffId(),
                    "staff", line.staffId() == null ? null : staffName(line.staffId())));
        }
        out.put("lines", lines);
        out.put("customerLabel", Util.nz(invoice.customerName(), "Walk-in guest"));
        out.put("salon", Util.map("name", config.salonName(), "legalName", config.legalName(),
                "address", Util.join(List.of(Util.nz(config.addressLine()), Util.nz(config.city()),
                        Util.nz(config.state()), Util.nz(config.pincode())), ", "),
                "phone", config.phone(), "email", config.email(), "gstin", config.gstin(), "website", config.website()));
        out.put("statusLabel", invoice.status().label());
        out.put("createdAtLabel", invoice.createdAt() == null ? "" : STAMP.format(invoice.createdAt()));
        out.put("amountLabel", Money.inr(invoice.total()));
        out.put("paidLabel", Money.inr(invoice.paid()));
        out.put("balanceLabel", Money.inr(invoice.balance()));
        out.put("taxSplit", config.taxSplit());
        out.put("taxLabel", config.taxLabel());
        out.put("currency", config.currency());
        out.put("currencySymbol", config.currencySymbol());
        out.put("payments", Util.nullSafe(invoice.payments()).stream().map(p -> Util.map(
                "id", p.id(), "method", p.method().name(), "methodLabel", p.tenderLabel(), "amount", p.amount(),
                "status", p.status().name(), "statusLabel", p.status().label(), "gatewayRef", p.gatewayRef(),
                "authCode", p.authCode(), "brand", p.cardBrand(), "last4", p.cardLast4(), "vpa", p.upiVpa(),
                "riskScore", p.riskScore(), "riskBand", PaymentGateway.riskBand(p.riskScore()),
                "threeDsVerified", p.threeDsVerified(), "failureCode", p.failureCode(),
                "failureReason", p.failureReason(), "takenBy", p.takenBy(),
                "at", p.createdAt() == null ? null : p.createdAt().toString(),
                "refunded", p.refunded(), "refunds", p.refunds())).toList());
        out.put("receipt", receiptMeta(invoice));
        out.put("verifyUrl", "/api/receipts/" + invoice.id() + "/verify?code=" + Util.nz(invoice.receiptCode()));
        out.put("loyalty", loyalty.summary(invoice.customerId() == null ? null : customers.get(invoice.customerId())));
        return out;
    }

    private Map<String, Object> receiptMeta(Invoice invoice) {
        return Util.map(
                "id", invoice.id(),
                "number", invoice.number(),
                "code", invoice.receiptCode(),
                "text", "/api/receipts/" + invoice.id() + "/text",
                "html", "/api/receipts/" + invoice.id() + "/print",
                "json", "/api/receipts/" + invoice.id(),
                "page", "/receipt/" + invoice.number().replace("/", "-"));
    }

    public String staffName(String id) {
        if (Util.blank(id)) return null;
        Staff member = staffRepo.get(id);
        return member == null ? null : member.name();
    }

    // ------------------------------------------------------------------ receipts

    private String receiptKey() {
        String key = receiptKey;
        if (key != null) return key;
        synchronized (this) {
            if (receiptKey == null) {
                try {
                    if (Files.exists(receiptKeyFile)) {
                        receiptKey = Files.readString(receiptKeyFile).trim();
                    }
                    if (Util.blank(receiptKey)) {
                        receiptKey = Util.randomToken(32);
                        AtomicFiles.write(receiptKeyFile, receiptKey);
                        Log.info("generated a new receipt signing key");
                    }
                } catch (IOException e) {
                    receiptKey = Util.sha256Hex("sillage-fallback-" + LocalDate.now());
                    Log.warn("receipt key file unavailable, using an ephemeral key: %s", e.getMessage());
                }
            }
            return receiptKey;
        }
    }

    /** A tamper-evident seal printed on the receipt and checkable by anyone. */
    public String receiptCode(Invoice invoice) {
        String payload = invoice.number() + "|" + Money.scale(invoice.total()).toPlainString() + "|"
                + (invoice.createdAt() == null ? "" : invoice.createdAt().toLocalDate());
        return Util.hmacSha256(receiptKey(), payload).substring(0, 12).toUpperCase(Locale.ROOT);
    }

    public boolean verifyReceipt(String number, String code) {
        Invoice invoice = byNumber(number);
        if (invoice == null || Util.blank(code)) return false;
        return Util.constantTimeEquals(Util.nz(invoice.receiptCode()).toUpperCase(Locale.ROOT),
                code.trim().toUpperCase(Locale.ROOT));
    }

    private Invoice withReceiptCode(Invoice invoice, String code) {
        return new Invoice(invoice.id(), invoice.number(), invoice.customerId(), invoice.customerName(),
                invoice.customerPhone(), invoice.customerEmail(), invoice.appointmentIds(), invoice.lines(),
                invoice.subtotal(), invoice.discount(), invoice.promoCode(), invoice.loyaltyRedemption(),
                invoice.pointsRedeemed(), invoice.gratuity(), invoice.taxable(), invoice.cgst(), invoice.sgst(),
                invoice.taxTotal(), invoice.roundOff(), invoice.total(), invoice.paid(), invoice.balance(),
                invoice.status(), invoice.payments(), invoice.pointsEarned(), invoice.notes(), invoice.footerNote(),
                invoice.createdAt(), invoice.settledAt(), invoice.createdBy(), invoice.staffId(),
                invoice.fiscalYear(), invoice.gstin(), invoice.source(), code, invoice.printed(), invoice.printCount());
    }

    /** A plain-text receipt, laid out for a 58\u201380 mm thermal printer. */
    public String receiptText(Invoice invoice) {
        BusinessSettings config = settings.current();
        StringBuilder sb = new StringBuilder();
        String rule = "\u2500".repeat(44);
        sb.append(centre(Util.nz(config.salonName(), "SILLAGE").toUpperCase(Locale.ROOT), 44)).append('\n');
        sb.append(centre(Util.nz(config.tagline(), ""), 44)).append('\n');
        sb.append(centre(Util.nz(config.addressLine()), 44)).append('\n');
        sb.append(centre(Util.join(List.of(Util.nz(config.city()), Util.nz(config.pincode())), " "), 44)).append('\n');
        sb.append(centre("Tel " + Util.nz(config.phone()), 44)).append('\n');
        if (Util.present(config.gstin())) sb.append(centre("GSTIN " + config.gstin(), 44)).append('\n');
        sb.append(rule).append('\n');
        sb.append(left("Receipt", invoice.number())).append('\n');
        sb.append(left("Date", invoice.createdAt() == null ? "" : STAMP.format(invoice.createdAt()))).append('\n');
        sb.append(left("Client", Util.nz(invoice.customerName(), "Walk-in guest"))).append('\n');
        if (Util.present(invoice.customerPhone())) sb.append(left("Phone", invoice.customerPhone())).append('\n');
        sb.append(left("Billed by", Util.nz(invoice.createdBy()))).append('\n');
        sb.append(rule).append('\n');
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            sb.append(wrap(line.name(), 30)).append('\n');
            if (Util.present(line.detail())) sb.append("  ").append(Util.truncate(line.detail(), 40)).append('\n');
            sb.append(left("  " + line.qty() + " \u00d7 " + Money.format(line.unitPrice()), Money.format(line.amount()))).append('\n');
        }
        sb.append(rule).append('\n');
        sb.append(left("Subtotal", Money.format(invoice.subtotal()))).append('\n');
        if (Money.isPositive(invoice.discount())) {
            sb.append(left("Discount" + (Util.present(invoice.promoCode()) ? " (" + invoice.promoCode() + ")" : ""),
                    "-" + Money.format(invoice.discount()))).append('\n');
        }
        if (Money.isPositive(invoice.loyaltyRedemption())) {
            sb.append(left("Loyalty (" + invoice.pointsRedeemed() + " pts)", "-" + Money.format(invoice.loyaltyRedemption()))).append('\n');
        }
        if (Money.isPositive(invoice.taxable()) && config.pricesInclusiveOfTax()) {
            sb.append(left("Taxable value", Money.format(invoice.taxable()))).append('\n');
        }
        if (Money.isPositive(invoice.cgst())) sb.append(left("CGST", Money.format(invoice.cgst()))).append('\n');
        if (Money.isPositive(invoice.sgst())) sb.append(left("SGST", Money.format(invoice.sgst()))).append('\n');
        if (Money.isPositive(invoice.taxTotal()) && Money.isZero(invoice.cgst()) && Money.isZero(invoice.sgst())) {
            sb.append(left(Util.nz(config.taxLabel(), "Tax"), Money.format(invoice.taxTotal()))).append('\n');
        }
        if (Money.isPositive(invoice.gratuity())) sb.append(left("Gratuity", Money.format(invoice.gratuity()))).append('\n');
        if (!Money.isZero(invoice.roundOff())) sb.append(left("Round off", (invoice.roundOff().signum() > 0 ? "+" : "-") + Money.format(invoice.roundOff().abs()))).append('\n');
        sb.append(rule).append('\n');
        sb.append(left("TOTAL", Money.format(invoice.total()))).append('\n');
        for (Payment payment : Util.nullSafe(invoice.payments())) {
            if (!payment.isSuccessful()) continue;
            sb.append(left("  " + payment.tenderLabel(), Money.format(payment.amount()))).append('\n');
        }
        if (Money.isPositive(invoice.balance())) sb.append(left("Balance due", Money.format(invoice.balance()))).append('\n');
        sb.append(rule).append('\n');
        if (config.loyaltyEnabled() && invoice.pointsEarned() > 0) {
            sb.append(centre("Points earned: " + invoice.pointsEarned(), 44)).append('\n');
        }
        sb.append(centre("Receipt seal " + Util.nz(invoice.receiptCode()), 44)).append('\n');
        sb.append(centre("Verify at " + Util.nz(config.website(), "") + "/verify", 44)).append('\n');
        if (Util.present(invoice.notes())) sb.append('\n').append(wrap(invoice.notes(), 44)).append('\n');
        sb.append(rule).append('\n');
        sb.append(centre(Util.nz(config.policies() == null ? null : config.policies().get("cancellation"), ""), 44)).append('\n');
        sb.append(centre("Thank you \u2014 see you soon", 44)).append('\n');
        return sb.toString();
    }

    private static String left(String label, String value) {
        String l = Util.truncate(label, 28);
        String v = Util.nz(value);
        int pad = Math.max(1, 44 - l.length() - v.length());
        return l + " ".repeat(pad) + v;
    }

    private static String centre(String text, int width) {
        String value = Util.truncate(Util.nz(text), width);
        int pad = Math.max(0, width - value.length());
        return " ".repeat(pad / 2) + value;
    }

    private static String wrap(String text, int width) {
        String value = Util.nz(text);
        if (value.length() <= width) return value;
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (at < value.length()) {
            int end = Math.min(value.length(), at + width);
            if (end < value.length()) {
                int space = value.lastIndexOf(' ', end);
                if (space > at) end = space;
            }
            sb.append(value, at, end).append('\n');
            at = end;
            while (at < value.length() && value.charAt(at) == ' ') at++;
        }
        return sb.toString().stripTrailing();
    }

    public String toCsv(List<Invoice> list) {
        List<List<String>> rows = new ArrayList<>();
        for (Invoice i : list) {
            rows.add(List.of(Util.nz(i.number()), i.createdAt() == null ? "" : i.createdAt().toString(),
                    Util.nz(i.customerName()), Util.nz(i.customerPhone()), Util.nz(i.customerEmail()),
                    i.subtotal() == null ? "0" : i.subtotal().toPlainString(),
                    i.discount() == null ? "0" : i.discount().toPlainString(),
                    i.taxTotal() == null ? "0" : i.taxTotal().toPlainString(),
                    i.cgst() == null ? "0" : i.cgst().toPlainString(),
                    i.sgst() == null ? "0" : i.sgst().toPlainString(),
                    i.gratuity() == null ? "0" : i.gratuity().toPlainString(),
                    i.total() == null ? "0" : i.total().toPlainString(),
                    i.paid() == null ? "0" : i.paid().toPlainString(),
                    i.balance() == null ? "0" : i.balance().toPlainString(),
                    i.status() == null ? "" : i.status().name(),
                    i.primaryMethod(), String.valueOf(i.pointsEarned()), Util.nz(i.source()), Util.nz(i.createdBy())));
        }
        return Util.toCsv(List.of("number", "created_at", "customer", "phone", "email", "subtotal", "discount",
                "tax_total", "cgst", "sgst", "gratuity", "total", "paid", "balance", "status", "method",
                "points_earned", "source", "billed_by"), rows);
    }

    // ------------------------------------------------------------------ gift cards

    public GiftCard issueGiftCard(Map<String, Object> body, Actor actor) {
        BusinessSettings config = settings.current();
        if (!config.giftCardEnabled()) throw ApiException.conflict("Gift cards are disabled");
        BigDecimal amount = Money.of(body.get("amount"));
        if (amount.compareTo(new BigDecimal("500")) < 0) throw ApiException.validation(List.of("Gift cards start at \u20B9500"));
        if (amount.compareTo(new BigDecimal("100000")) > 0) throw ApiException.validation(List.of("That is above the \u20B91,00,000 gift-card ceiling"));
        String code = Util.present(JsonMapper.asText(body.get("code")))
                ? JsonMapper.asText(body.get("code")).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")
                : generateGiftCode();
        if (giftCards.firstWhere(g -> code.equalsIgnoreCase(g.code())).isPresent()) throw ApiException.conflict("That code is taken");
        GiftCard card = new GiftCard(Util.id("gc"), code,
                com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("purchaserName"))),
                JsonMapper.asText(body.get("purchaserPhone")),
                com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("recipientName"))),
                com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("message"))),
                amount, amount, GiftCard.ACTIVE, LocalDate.now().plusYears(2), LocalDateTime.now(), null,
                actor == null ? null : actor.id(), null, 0);
        giftCards.put(card);
        search.index(card);
        audit.record("giftcard.issue", "giftcard", card.id(), Money.inr(amount) + " gift card " + code, actor);
        return card;
    }

    private String generateGiftCode() {
        for (int i = 0; i < 12; i++) {
            String code = "SIL-" + Util.randomDigits(3) + "-" + Util.randomDigits(3);
            if (giftCards.firstWhere(g -> code.equals(g.code())).isEmpty()) return code;
        }
        return "SIL-" + Util.randomDigits(7);
    }

    public Map<String, Object> giftCardStatus(String code) {
        GiftCard card = giftCards.firstWhere(g -> Util.nz(g.code()).equalsIgnoreCase(Util.nz(code).trim())).orElse(null);
        if (card == null) throw ApiException.notFound("Gift card " + Util.nz(code));
        return Util.map("code", card.code(), "balance", card.balance(), "amount", card.amount(),
                "status", card.status(), "expiresOn", card.expiresOn() == null ? null : card.expiresOn().toString(),
                "recipient", card.recipientName(), "usable", card.isUsable(), "redemptions", card.redemptions());
    }

    public List<GiftCard> giftCards(int limit) {
        List<GiftCard> out = giftCards.all();
        out.sort(Comparator.comparing(GiftCard::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ------------------------------------------------------------------ reports

    /** The day-close (Z) report. */
    public Map<String, Object> dailySummary(LocalDate date) {
        List<Invoice> day = invoices.where(i -> i.createdAt() != null && i.createdAt().toLocalDate().equals(date));
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal tips = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, BigDecimal> byService = new LinkedHashMap<>();
        for (Invoice invoice : day) {
            if (invoice.status() == InvoiceStatus.VOID) continue;
            gross = Money.add(gross, invoice.total());
            tax = Money.add(tax, invoice.taxTotal());
            discount = Money.add(discount, invoice.discount());
            tips = Money.add(tips, invoice.gratuity());
            outstanding = Money.add(outstanding, invoice.balance());
            byStatus.merge(invoice.status().name(), 1, Integer::sum);
            for (Payment payment : Util.nullSafe(invoice.payments())) {
                if (payment.isSuccessful()) byMethod.merge(payment.method().label(), payment.amount(), Money::add);
                if (payment.refunded() != null) refunds = Money.add(refunds, payment.refunded());
            }
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                if (Invoice.Line.SERVICE.equals(line.kind()) || Invoice.Line.ADDON.equals(line.kind())) {
                    byService.merge(line.name(), line.amount(), Money::add);
                }
            }
        }
        List<Appointment> bookings = appointments.where(a -> a.start() != null && a.start().toLocalDate().equals(date));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("label", DAY.format(date));
        out.put("invoices", day.size());
        out.put("gross", gross);
        out.put("taxCollected", tax);
        out.put("discountGiven", discount);
        out.put("tips", tips);
        out.put("refunds", refunds);
        out.put("net", Money.subtract(gross, refunds));
        out.put("outstanding", outstanding);
        out.put("byMethod", byMethod);
        out.put("byStatus", byStatus);
        out.put("topServices", byService.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).limit(8)
                .map(e -> Util.map("name", e.getKey(), "amount", e.getValue())).toList());
        out.put("appointments", Util.map(
                "total", bookings.size(),
                "completed", bookings.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count(),
                "cancelled", bookings.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count(),
                "noShows", bookings.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count()));
        out.put("generatedAt", LocalDateTime.now().toString());
        return out;
    }

    public Map<String, Object> stats() {
        List<Invoice> all = invoices.all();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        int settled = 0;
        for (Invoice invoice : all) {
            if (invoice.status() == InvoiceStatus.VOID) continue;
            gross = Money.add(gross, invoice.total());
            outstanding = Money.add(outstanding, invoice.balance());
            if (invoice.status() == InvoiceStatus.PAID) settled++;
        }
        return Util.map("total", all.size(), "settled", settled, "gross", gross, "outstanding", outstanding,
                "averageTicket", all.isEmpty() ? BigDecimal.ZERO : Money.divide(gross, BigDecimal.valueOf(Math.max(1, all.size()))));
    }

    // ------------------------------------------------------------------ helpers

    private static Payment withAmount(Payment payment, BigDecimal amount) {
        return new Payment(payment.id(), payment.method(), amount, payment.status(), payment.gatewayRef(),
                payment.authCode(), payment.cardBrand(), payment.cardLast4(), payment.cardExpiry(), payment.cardToken(),
                payment.upiVpa(), payment.bank(), payment.wallet(), payment.giftCardCode(), payment.idempotencyKey(),
                payment.threeDsVerified(), payment.failureCode(), payment.failureReason(), payment.createdAt(),
                payment.capturedAt(), payment.refunded(), payment.refunds(), payment.takenBy(), payment.note(),
                payment.riskScore(), payment.invoiceNumber());
    }

    private Invoice withPayments(Invoice invoice, List<Payment> payments, BigDecimal paid, BigDecimal balance, InvoiceStatus status) {
        Invoice updated = invoice.withPayments(payments, Money.scale(paid), Money.scale(balance), status);
        invoices.put(updated);
        search.index(updated);
        return updated;
    }

    private String firstAppointment(Invoice invoice) {
        return invoice.appointmentIds() == null || invoice.appointmentIds().isEmpty() ? null : invoice.appointmentIds().get(0);
    }

    private int velocity(Actor actor) {
        if (actor == null || actor.ip() == null) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        return (int) intents.countWhere(i -> actor.ip().equals(i.ip()) && i.createdAt() != null && i.createdAt().isAfter(cutoff));
    }

    private static String actorName(Actor actor) {
        return actor == null ? "desk" : actor.label();
    }

    public Invoice markPrinted(String id) {
        Invoice invoice = get(id);
        Invoice printed = invoice.withPrinted();
        invoices.put(printed);
        return printed;
    }

    public Invoice updateNotes(String id, String notes, Actor actor) {
        Invoice invoice = get(id);
        Invoice updated = invoice.withNotes(com.sillage.security.Validator.sanitise(notes));
        invoices.put(updated);
        search.index(updated);
        return updated;
    }

    /** Products are re-read for the receipt's aftercare block. */
    public List<Product> productsOn(Invoice invoice) {
        List<Product> out = new ArrayList<>();
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            if (!Invoice.Line.PRODUCT.equals(line.kind())) continue;
            Product product = inventory.find(line.refId());
            if (product != null) out.add(product);
        }
        return out;
    }
}
