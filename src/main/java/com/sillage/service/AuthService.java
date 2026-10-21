package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.Config;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.AuditEvent;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Credential;
import com.sillage.model.Customer;
import com.sillage.model.Role;
import com.sillage.model.SessionRecord;
import com.sillage.model.Staff;
import com.sillage.model.UserAccount;
import com.sillage.security.PasswordHasher;
import com.sillage.security.SessionManager;
import com.sillage.security.Validator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Accounts, sessions and roles \u2014 all optional. The whole salon is usable without an account:
 * {@link #guest} hands out a session with read access to public data and enough write access to
 * book a chair and pay for it.
 */
public final class AuthService {

    private static final int MAX_FAILED = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final Duration RESET_TTL = Duration.ofMinutes(20);

    private final Repo<UserAccount> accounts;
    private final Repo<Credential> credentials;
    private final Repo<SessionRecord> sessions;
    private final Repo<Customer> customers;
    private final Repo<Staff> staffRepo;
    private final SessionManager sessionManager;
    private final PasswordHasher hasher;
    private final AuditService audit;
    private final SearchService search;
    private final SettingsService settings;
    private final CustomerService customerService;
    private final Config config;
    private final Map<String, ResetToken> resetTokens = new LinkedHashMap<>();

    private record ResetToken(String token, String userId, LocalDateTime expiresAt, boolean used) {}

    public AuthService(Repo<UserAccount> accounts, Repo<Credential> credentials, Repo<SessionRecord> sessions,
                       Repo<Customer> customers, Repo<Staff> staffRepo, SessionManager sessionManager,
                       AuditService audit, SearchService search, SettingsService settings,
                       CustomerService customerService, Config config) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.sessions = sessions;
        this.customers = customers;
        this.staffRepo = staffRepo;
        this.sessionManager = sessionManager;
        this.hasher = new PasswordHasher();
        this.audit = audit;
        this.search = search;
        this.settings = settings;
        this.customerService = customerService;
        this.config = config;
    }

    // ------------------------------------------------------------------ identity

    public Actor actorOf(SessionRecord session, UserAccount account, String ip, String userAgent) {
        if (account != null && session != null) return Actor.of(account, session, ip, userAgent);
        if (session != null) return Actor.guest(session, ip, userAgent);
        return Actor.anonymous(ip, userAgent);
    }

    public Optional<UserAccount> account(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(accounts.get(id));
    }

    public UserAccount byUsername(String value) {
        if (Util.blank(value)) return null;
        String key = value.trim().toLowerCase(Locale.ROOT);
        return accounts.firstWhere(a -> key.equals(Util.nz(a.username()).toLowerCase(Locale.ROOT))
                || key.equals(Util.nz(a.email()).toLowerCase(Locale.ROOT))).orElse(null);
    }

    // ------------------------------------------------------------------ registration

    public Map<String, Object> register(Map<String, Object> body, String ip, String userAgent) {
        Validator validator = Validator.of(body);
        String username = validator.username("username", true);
        String email = validator.email("email", true);
        String password = validator.text("password", 128, true);
        String displayName = validator.optional("displayName", 80);
        String phone = validator.phone("phone", false);
        boolean wantsCustomer = validator.bool("createCustomerRecord", true);
        validator.validate("register");

        PasswordHasher.Policy policy = hasher.evaluate(password);
        if (!policy.isAcceptable()) {
            throw ApiException.validation(policy.problems());
        }
        if (byUsername(username) != null) throw ApiException.conflict("That username is taken");
        String emailKey = email.toLowerCase(Locale.ROOT);
        if (accounts.firstWhere(a -> emailKey.equals(Util.nz(a.email()).toLowerCase(Locale.ROOT))).isPresent()) {
            throw ApiException.conflict("An account already uses that email");
        }

        LocalDateTime now = LocalDateTime.now();
        UserAccount account = new UserAccount(Util.id("usr"), username.trim().toLowerCase(Locale.ROOT), emailKey,
                Util.blank(displayName) ? Util.titleCase(username) : Validator.sanitise(displayName), Role.CLIENT,
                null, null, phone, true, false, false, 0, null, null, null, 0,
                defaultPreferences(), now, now);
        accounts.put(account);
        storeCredential(account.id(), password, null);

        if (wantsCustomer) {
            Customer customer = customerService.byEmail(emailKey);
            if (customer == null && Util.present(phone)) customer = customerService.byPhone(phone);
            if (customer == null) {
                customer = customerService.findOrCreate(account.displayName(), phone, emailKey, "account",
                        Actor.anonymous(ip, userAgent));
            }
            account = withCustomer(account, customer.id());
            accounts.put(account);
        }

        SessionManager.Issued issued = sessionManager.issue(account, ip, userAgent);
        audit.security("auth.register", "Account created for " + account.username(), ip,
                Map.of("userId", account.id(), "role", account.role().name()));
        Log.info("registered account %s (%s)", account.username(), account.role());
        return Map.of("account", view(account), "token", issued.token(), "session", sessionView(issued.session()),
                "cookie", sessionManager.userCookieHeader(issued.token(), config.secureCookies()),
                "csrf", sessionManager.csrfCookieHeader(issued.session().csrfToken(), config.secureCookies()),
                "passwordPolicy", passwordPolicy());
    }

    private static UserAccount withCustomer(UserAccount account, String customerId) {
        return new UserAccount(account.id(), account.username(), account.email(), account.displayName(), account.role(),
                customerId, account.staffId(), account.phone(), account.active(), account.emailVerified(),
                account.mustChangePassword(), account.failedAttempts(), account.lockedUntil(), account.lastLoginAt(),
                account.lastLoginIp(), account.loginCount(), account.preferences(), account.createdAt(),
                LocalDateTime.now());
    }

    // ------------------------------------------------------------------ sessions

    public Map<String, Object> login(Map<String, Object> body, String ip, String userAgent) {
        String identifier = Util.nz(JsonMapper.asText(body.get("username")), JsonMapper.asText(body.get("email")));
        String password = JsonMapper.asText(body.get("password"));
        if (Util.blank(identifier) || Util.blank(password)) {
            throw ApiException.validation(List.of("Enter your username or email and your password"));
        }
        UserAccount account = byUsername(identifier);
        if (account == null) {
            // spend the same time as a real check so timing cannot enumerate accounts
            hasher.verify(password, hasher.newSalt(), Util.sha256Hex("decoy"), hasher.iterations());
            audit.security("auth.login.unknown", "Login attempt for an unknown account " + identifier, ip,
                    Map.of("identifier", identifier));
            throw ApiException.unauthorized("Those details do not match an account");
        }
        if (!account.active()) throw ApiException.forbidden("That account is disabled");
        if (account.isLocked()) {
            long minutes = Math.max(1, Duration.between(LocalDateTime.now(), account.lockedUntil()).toMinutes());
            throw new ApiException(423, "ACCOUNT_LOCKED",
                    "Too many failed attempts \u2014 try again in " + minutes + " minutes",
                    Map.of("lockedUntil", account.lockedUntil().toString(), "minutes", minutes));
        }
        Credential credential = credentials.firstWhere(c -> account.id().equals(c.userId())).orElse(null);
        if (credential == null || !hasher.verify(password, credential.salt(), credential.hash(), credential.iterations())) {
            LocalDateTime lockAt = account.failedAttempts() + 1 >= MAX_FAILED
                    ? LocalDateTime.now().plus(LOCKOUT) : null;
            accounts.put(account.withFailedAttempt(lockAt));
            audit.security(lockAt == null ? "auth.login.failed" : "auth.locked",
                    "Failed password for " + account.username() + (lockAt == null ? "" : " \u2014 account locked"), ip,
                    Map.of("userId", account.id(), "attempts", account.failedAttempts() + 1, "max", MAX_FAILED));
            if (lockAt != null) {
                sessionManager.revokeAllForUser(account.id(), "account locked after repeated failures");
                throw new ApiException(423, "ACCOUNT_LOCKED", "Too many failed attempts \u2014 locked for "
                        + LOCKOUT.toMinutes() + " minutes", Map.of("lockedUntil", lockAt.toString()));
            }
            throw ApiException.unauthorized("Those details do not match an account ("
                    + (MAX_FAILED - account.failedAttempts() - 1) + " attempts left)");
        }
        if (hasher.needsRehash(credential.iterations())) {
            storeCredential(account.id(), password, credential.history());
            Log.debug("rehashed the credential for %s at %d iterations", account.username(), hasher.iterations());
        }

        UserAccount updated = account.withLogin(ip);
        accounts.put(updated);
        if (updated.mustChangePassword()) sessionManager.revokeAllForUser(updated.id(), "password changed");
        SessionManager.Issued issued = sessionManager.issue(updated, ip, userAgent);
        audit.security("auth.login", account.username() + " signed in", ip,
                Map.of("userId", account.id(), "role", account.role().name(), "sessionId", issued.session().id()));
        return Map.of("account", view(updated), "token", issued.token(), "session", sessionView(issued.session()),
                "cookie", sessionManager.userCookieHeader(issued.token(), config.secureCookies()),
                "csrf", sessionManager.csrfCookieHeader(issued.session().csrfToken(), config.secureCookies()),
                "mustChangePassword", updated.mustChangePassword(),
                "greeting", greeting(updated));
    }

    /** A session with no account attached \u2014 the default way in. */
    public Map<String, Object> guest(String ip, String userAgent, String customerId) {
        Customer customer = Util.present(customerId) ? customers.get(customerId) : null;
        SessionManager.Issued issued = sessionManager.issueGuest(ip, userAgent, customer == null ? null : customer.id());
        BusinessSettings salon = settings.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionView(issued.session()));
        out.put("token", issued.token());
        out.put("cookie", sessionManager.guestCookieHeader(issued.token(), config.secureCookies()));
        out.put("csrf", sessionManager.csrfCookieHeader(issued.session().csrfToken(), config.secureCookies()));
        out.put("guest", true);
        out.put("customer", customer == null ? null : JsonMapper.toMap(customer));
        out.put("welcome", "You are browsing as a guest of " + salon.salonName()
                + " \u2014 no account needed to book a chair.");
        return out;
    }

    public void logout(SessionRecord session, String ip) {
        if (session == null) return;
        sessionManager.revoke(session.id(), "signed out");
        audit.security("auth.logout", Util.nz(session.displayName(), "session") + " signed out", ip,
                Map.of("sessionId", session.id()));
    }

    public Map<String, Object> me(SessionRecord session, UserAccount account) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionView(session));
        out.put("account", account == null ? null : view(account));
        out.put("customer", account != null && account.customerId() != null
                ? JsonMapper.toMap(customers.get(account.customerId())) : null);
        out.put("staff", account != null && account.staffId() != null
                ? JsonMapper.toMap(staffRepo.get(account.staffId())) : null);
        out.put("preferences", account != null && account.preferences() != null
                ? account.preferences() : defaultPreferences());
        out.put("capabilities", capabilities(account == null ? Role.GUEST : account.role()));
        out.put("passwordPolicy", passwordPolicy());
        return out;
    }

    public static Map<String, Object> capabilities(Role role) {
        return Util.map(
                "role", role == null ? Role.GUEST.name() : role.name(),
                "label", role == null ? Role.GUEST.label() : role.label(),
                "browseCatalogue", true,
                "bookOnline", true,
                "payOnline", true,
                "viewPublicSearch", true,
                "manageCustomers", role != null && role.canViewAllCustomers(),
                "manageBookings", role != null && role.isStaff(),
                "takePayment", role != null && role.isStaff(),
                "refund", role != null && role.canRefund(),
                "manageInventory", role != null && role.isStaff(),
                "viewAnalytics", role != null && role.isStaff(),
                "manageStaff", role == Role.OWNER || role == Role.MANAGER,
                "manageSettings", role != null && role.canManageSettings(),
                "viewAuditLog", role == Role.OWNER || role == Role.MANAGER,
                "manageAccounts", role == Role.OWNER,
                "exportData", role != null && role.isStaff(),
                "backupRestore", role == Role.OWNER);
    }

    private static String greeting(UserAccount account) {
        int hour = LocalDateTime.now().getHour();
        String part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        return part + ", " + Util.nz(account.displayName(), account.username()) + ".";
    }

    // ------------------------------------------------------------------ passwords

    private void storeCredential(String userId, String password, String history) {
        Credential existing = credentials.firstWhere(c -> userId.equals(c.userId())).orElse(null);
        String salt = hasher.newSalt();
        String hash = hasher.hash(password, salt);
        String trail = history == null ? "" : history;
        List<String> previous = new ArrayList<>();
        for (String fragment : trail.split(",")) {
            if (Util.present(fragment)) previous.add(fragment);
        }
        if (previous.size() > 4) previous = new ArrayList<>(previous.subList(previous.size() - 4, previous.size()));
        Credential credential = new Credential(existing == null ? Util.id("crd") : existing.id(), userId,
                PasswordHasher.ALGORITHM, salt, hash, hasher.iterations(), PasswordHasher.KEY_LENGTH_BITS,
                existing == null ? LocalDateTime.now() : existing.createdAt(), LocalDateTime.now(),
                String.join(",", previous) + (previous.isEmpty() ? "" : ",") + hash.substring(0, 16));
        credentials.put(credential);
    }

    /** Cheap reuse guard: compares against the last few stored hash fragments. */
    private boolean reusedRecently(Credential credential, String candidate) {
        if (credential == null || Util.blank(credential.history())) return false;
        String probe = hasher.hash(candidate, credential.salt());
        for (String fragment : Util.nz(credential.history()).split(",")) {
            if (Util.present(fragment) && probe.startsWith(fragment)) return true;
        }
        return false;
    }

    public Map<String, Object> changePassword(String userId, String current, String next, Actor actor) {
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        Credential credential = credentials.firstWhere(c -> userId.equals(c.userId())).orElse(null);
        if (credential == null) throw ApiException.conflict("That account has no password set");
        if (!hasher.verify(Util.nz(current), credential.salt(), credential.hash(), credential.iterations())) {
            audit.security("auth.password.failed", "Wrong current password for " + account.username(),
                    actor == null ? null : actor.ip(), Map.of("userId", userId));
            throw ApiException.unauthorized("Your current password is not right");
        }
        PasswordHasher.Policy policy = hasher.evaluate(next);
        if (!policy.isAcceptable()) throw ApiException.validation(policy.problems());
        if (reusedRecently(credential, next)) {
            throw ApiException.validation(List.of("That password was used recently \u2014 pick a fresh one"));
        }
        storeCredential(userId, next, credential.history());
        accounts.put(account.withPasswordChanged());
        sessionManager.revokeAllForUser(userId, "password changed");
        audit.security("auth.password.change", account.username() + " changed their password",
                actor == null ? null : actor.ip(), Map.of("userId", userId, "algorithm", PasswordHasher.ALGORITHM));
        return Map.of("changed", true, "sessionsRevoked", true,
                "message", "Password changed. Sign in again on your other devices.");
    }

    /** In this build the reset link is returned in the response (there is no mail server). */
    public Map<String, Object> requestPasswordReset(String identifier, String ip) {
        UserAccount account = byUsername(identifier);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", true);
        out.put("message", "If that account exists, a reset link has been queued.");
        if (account == null || !account.active()) {
            audit.security("auth.reset.unknown", "Password reset requested for " + Util.nz(identifier), ip, Map.of());
            return out;
        }
        String token = Util.randomToken(24);
        resetTokens.put(account.id(), new ResetToken(token, account.id(), LocalDateTime.now().plus(RESET_TTL), false));
        out.put("expiresInMinutes", RESET_TTL.toMinutes());
        if (config.dev()) {
            out.put("devToken", token);
            out.put("devLink", "/studio.html#/profile?reset=" + token);
        }
        audit.security("auth.reset.request", "Password reset requested for " + account.username(), ip,
                Map.of("userId", account.id()));
        return out;
    }

    public Map<String, Object> confirmPasswordReset(String token, String password, String ip) {
        ResetToken record = resetTokens.values().stream()
                .filter(t -> Util.constantTimeEquals(t.token(), Util.nz(token))).findFirst().orElse(null);
        if (record == null || record.used() || record.expiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("That reset link is invalid or has expired");
        }
        PasswordHasher.Policy policy = hasher.evaluate(password);
        if (!policy.isAcceptable()) throw ApiException.validation(policy.problems());
        Credential existing = credentials.firstWhere(c -> record.userId().equals(c.userId())).orElse(null);
        storeCredential(record.userId(), password, existing == null ? null : existing.history());
        UserAccount account = accounts.get(record.userId());
        if (account != null) accounts.put(account.withPasswordChanged());
        resetTokens.remove(record.userId());
        sessionManager.revokeAllForUser(record.userId(), "password reset");
        audit.security("auth.reset.confirm", "Password reset completed", ip, Map.of("userId", record.userId()));
        return Map.of("reset", true, "message", "Password updated \u2014 you can sign in now.");
    }

    public Map<String, Object> passwordPolicy() {
        PasswordHasher.Policy sample = hasher.evaluate("Passw0rd!");
        return Util.map("algorithm", PasswordHasher.ALGORITHM, "iterations", hasher.iterations(),
                "keyLengthBits", PasswordHasher.KEY_LENGTH_BITS, "minLength", 10,
                "maxFailedAttempts", MAX_FAILED, "lockoutMinutes", LOCKOUT.toMinutes(),
                "hints", sample.hints(), "sessionIdleHours", 12, "sessionAbsoluteDays", 14, "guestSessionHours", 6);
    }

    // ------------------------------------------------------------------ profile

    public UserAccount updateProfile(String userId, Map<String, Object> body, Actor actor) {
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        Validator validator = Validator.of(body);
        String displayName = validator.optional("displayName", 80);
        String email = validator.email("email", false);
        String phone = validator.phone("phone", false);
        validator.validate("profile");

        if (Util.present(email)) {
            String key = email.toLowerCase(Locale.ROOT);
            if (!key.equals(account.email()) && accounts.firstWhere(a -> key.equals(Util.nz(a.email()).toLowerCase(Locale.ROOT))).isPresent()) {
                throw ApiException.conflict("That email is already used");
            }
            account = withEmail(account, key);
        }
        if (Util.present(displayName)) account = withDisplayName(account, Validator.sanitise(displayName));
        if (Util.present(phone)) account = withPhone(account, phone);
        accounts.put(account);
        audit.record("profile.update", "user", account.id(), account.username() + " updated their profile", actor);
        return account;
    }

    /** Theme and accessibility preferences, kept server-side so they follow the account. */
    public UserAccount updatePreferences(String userId, Map<String, Object> incoming, Actor actor) {
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        Map<String, Object> merged = new LinkedHashMap<>(account.preferences() == null ? defaultPreferences() : account.preferences());
        Map<String, Object> patch = incoming == null ? Map.of() : incoming;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            switch (key) {
                case "fontScale" -> merged.put(key, Util.clamp(JsonMapper.asInt(value, 100), 85, 150));
                case "theme", "contrast", "motion", "readingFont", "density" -> merged.put(key, Validator.sanitise(String.valueOf(value)));
                case "underlineLinks", "reduceMotion", "highContrast", "dyslexiaFont", "screenReaderHints",
                     "largeTargets", "announceChanges", "hidePrices", "soundCues" -> merged.put(key, Boolean.parseBoolean(String.valueOf(value)));
                case "language", "timezone" -> merged.put(key, Validator.sanitise(String.valueOf(value)));
                default -> { /* unknown keys are ignored rather than stored */ }
            }
        }
        UserAccount updated = account.withPreferences(merged);
        accounts.put(updated);
        audit.record("profile.preferences", "user", userId, "Preferences updated", actor, AuditEvent.INFO, merged);
        return updated;
    }

    public Map<String, Object> defaultPreferences() {
        BusinessSettings config = settings.current();
        Map<String, Object> prefs = new LinkedHashMap<>();
        Map<String, Object> theme = config.theme() == null ? Map.of() : config.theme();
        prefs.put("theme", String.valueOf(theme.getOrDefault("mode", "light")));
        prefs.put("fontScale", 100);
        prefs.put("contrast", "normal");
        prefs.put("density", "comfortable");
        prefs.put("readingFont", "editorial");
        prefs.put("underlineLinks", false);
        prefs.put("reduceMotion", false);
        prefs.put("highContrast", false);
        prefs.put("dyslexiaFont", false);
        prefs.put("largeTargets", false);
        prefs.put("screenReaderHints", true);
        prefs.put("announceChanges", true);
        prefs.put("hidePrices", false);
        prefs.put("soundCues", false);
        prefs.put("language", String.valueOf(theme.getOrDefault("language", "en-IN")));
        prefs.put("timezone", "Asia/Kolkata");
        return prefs;
    }

    /** Attaches an existing client record to an account after a phone check. */
    public Map<String, Object> linkCustomer(String userId, String phone, String code, Actor actor) {
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        Customer customer = customerService.byPhone(phone);
        if (customer == null) throw ApiException.notFound("No client record on " + Util.nz(phone));
        String expected = linkCode(customer);
        if (Util.blank(code)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("customerFound", true);
            out.put("name", customer.fullName());
            out.put("maskedPhone", Util.maskPhone(customer.phone()));
            out.put("codeRequired", true);
            out.put("message", "Enter the 6-character code the front desk reads out to link this record.");
            if (config.dev()) out.put("devCode", expected);
            return out;
        }
        if (!Util.constantTimeEquals(expected, code.trim().toUpperCase(Locale.ROOT))) {
            audit.security("auth.link.failed", "Client link rejected for " + account.username(),
                    actor == null ? null : actor.ip(), Map.of("customerId", customer.id()));
            throw ApiException.unauthorized("That code does not match");
        }
        accounts.put(withCustomer(account, customer.id()));
        sessionManager.revokeAllForUser(account.id(), "client record linked");
        audit.record("auth.link", "customer", customer.id(), account.username() + " linked to " + customer.fullName(), actor);
        return Map.of("linked", true, "customer", JsonMapper.toMap(customer),
                "message", "Signed out everywhere \u2014 sign in again to continue as " + customer.firstName() + ".");
    }

    /** Deterministic link code derived from the record, never stored. */
    public String linkCode(Customer customer) {
        return Util.hmacSha256("sillage-link", customer.id() + "|" + CustomerService.phoneKey(customer.phone()))
                .substring(0, 6).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ admin

    public List<Map<String, Object>> list(Actor actor) {
        actor.require(Role.MANAGER);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserAccount account : accounts.all()) out.add(view(account));
        out.sort((a, b) -> String.valueOf(a.getOrDefault("displayName", ""))
                .compareTo(String.valueOf(b.getOrDefault("displayName", ""))));
        return out;
    }

    public UserAccount setRole(String userId, Role role, Actor actor) {
        actor.require(Role.OWNER);
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        if (account.id().equals(actor.id()) && role != Role.OWNER) {
            throw ApiException.conflict("You cannot remove your own owner role");
        }
        if (accounts.countWhere(a -> a.role() == Role.OWNER && a.active()) <= 1 && account.role() == Role.OWNER) {
            throw ApiException.conflict("The salon needs at least one owner");
        }
        accounts.put(account.withRole(role));
        audit.security("auth.role.change", account.username() + " is now " + role.label(), actor.ip(),
                Map.of("userId", userId, "from", account.role().name(), "to", role.name()));
        return account.withRole(role);
    }

    public UserAccount setActive(String userId, boolean active, Actor actor) {
        actor.require(Role.OWNER);
        UserAccount account = accounts.get(userId);
        if (account == null) throw ApiException.notFound("Account " + Util.nz(userId));
        if (account.id().equals(actor.id()) && !active) throw ApiException.conflict("You cannot disable your own account");
        UserAccount updated = withActive(account, active);
        accounts.put(updated);
        if (!active) sessionManager.revokeAllForUser(userId, "account disabled");
        audit.security(active ? "auth.enable" : "auth.disable", account.username() + (active ? " re-enabled" : " disabled"),
                actor.ip(), Map.of("userId", userId));
        return updated;
    }

    /** Owner-created staff accounts start with a temporary password they must change. */
    public Map<String, Object> createStaffAccount(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Validator validator = Validator.of(body);
        String staffId = validator.required("staffId");
        Role role = validator.role("role", Role.STYLIST);
        String username = validator.username("username", true);
        validator.validate("staff account");
        if (role == Role.CLIENT || role == Role.GUEST) throw ApiException.validation(List.of("Pick a staff role"));
        Staff member = staffRepo.get(staffId);
        if (member == null) throw ApiException.notFound("Staff member " + staffId);
        if (byUsername(username) != null) throw ApiException.conflict("That username is taken");
        if (accounts.firstWhere(a -> staffId.equals(a.staffId())).isPresent()) {
            throw ApiException.conflict(member.name() + " already has an account");
        }
        String temporary = Util.randomToken(6) + "Aa1!";
        LocalDateTime now = LocalDateTime.now();
        UserAccount account = new UserAccount(Util.id("usr"), username.trim().toLowerCase(Locale.ROOT),
                Util.nz(member.email()).toLowerCase(Locale.ROOT), member.name(), role, null, staffId, member.phone(),
                true, Util.present(member.email()), true, 0, null, null, null, 0, defaultPreferences(), now, now);
        accounts.put(account);
        storeCredential(account.id(), temporary, null);
        audit.security("auth.account.create", actor.label() + " created an account for " + member.name(), actor.ip(),
                Map.of("userId", account.id(), "role", role.name(), "staffId", staffId));
        return Map.of("account", view(account), "temporaryPassword", temporary,
                "mustChangePassword", true, "staff", JsonMapper.toMap(member));
    }

    /**
     * Creates an account with a known password. Used by the seeder and by the staff-account flow;
     * nothing else is allowed to call it.
     */
    public UserAccount provision(String username, String displayName, String email, String phone, String password,
                                 Role role, String staffId, String customerId, boolean mustChange) {
        if (byUsername(username) != null) return byUsername(username);
        LocalDateTime now = LocalDateTime.now();
        UserAccount account = new UserAccount(Util.id("usr"), Util.nz(username).toLowerCase(Locale.ROOT),
                Util.blank(email) ? null : email.toLowerCase(Locale.ROOT), Util.nz(displayName, username),
                role == null ? Role.CLIENT : role, customerId, staffId, phone, true, false, mustChange, 0, null,
                null, null, 0, defaultPreferences(), now, now);
        accounts.put(account);
        storeCredential(account.id(), password, null);
        Log.info("provisioned %s (%s)", account.username(), account.role());
        return account;
    }

    /** Creates the first owner if the installation has none (used by the seeder). */
    public Optional<UserAccount> ensureOwner(String username, String password, String displayName) {
        if (accounts.countWhere(a -> a.role() == Role.OWNER) > 0) return Optional.empty();
        LocalDateTime now = LocalDateTime.now();
        UserAccount account = new UserAccount(Util.id("usr"), Util.nz(username, "owner").toLowerCase(Locale.ROOT),
                "owner@sillage.example", Util.nz(displayName, "The Owner"), Role.OWNER, null, null, null,
                true, true, false, 0, null, null, null, 0, defaultPreferences(), now, now);
        accounts.put(account);
        storeCredential(account.id(), Util.blank(password) ? "Sillage#2026" : password, null);
        Log.info("created the owner account %s", account.username());
        return Optional.of(account);
    }

    // ------------------------------------------------------------------ views

    public Map<String, Object> view(UserAccount account) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", account.id());
        out.put("username", account.username());
        out.put("email", Util.maskEmail(account.email()));
        out.put("emailRaw", account.email());
        out.put("displayName", account.displayName());
        out.put("initials", account.initials());
        out.put("role", account.role().name());
        out.put("roleLabel", account.role().label());
        out.put("phone", Util.maskPhone(account.phone()));
        out.put("active", account.active());
        out.put("locked", account.isLocked());
        out.put("lockedUntil", account.lockedUntil() == null ? null : account.lockedUntil().toString());
        out.put("mustChangePassword", account.mustChangePassword());
        out.put("emailVerified", account.emailVerified());
        out.put("loginCount", account.loginCount());
        out.put("lastLoginAt", account.lastLoginAt() == null ? null : account.lastLoginAt().toString());
        out.put("lastLoginIp", account.lastLoginIp());
        out.put("customerId", account.customerId());
        out.put("staffId", account.staffId());
        out.put("preferences", account.preferences());
        out.put("capabilities", capabilities(account.role()));
        out.put("activeSessions", sessions.countWhere(s -> account.id().equals(s.userId()) && !s.revoked()));
        out.put("createdAt", account.createdAt() == null ? null : account.createdAt().toString());
        return out;
    }

    private Map<String, Object> sessionView(SessionRecord session) {
        if (session == null) return Map.of("kind", "anonymous");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", session.id());
        out.put("kind", session.kind());
        out.put("role", session.role() == null ? Role.GUEST.name() : session.role().name());
        out.put("roleLabel", session.role() == null ? Role.GUEST.label() : session.role().label());
        out.put("displayName", session.displayName());
        out.put("customerId", session.customerId());
        out.put("userId", session.userId());
        out.put("csrfToken", session.csrfToken());
        out.put("createdAt", session.createdAt() == null ? null : session.createdAt().toString());
        out.put("expiresAt", session.expiresAt() == null ? null : session.expiresAt().toString());
        out.put("lastSeenAt", session.lastSeenAt() == null ? null : session.lastSeenAt().toString());
        out.put("revoked", session.revoked());
        out.put("capabilities", capabilities(session.role()));
        return out;
    }

    public List<Map<String, Object>> sessionsOf(String userId, SessionRecord current) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionRecord session : sessionManager.forUser(userId)) {
            Map<String, Object> entry = sessionView(session);
            entry.put("current", current != null && current.id().equals(session.id()));
            entry.put("userAgent", session.userAgent());
            entry.put("ip", session.ip());
            entry.put("lastSeenAt", session.lastSeenAt() == null ? null : session.lastSeenAt().toString());
            out.add(entry);
        }
        return out;
    }

    public int revokeSession(String sessionId, Actor actor) {
        SessionRecord session = sessions.get(sessionId);
        if (session == null) throw ApiException.notFound("Session " + Util.nz(sessionId));
        if (session.userId() != null && !session.userId().equals(actor.id()) && !actor.has(Role.MANAGER)) {
            throw ApiException.forbidden("That session belongs to someone else");
        }
        sessionManager.revoke(sessionId, "revoked by " + actor.label());
        audit.security("auth.session.revoke", "Session revoked", actor.ip(), Map.of("sessionId", sessionId));
        return 1;
    }

    public int revokeEverywhere(String userId, Actor actor) {
        int count = sessionManager.revokeAllForUser(userId, "signed out everywhere by " + actor.label());
        audit.security("auth.session.revokeAll", actor.label() + " signed out " + count + " sessions", actor.ip(),
                Map.of("userId", userId, "count", count));
        return count;
    }

    public int pruneSessions() { return sessionManager.prune(); }

    public String cookieHeader(String token, boolean secure) {
        return sessionManager.cookieHeader(token, secure, Duration.ofHours(6));
    }

    public String clearCookieHeader(boolean secure) {
        return sessionManager.clearCookieHeader(secure);
    }

    public Map<String, Object> stats() {
        return Util.map("accounts", accounts.size(),
                "active", accounts.countWhere(UserAccount::active),
                "staff", accounts.countWhere(a -> a.role().isStaff()),
                "clients", accounts.countWhere(a -> a.role() == Role.CLIENT),
                "owners", accounts.countWhere(a -> a.role() == Role.OWNER),
                "locked", accounts.countWhere(UserAccount::isLocked),
                "activeSessions", sessionManager.activeSessions(),
                "guestSessions", sessions.countWhere(s -> s.userId() == null && !s.revoked()),
                "hashAlgorithm", PasswordHasher.ALGORITHM, "iterations", hasher.iterations());
    }

    // ------------------------------------------------------------------ immutable setters

    private static UserAccount withEmail(UserAccount a, String email) {
        return new UserAccount(a.id(), a.username(), email, a.displayName(), a.role(), a.customerId(), a.staffId(),
                a.phone(), a.active(), a.emailVerified(), a.mustChangePassword(), a.failedAttempts(), a.lockedUntil(),
                a.lastLoginAt(), a.lastLoginIp(), a.loginCount(), a.preferences(), a.createdAt(), LocalDateTime.now());
    }

    private static UserAccount withDisplayName(UserAccount a, String name) {
        return new UserAccount(a.id(), a.username(), a.email(), name, a.role(), a.customerId(), a.staffId(),
                a.phone(), a.active(), a.emailVerified(), a.mustChangePassword(), a.failedAttempts(), a.lockedUntil(),
                a.lastLoginAt(), a.lastLoginIp(), a.loginCount(), a.preferences(), a.createdAt(), LocalDateTime.now());
    }

    private static UserAccount withPhone(UserAccount a, String phone) {
        return new UserAccount(a.id(), a.username(), a.email(), a.displayName(), a.role(), a.customerId(), a.staffId(),
                phone, a.active(), a.emailVerified(), a.mustChangePassword(), a.failedAttempts(), a.lockedUntil(),
                a.lastLoginAt(), a.lastLoginIp(), a.loginCount(), a.preferences(), a.createdAt(), LocalDateTime.now());
    }

    private static UserAccount withActive(UserAccount a, boolean active) {
        return new UserAccount(a.id(), a.username(), a.email(), a.displayName(), a.role(), a.customerId(), a.staffId(),
                a.phone(), active, a.emailVerified(), a.mustChangePassword(), a.failedAttempts(), a.lockedUntil(),
                a.lastLoginAt(), a.lastLoginIp(), a.loginCount(), a.preferences(), a.createdAt(), LocalDateTime.now());
    }
}
