package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.Log;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Payment;
import com.sillage.model.PaymentIntent;
import com.sillage.model.PaymentMethod;
import com.sillage.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A self-contained payment gateway: intents, authorisation, capture, refunds, tokenisation,
 * 3-D Secure challenges and deterministic risk scoring.
 *
 * <p><strong>Why simulated.</strong> A live PSP needs credentials, a public webhook URL and a
 * merchant account, none of which a local demo should require. Everything the real thing does is
 * modelled here — the same state machine, the same idempotency rules, the same refusal to store a
 * PAN — so the checkout, receipt, refund and reconciliation paths are exercised end to end. Going
 * live means replacing {@link #authorise} with an HTTP call and keeping the rest.
 *
 * <p><strong>Card data.</strong> The PAN is validated (Luhn), classified by BIN, hashed into an
 * opaque token and then dropped. Only brand, last four, expiry and the token survive.
 */
public final class PaymentGateway {

    /** Tender details collected from the client. Never persisted as given. */
    public record Tender(PaymentMethod method, BigDecimal amount, String cardNumber, String cardExpiry,
                         String cardCvv, String cardHolder, String upiVpa, String bank, String wallet,
                         String giftCardCode, String idempotencyKey, String ip, int velocity) {}

    /** The gateway's decision. */
    public record Authorisation(boolean approved, PaymentStatus status, String gatewayRef, String authCode,
                                String failureCode, String failureReason, String cardBrand, String cardLast4,
                                String cardExpiry, String cardToken, boolean threeDsRequired, String challengeId,
                                int riskScore, Map<String, Object> details) {}

    private static final Map<String, String> DECLINE_MESSAGES = Map.ofEntries(
            Map.entry("generic_decline", "The card was declined by the issuing bank"),
            Map.entry("insufficient_funds", "Insufficient funds"),
            Map.entry("expired_card", "That card has expired"),
            Map.entry("incorrect_cvc", "The security code was refused"),
            Map.entry("processing_error", "The network could not complete the charge \u2014 try again"),
            Map.entry("do_not_honour", "The issuing bank refused this transaction"),
            Map.entry("authentication_required", "The bank asked for an extra authentication step"),
            Map.entry("velocity", "Too many attempts in a short window \u2014 wait a moment"),
            Map.entry("unsupported", "That tender is not enabled on this account"));

    private final Repo<PaymentIntent> intents;
    private final SettingsService settings;
    private final Map<String, Authorisation> idempotency = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> events = new ConcurrentLinkedDeque<>();
    private final String secret;
    private volatile boolean testMode = true;

    public PaymentGateway(Repo<PaymentIntent> intents, SettingsService settings) {
        this.intents = intents;
        this.settings = settings;
        this.secret = Util.randomToken(24);
        this.testMode = "TEST".equalsIgnoreCase(settings.current().gatewayMode());
    }

    public String name() { return Util.nz(settings.current().gatewayName(), "Sillage Pay"); }

    public boolean testMode() { return testMode; }

    public void setTestMode(boolean value) { this.testMode = value; }

    // ------------------------------------------------------------------ intents

    public PaymentIntent createIntent(BigDecimal amount, PaymentMethod method, String invoiceId, String invoiceNumber,
                                      String appointmentId, String customerId, String idempotencyKey, String ip) {
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("An intent needs a positive amount");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            for (PaymentIntent existing : intents.all()) {
                if (idempotencyKey.equals(existing.idempotencyKey()) && existing.isLive()
                        && Money.compare(existing.amount(), amount) == 0) {
                    Log.debug("intent reused via idempotency key %s", idempotencyKey);
                    return existing;
                }
            }
        }
        int ttl = Util.clamp(settings.current().intentTtlMinutes(), 1, 240);
        LocalDateTime now = LocalDateTime.now();
        PaymentIntent intent = new PaymentIntent(
                Util.id("pi"),
                Util.randomToken(20),
                invoiceId, invoiceNumber, appointmentId, customerId,
                Money.scale(amount), settings.current().currency(),
                method == null ? PaymentMethod.CARD : method,
                PaymentIntent.CREATED, null, null, 0, 0, null,
                Util.nz(idempotencyKey), ip, now, now.plusMinutes(ttl), null, null, testMode);
        intents.put(intent);
        publish("intent.created", intent, null);
        pruneIntents();
        return intent;
    }

    public PaymentIntent intent(String id) {
        PaymentIntent intent = intents.get(id);
        if (intent == null) throw ApiException.notFound("Payment intent " + Util.nz(id));
        return intent;
    }

    /** Confirms an intent: validates the client secret, runs the tender, may ask for 3-D Secure. */
    public Authorisation confirm(PaymentIntent intent, Tender tender, String clientSecret, String otp) {
        if (!Util.constantTimeEquals(Util.nz(intent.clientSecret()), Util.nz(clientSecret))) {
            throw ApiException.unauthorized("That payment session does not match");
        }
        if (intent.isExpired()) {
            intents.put(intent.withStatus(PaymentIntent.EXPIRED, "intent expired"));
            throw ApiException.conflict("This payment link has expired \u2014 start the checkout again");
        }
        if (PaymentIntent.SUCCEEDED.equals(intent.status())) {
            throw ApiException.conflict("This payment has already completed");
        }
        if (intent.attempts() >= 5) {
            intents.put(intent.withStatus(PaymentIntent.FAILED, "too many attempts"));
            throw ApiException.tooMany("Too many attempts on this payment. Please start a fresh checkout.", 300);
        }
        intents.put(intent.withAttempt(null));

        if (PaymentIntent.REQUIRES_AUTHENTICATION.equals(intent.status())) {
            if (Util.blank(otp)) throw ApiException.badRequest("Enter the authentication code we sent you");
            return completeOtp(intent, otp, tender);
        }

        Authorisation authorisation = authorise(intent, tender);
        if (authorisation.threeDsRequired()) {
            String challenge = Util.id("ch");
            String code = Util.randomDigits(Util.clamp(settings.current().otpLength(), 4, 8));
            intents.put(intent.withChallenge(challenge, Util.sha256Hex(secret + challenge + code)));
            Map<String, Object> details = new LinkedHashMap<>(authorisation.details());
            details.put("challengeId", challenge);
            details.put("maskedDestination", maskedDestination(tender));
            if (testMode) details.put("testOtp", code); // TEST mode only: the code is echoed so the flow is completable
            publish("intent.challenge", intent, details);
            return new Authorisation(false, PaymentStatus.PENDING_AUTHENTICATION, null, null,
                    "authentication_required", "An authentication code has been sent", authorisation.cardBrand(),
                    authorisation.cardLast4(), authorisation.cardExpiry(), authorisation.cardToken(), true,
                    challenge, authorisation.riskScore(), details);
        }
        if (authorisation.approved()) {
            intents.put(intent.withStatus(PaymentIntent.SUCCEEDED, null));
            publish("payment.succeeded", intent, authorisation.details());
        } else {
            intents.put(intent.withStatus(PaymentIntent.FAILED, authorisation.failureReason()));
            publish("payment.failed", intent, authorisation.details());
        }
        return authorisation;
    }

    private Authorisation completeOtp(PaymentIntent intent, String otp, Tender tender) {
        String expected = Util.sha256Hex(secret + Util.nz(intent.challengeId()) + otp.trim());
        if (intent.otpAttempts() >= 3) {
            intents.put(intent.withStatus(PaymentIntent.FAILED, "authentication abandoned"));
            throw ApiException.tooMany("Too many incorrect codes. Start the payment again.", 300);
        }
        intents.put(intent.withOtpAttempt());
        if (!Util.constantTimeEquals(expected, Util.nz(intent.otpHash()))) {
            publish("challenge.failed", intent, Map.of("attempts", intent.otpAttempts() + 1));
            return declined(intent, "incorrect_code", "That authentication code is not right", tender, false);
        }
        Authorisation authorisation = authorise(intent, tender, true);
        intents.put(authorisation.approved()
                ? intent.withStatus(PaymentIntent.SUCCEEDED, null)
                : intent.withStatus(PaymentIntent.FAILED, authorisation.failureReason()));
        publish(authorisation.approved() ? "payment.succeeded" : "payment.failed", intent, authorisation.details());
        return authorisation;
    }

    // ------------------------------------------------------------------ authorisation

    private Authorisation authorise(PaymentIntent intent, Tender tender) {
        return authorise(intent, tender, false);
    }

    private Authorisation authorise(PaymentIntent intent, Tender tender, boolean authenticated) {
        PaymentMethod method = tender == null || tender.method() == null ? intent.method() : tender.method();
        BigDecimal amount = Money.scale(intent.amount());

        if (tender != null && Util.present(tender.idempotencyKey())) {
            Authorisation cached = idempotency.get(tender.idempotencyKey());
            if (cached != null) {
                Log.debug("idempotent replay for key %s", tender.idempotencyKey());
                return cached;
            }
        }

        Authorisation result = switch (method) {
            case CARD -> authoriseCard(intent, tender, amount, authenticated);
            case UPI -> authoriseUpi(intent, tender, amount, authenticated);
            case NETBANKING -> authoriseBank(intent, tender, amount, authenticated);
            case WALLET -> authoriseWallet(intent, tender, amount, authenticated);
            case CASH, GIFT_CARD, LOYALTY, PAY_AT_SALON -> offline(method, amount);
        };

        if (tender != null && Util.present(tender.idempotencyKey())) {
            if (idempotency.size() > 512) idempotency.clear();
            idempotency.put(tender.idempotencyKey(), result);
        }
        return result;
    }

    private Authorisation authoriseCard(PaymentIntent intent, Tender tender, BigDecimal amount, boolean authenticated) {
        if (tender == null || Util.blank(tender.cardNumber())) {
            return declined(intent, "missing_card", "Card details are missing", tender, false);
        }
        String pan = tender.cardNumber().replaceAll("[^0-9]", "");
        if (pan.length() < 12 || pan.length() > 19) return declined(intent, "invalid_number", "That card number is not valid", tender, false);
        if (!com.sillage.security.Validator.luhn(pan)) return declined(intent, "invalid_number", "That card number failed its checksum", tender, false);
        if (Util.blank(tender.cardExpiry())) return declined(intent, "invalid_expiry", "The expiry date is missing", tender, false);
        if (Util.present(tender.cardCvv()) && (tender.cardCvv().length() < 3 || tender.cardCvv().length() > 4)) {
            return declined(intent, "incorrect_cvc", "The security code must be 3 or 4 digits", tender, false);
        }
        String brand = brandOf(pan);
        String last4 = pan.substring(pan.length() - 4);
        String token = tokenize(pan);

        if (!authenticated && requiresChallenge(pan, brand)) {
            int risk = risk(intent, tender, brand, false);
            return new Authorisation(false, PaymentStatus.PENDING_AUTHENTICATION, null, null, "authentication_required",
                    "This card needs an authentication step", brand, last4, tender.cardExpiry(), token, true, null, risk,
                    Map.of("brand", brand, "last4", last4, "threeDs", true));
        }

        String decline = declineCodeFor(pan, tender);
        int risk = risk(intent, tender, brand, authenticated);
        if (decline != null) return declined(intent, decline, DECLINE_MESSAGES.getOrDefault(decline, "Declined"), tender, authenticated, brand, last4, token);
        if (settings.current().simulateGatewayFailures() && tender.velocity() > 8) {
            return declined(intent, "velocity", DECLINE_MESSAGES.get("velocity"), tender, authenticated, brand, last4, token);
        }

        String reference = reference("pay");
        String authCode = Util.randomDigits(6);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("brand", brand);
        details.put("last4", last4);
        details.put("token", token);
        details.put("authCode", authCode);
        details.put("riskScore", risk);
        details.put("threeDsVerified", authenticated);
        details.put("network", networkFor(brand));
        details.put("amount", amount.toPlainString());
        return new Authorisation(true, PaymentStatus.CAPTURED, reference, authCode, null, null, brand, last4,
                tender.cardExpiry(), token, false, null, risk, details);
    }

    private Authorisation authoriseUpi(PaymentIntent intent, Tender tender, BigDecimal amount, boolean authenticated) {
        if (tender == null || Util.blank(tender.upiVpa())) return declined(intent, "missing_vpa", "Enter your UPI id", tender, false);
        String vpa = tender.upiVpa().trim().toLowerCase(Locale.ROOT);
        if (!vpa.matches("^[a-z0-9._\\-]{2,64}@[a-z]{2,32}$")) return declined(intent, "invalid_vpa", "That UPI id does not look right", tender, false);
        if (!authenticated && (vpa.startsWith("otp") || vpa.contains("secure"))) {
            return new Authorisation(false, PaymentStatus.PENDING_AUTHENTICATION, null, null, "authentication_required",
                    "Approve the collect request on your UPI app", null, null, null, null, true, null,
                    risk(intent, tender, "UPI", false), Map.of("vpa", vpa));
        }
        if (vpa.startsWith("fail") || vpa.startsWith("decline")) {
            return declined(intent, "upi_failed", "The UPI collect request was declined", tender, false);
        }
        if (vpa.startsWith("insufficient") || vpa.startsWith("lowbal")) {
            return declined(intent, "insufficient_funds", DECLINE_MESSAGES.get("insufficient_funds"), tender, false);
        }
        String reference = reference("upi");
        Map<String, Object> details = Map.of("vpa", vpa, "psp", pspFor(vpa), "amount", amount.toPlainString(),
                "riskScore", risk(intent, tender, "UPI", authenticated));
        return new Authorisation(true, PaymentStatus.CAPTURED, reference, Util.randomDigits(6), null, null,
                null, null, null, null, false, null, risk(intent, tender, "UPI", authenticated), details);
    }

    private Authorisation authoriseBank(PaymentIntent intent, Tender tender, BigDecimal amount, boolean authenticated) {
        String bank = tender == null || Util.blank(tender.bank()) ? "HDFC" : tender.bank().trim();
        if (bank.equalsIgnoreCase("FAILBANK") || bank.startsWith("fail")) {
            return declined(intent, "bank_declined", bank + " could not complete the transfer", tender, false);
        }
        String reference = reference("nb");
        return new Authorisation(true, PaymentStatus.CAPTURED, reference, Util.randomDigits(6), null, null,
                null, null, null, null, false, null, risk(intent, tender, bank, authenticated),
                Map.of("bank", bank, "amount", amount.toPlainString()));
    }

    private Authorisation authoriseWallet(PaymentIntent intent, Tender tender, BigDecimal amount, boolean authenticated) {
        String wallet = tender == null || Util.blank(tender.wallet()) ? "Paytm" : tender.wallet().trim();
        if (wallet.toLowerCase(Locale.ROOT).startsWith("fail")) {
            return declined(intent, "wallet_declined", wallet + " declined the charge", tender, false);
        }
        String reference = reference("wl");
        return new Authorisation(true, PaymentStatus.CAPTURED, reference, Util.randomDigits(6), null, null,
                null, null, null, null, false, null, risk(intent, tender, wallet, authenticated),
                Map.of("wallet", wallet, "amount", amount.toPlainString()));
    }

    private Authorisation offline(PaymentMethod method, BigDecimal amount) {
        String reference = reference(method.name().toLowerCase(Locale.ROOT).substring(0, Math.min(4, method.name().length())));
        Map<String, Object> details = Map.of("channel", "offline", "amount", amount.toPlainString());
        return new Authorisation(true, PaymentStatus.CAPTURED, reference, null, null, null, null, null, null, null,
                false, null, 0, details);
    }

    private Authorisation declined(PaymentIntent intent, String code, String message, Tender tender, boolean authenticated) {
        return declined(intent, code, message, tender, authenticated, null, null, null);
    }

    private Authorisation declined(PaymentIntent intent, String code, String message, Tender tender,
                                   boolean authenticated, String brand, String last4, String token) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("amount", intent.amount().toPlainString());
        if (brand != null) details.put("brand", brand);
        if (last4 != null) details.put("last4", last4);
        return new Authorisation(false, PaymentStatus.FAILED, null, null, code, message, brand, last4,
                tender == null ? null : tender.cardExpiry(), token, false, null,
                risk(intent, tender, brand, authenticated), details);
    }

    // ------------------------------------------------------------------ capture & refunds

    /** Materialises an {@link Payment} record from a successful authorisation. */
    public Payment toPayment(Authorisation authorisation, Tender tender, String invoiceNumber, String takenBy) {
        PaymentMethod method = tender == null ? PaymentMethod.CASH : tender.method();
        return new Payment(Util.id("pay"), method,
                Money.scale(new BigDecimal(String.valueOf(authorisation.details().getOrDefault("amount", "0")))),
                authorisation.approved() ? PaymentStatus.CAPTURED : PaymentStatus.FAILED,
                authorisation.gatewayRef(), authorisation.authCode(),
                authorisation.cardBrand(), authorisation.cardLast4(), authorisation.cardExpiry(), authorisation.cardToken(),
                tender == null ? null : lower(tender.upiVpa()),
                tender == null ? null : tender.bank(),
                tender == null ? null : tender.wallet(),
                tender == null ? null : giftCode(tender.giftCardCode()),
                tender == null ? null : tender.idempotencyKey(),
                Boolean.TRUE.equals(authorisation.details().get("threeDsVerified")),
                authorisation.failureCode(), authorisation.failureReason(),
                LocalDateTime.now(), authorisation.approved() ? LocalDateTime.now() : null,
                BigDecimal.ZERO, List.of(), takenBy, null, authorisation.riskScore(), invoiceNumber);
    }

    public Map<String, Object> refund(Payment payment, BigDecimal amount, String reason) {
        if (payment == null) throw ApiException.notFound("Payment");
        if (!payment.status().isRefundable()) {
            throw ApiException.conflict("A " + payment.status().label().toLowerCase(Locale.ROOT) + " payment cannot be refunded");
        }
        BigDecimal already = Money.nz(payment.refunded());
        BigDecimal requested = Money.scale(amount == null ? Money.subtract(payment.amount(), already) : amount);
        if (requested.signum() <= 0) throw ApiException.badRequest("Refund amount must be positive");
        if (Money.add(already, requested).compareTo(payment.amount()) > 0) {
            throw ApiException.conflict("That exceeds the " + Money.inr(Money.subtract(payment.amount(), already)) + " left to refund");
        }
        String reference = reference("ref");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundId", reference);
        out.put("amount", requested);
        out.put("reason", Util.nz(reason));
        out.put("gatewayRef", payment.gatewayRef());
        out.put("method", payment.method().label());
        out.put("settlesIn", payment.method().isOnline() ? "3\u20135 working days" : "immediate (at the desk)");
        out.put("totalRefunded", Money.add(already, requested));
        out.put("fullyRefunded", Money.add(already, requested).compareTo(payment.amount()) >= 0);
        out.put("at", LocalDateTime.now().toString());
        publish("payment.refunded", null, out);
        return out;
    }

    // ------------------------------------------------------------------ card intelligence

    public static String brandOf(String pan) {
        if (pan == null || pan.isEmpty()) return "UNKNOWN";
        if (pan.startsWith("4")) return "Visa";
        if (pan.matches("^5[1-5].*") || pan.matches("^2(2[2-9]|[3-6][0-9]|7[01]|720).*")) return "Mastercard";
        if (pan.matches("^3[47].*")) return "American Express";
        if (pan.matches("^3(0[0-5]|[68]).*")) return "Diners Club";
        if (pan.startsWith("60") || pan.startsWith("65") || pan.startsWith("81") || pan.startsWith("82")
                || pan.startsWith("508") || pan.matches("^6(Ru)?[0-9]{6,}")) return "RuPay";
        if (pan.startsWith("6")) return "Discover";
        return "UNKNOWN";
    }

    private static String networkFor(String brand) {
        return switch (Util.nz(brand)) {
            case "Visa" -> "VisaNet";
            case "Mastercard" -> "Mastercard Network";
            case "American Express" -> "Amex SafeKey";
            case "RuPay" -> "NPCI RuPay";
            case "Diners Club", "Discover" -> "Discover Global";
            default -> "unknown network";
        };
    }

    /** The PAN is never stored: only an opaque, keyed token derived from it. */
    public String tokenize(String pan) {
        String digits = pan == null ? "" : pan.replaceAll("[^0-9]", "");
        return "tok_" + Util.hmacSha256(secret, digits).substring(0, 22);
    }

    private static boolean requiresChallenge(String pan, String brand) {
        if ("American Express".equals(brand)) return true;
        if (pan.startsWith("400025") || pan.startsWith("40000025")) return true; // 3DS test range
        long amountIndependent = Long.parseLong(pan.substring(Math.max(0, pan.length() - 6))) % 10;
        return amountIndependent == 7L && pan.length() == 16 && pan.startsWith("5");
    }

    private static String declineCodeFor(String pan, Tender tender) {
        if (pan.endsWith("0002")) return "generic_decline";
        if (pan.endsWith("9995")) return "insufficient_funds";
        if (pan.endsWith("0119")) return "processing_error";
        if (pan.endsWith("0127")) return "incorrect_cvc";
        if (pan.endsWith("0101")) return "expired_card";
        if (pan.endsWith("0019") || pan.endsWith("6666")) return "do_not_honour";
        if (tender != null && "000".equals(tender.cardCvv())) return "incorrect_cvc";
        return null;
    }

    /**
     * Deterministic risk score (0\u2013100). No model, no history outside this process: a documented
     * sum of the factors a fraud desk would check by hand.
     */
    public int risk(PaymentIntent intent, Tender tender, String brand, boolean authenticated) {
        int score = 5;
        BigDecimal amount = intent == null ? BigDecimal.ZERO : Money.nz(intent.amount());
        if (amount.compareTo(new BigDecimal("25000")) > 0) score += 25;
        else if (amount.compareTo(new BigDecimal("10000")) > 0) score += 12;
        if ("UNKNOWN".equals(brand)) score += 20;
        if (!authenticated && tender != null && tender.method() == PaymentMethod.CARD) score += 15;
        if (authenticated) score -= 20;
        if (tender != null && tender.velocity() > 5) score += Math.min(25, tender.velocity() * 3);
        if (tender != null && Util.blank(tender.cardHolder()) && tender.method() == PaymentMethod.CARD) score += 8;
        if (intent != null && intent.attempts() > 1) score += 6 * Math.min(3, intent.attempts());
        return Util.clamp(score, 0, 100);
    }

    public static String riskBand(int score) {
        if (score >= 70) return "review";
        if (score >= 40) return "elevated";
        return "normal";
    }

    // ------------------------------------------------------------------ helpers & console

    private static String reference(String prefix) {
        return prefix.toUpperCase(Locale.ROOT) + "_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + "_" + Util.randomDigits(4);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String giftCode(String value) {
        if (Util.blank(value)) return null;
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\-]", "");
    }

    private String maskedDestination(Tender tender) {
        if (tender == null) return "your registered device";
        if (tender.method() == PaymentMethod.UPI) return Util.nz(tender.upiVpa());
        if (tender.method() == PaymentMethod.CARD && Util.present(tender.cardNumber())) {
            String digits = tender.cardNumber().replaceAll("[^0-9]", "");
            return "the number ending " + (digits.length() >= 4 ? digits.substring(digits.length() - 4) : "\u2022\u2022\u2022\u2022");
        }
        return "your registered device";
    }

    private static String pspFor(String vpa) {
        String handle = vpa.contains("@") ? vpa.substring(vpa.indexOf('@') + 1) : "";
        return switch (handle) {
            case "okhdfcbank", "okicici", "okaxis", "oksbi" -> "Google Pay / NPCI";
            case "ybl", "ibl", "axl" -> "PhonePe";
            case "paytm" -> "Paytm";
            case "yapl" -> "Amazon Pay";
            case "upi" -> "UPI (generic)";
            default -> Util.nz(handle, "UPI");
        };
    }

    private void publish(String type, PaymentIntent intent, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", Util.id("evt"));
        event.put("type", type);
        event.put("at", LocalDateTime.now().toString());
        event.put("intentId", intent == null ? null : intent.id());
        event.put("invoiceId", intent == null ? null : intent.invoiceId());
        event.put("amount", intent == null ? null : intent.amount());
        event.put("payload", payload == null ? Map.of() : payload);
        events.addFirst(event);
        while (events.size() > 120) events.pollLast();
    }

    public List<Map<String, Object>> events(int limit) {
        return events.stream().limit(limit).toList();
    }

    public Map<String, Object> console() {
        List<PaymentIntent> all = intents.all();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        BigDecimal attempted = BigDecimal.ZERO;
        BigDecimal succeeded = BigDecimal.ZERO;
        for (PaymentIntent intent : all) {
            byStatus.merge(intent.status(), 1, Integer::sum);
            attempted = Money.add(attempted, intent.amount());
            if (PaymentIntent.SUCCEEDED.equals(intent.status())) succeeded = Money.add(succeeded, intent.amount());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateway", name());
        out.put("mode", testMode ? "TEST" : "LIVE");
        out.put("intents", all.size());
        out.put("byStatus", byStatus);
        out.put("attempted", attempted);
        out.put("succeeded", succeeded);
        out.put("approvalRate", attempted.signum() == 0 ? 0 : Util.percent(succeeded.doubleValue(), attempted.doubleValue()));
        out.put("idempotencyKeysHeld", idempotency.size());
        out.put("recentEvents", events(20));
        out.put("testCards", testCards());
        out.put("security", List.of(
                "PAN is validated with Luhn, classified by BIN, then discarded",
                "Only brand, last four, expiry and an opaque HMAC token are stored",
                "CVV is never written to disk or to the audit trail",
                "Every intent carries a single-use client secret compared in constant time",
                "Idempotency keys make a retried charge return the original authorisation",
                "OTP hashes are salted with a per-boot secret; attempts are capped at three"));
        return out;
    }

    /** The documented test tender set, shown in the checkout drawer in TEST mode. */
    public List<Map<String, Object>> testCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(testCard("4111 1111 1111 1111", "Visa", "Approves", null));
        out.add(testCard("5555 5555 5555 4444", "Mastercard", "Approves", null));
        out.add(testCard("3782 8224 6310 005", "American Express", "Approves after a 3-D Secure code", "3ds"));
        out.add(testCard("6521 1111 1111 1117", "RuPay", "Approves", null));
        out.add(testCard("4000 0000 0000 0002", "Visa", "Declined \u2014 generic", "generic_decline"));
        out.add(testCard("4000 0000 0000 9995", "Visa", "Declined \u2014 insufficient funds", "insufficient_funds"));
        out.add(testCard("4000 0000 0000 0119", "Visa", "Declined \u2014 processing error", "processing_error"));
        out.add(testCard("4000 0000 0000 0127", "Visa", "Declined \u2014 incorrect CVV", "incorrect_cvc"));
        out.add(testCard("4000 0000 0000 0101", "Visa", "Declined \u2014 expired card", "expired_card"));
        out.add(testCard("4000 0000 0000 0019", "Visa", "Declined \u2014 do not honour", "do_not_honour"));
        return out;
    }

    private static Map<String, Object> testCard(String number, String brand, String behaviour, String flag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", number);
        out.put("brand", brand);
        out.put("behaviour", behaviour);
        out.put("expiry", "12/30");
        out.put("cvv", "123");
        if (flag != null) out.put("flag", flag);
        return out;
    }

    public List<Map<String, Object>> recentIntents(int limit) {
        List<PaymentIntent> all = new ArrayList<>(intents.all());
        all.sort(Comparator.comparing(PaymentIntent::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PaymentIntent intent : all) {
            if (out.size() >= limit) break;
            out.add(Util.map("id", intent.id(), "amount", intent.amount(), "method", intent.method().name(),
                    "status", intent.status(), "invoiceNumber", intent.invoiceNumber(),
                    "createdAt", intent.createdAt() == null ? null : intent.createdAt().toString(),
                    "expired", intent.isExpired(), "attempts", intent.attempts(), "lastError", intent.lastError()));
        }
        return out;
    }

    /** Drops intents older than three days that never succeeded. Returns how many went. */
    public int pruneIntents() {
        if (intents.size() < 400) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        List<PaymentIntent> stale = intents.where(i -> i.createdAt() != null && i.createdAt().isBefore(cutoff)
                && !PaymentIntent.SUCCEEDED.equals(i.status()));
        for (PaymentIntent intent : stale) intents.remove(intent.id());
        return stale.size();
    }

    public void cancelIntent(String id) {
        PaymentIntent intent = intent(id);
        if (PaymentIntent.SUCCEEDED.equals(intent.status())) throw ApiException.conflict("That payment already succeeded");
        intents.put(intent.withStatus(PaymentIntent.CANCELLED, "cancelled by operator"));
        publish("intent.cancelled", intent, null);
    }

    public static List<String> supportedMethods() {
        return Arrays.stream(PaymentMethod.values()).map(PaymentMethod::name).toList();
    }
}
