package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Category;
import com.sillage.model.Look;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.model.Testimonial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The service menu: categories, bookable services with priced tiers, the editorial lookbook and
 * published client quotes.
 */
public final class CatalogueService {

    private final Repo<ServiceItem> services;
    private final Repo<Category> categories;
    private final Repo<Look> looks;
    private final Repo<Testimonial> testimonials;
    private final Repo<Staff> staff;
    private final SearchService search;

    public CatalogueService(Repo<ServiceItem> services, Repo<Category> categories, Repo<Look> looks,
                            Repo<Testimonial> testimonials, Repo<Staff> staff, SearchService search) {
        this.services = services;
        this.categories = categories;
        this.looks = looks;
        this.testimonials = testimonials;
        this.staff = staff;
        this.search = search;
    }

    public Repo<ServiceItem> repo() { return services; }

    // ------------------------------------------------------------------ reads

    public List<ServiceItem> allServices() {
        List<ServiceItem> out = services.all();
        out.sort(Comparator.comparing(s -> Util.nz(s.name()).toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<ServiceItem> bookableServices() {
        List<ServiceItem> out = services.where(s -> s.active() && s.onlineBookable());
        out.sort(Comparator.comparing(s -> Util.nz(s.name()).toLowerCase(Locale.ROOT)));
        return out;
    }

    public ServiceItem service(String id) {
        ServiceItem item = services.get(id);
        if (item == null) throw ApiException.notFound("Service " + Util.nz(id));
        return item;
    }

    public ServiceItem findService(String id) { return services.get(id); }

    public List<Category> allCategories() {
        List<Category> out = categories.all();
        out.sort(Comparator.comparingInt(Category::order).thenComparing(c -> Util.nz(c.name())));
        return out;
    }

    public Category category(String id) {
        Category category = categories.get(id);
        if (category == null) throw ApiException.notFound("Category " + Util.nz(id));
        return category;
    }

    public String categoryName(String id) {
        Category category = categories.get(id);
        return category == null ? "Studio" : category.name();
    }

    public String staffName(String id) {
        Staff member = staff.get(id);
        return member == null ? "Any available" : member.name();
    }

    /** The public menu: categories with their services, plus featured looks. */
    public Map<String, Object> menu(boolean publicOnly) {
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (Category category : allCategories()) {
            List<ServiceItem> items = services.where(s -> Util.nz(s.categoryId()).equals(category.id())
                    && s.active() && (!publicOnly || s.onlineBookable()));
            if (items.isEmpty() && publicOnly) continue;
            items.sort(Comparator.comparing(ServiceItem::name));
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ServiceItem item : items) entries.add(publicService(item));
            Map<String, Object> chapter = new LinkedHashMap<>();
            chapter.put("id", category.id());
            chapter.put("name", category.name());
            chapter.put("blurb", category.blurb());
            chapter.put("accent", Util.nz(category.accent(), "stone"));
            chapter.put("count", entries.size());
            chapter.put("services", entries);
            chapter.put("from", items.stream().map(ServiceItem::price).min(Money::compare).orElse(BigDecimal.ZERO));
            chapters.add(chapter);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chapters", chapters);
        out.put("total", chapters.stream().mapToInt(c -> (int) c.get("count")).sum());
        out.put("featured", services.where(ServiceItem::featured).stream().limit(6).map(this::publicService).toList());
        out.put("looks", publicLooks(6));
        out.put("index", chapters.stream().map(c -> Util.map("id", c.get("id"), "name", c.get("name"), "count", c.get("count"), "from", c.get("from"))).toList());
        return out;
    }

    public Map<String, Object> publicService(ServiceItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.id());
        out.put("code", item.code());
        out.put("name", item.name());
        out.put("categoryId", item.categoryId());
        out.put("category", categoryName(item.categoryId()));
        out.put("tagline", item.tagline());
        out.put("description", item.description());
        out.put("editorial", item.editorial());
        out.put("durationMinutes", item.durationMinutes());
        out.put("duration", Util.humanDuration(item.durationMinutes()));
        out.put("price", item.price());
        out.put("priceLabel", Money.inr(item.price()));
        out.put("variants", item.variants() == null ? List.of() : item.variants().stream()
                .map(v -> Util.map("id", v.id(), "label", v.label(), "extraMinutes", v.extraMinutes(),
                        "extraPrice", v.extraPrice(), "total", v.totalFor(item.price()),
                        "note", v.note())).toList());
        out.put("tags", item.tags());
        out.put("prepNotes", item.prepNotes());
        out.put("aftercareNotes", item.aftercareNotes());
        out.put("imageKey", item.imageKey());
        out.put("featured", item.featured());
        out.put("rating", item.rating());
        out.put("timesBooked", item.timesBooked());
        out.put("stylists", item.staffIds() == null || item.staffIds().isEmpty()
                ? List.of()
                : item.staffIds().stream().map(this::staffName).filter(n -> !n.equals("Any available")).toList());
        out.put("staffIds", item.staffIds());
        return out;
    }

    /** Full record plus derived context, for the studio's catalogue editor. */
    public Map<String, Object> detail(String id) {
        ServiceItem item = service(id);
        Map<String, Object> out = JsonMapper.toMap(item);
        out.put("category", categoryName(item.categoryId()));
        out.put("performers", item.staffIds() == null ? List.of() : item.staffIds().stream()
                .map(staff::get).filter(java.util.Objects::nonNull)
                .map(s -> Util.map("id", s.id(), "name", s.name(), "title", s.title())).toList());
        out.put("looks", looks.where(l -> l.serviceIds() != null && l.serviceIds().contains(id)).stream()
                .map(l -> Util.map("id", l.id(), "title", l.title(), "season", l.season())).toList());
        out.put("bookings30d", 0);
        return out;
    }

    public List<Look> allLooks() {
        List<Look> out = looks.all();
        out.sort(Comparator.comparing(l -> Util.nz(l.season())));
        return out;
    }

    public List<Map<String, Object>> publicLooks(int limit) {
        List<Look> featured = looks.where(Look::active);
        featured.sort(Comparator.comparing(Look::featured).reversed().thenComparing(l -> Util.nz(l.title())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Look look : featured) {
            if (out.size() >= limit) break;
            Map<String, Object> entry = JsonMapper.toMap(look);
            entry.put("stylist", staffName(look.stylistId()));
            entry.put("services", look.serviceIds() == null ? List.of() : look.serviceIds().stream()
                    .map(services::get).filter(java.util.Objects::nonNull)
                    .map(s -> Util.map("id", s.id(), "name", s.name(), "price", s.price())).toList());
            out.add(entry);
        }
        return out;
    }

    public List<Testimonial> quotes(boolean onlyApproved, int limit) {
        List<Testimonial> out = testimonials.where(t -> !onlyApproved || t.approved());
        out.sort(Comparator.comparing(Testimonial::featured).reversed()
                .thenComparing(t -> t.date() == null ? LocalDate.MIN : t.date(), Comparator.reverseOrder()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<ServiceItem> all = services.all();
        out.put("services", all.size());
        out.put("active", all.stream().filter(ServiceItem::active).count());
        out.put("onlineBookable", all.stream().filter(s -> s.active() && s.onlineBookable()).count());
        out.put("categories", categories.size());
        out.put("looks", looks.size());
        out.put("testimonials", testimonials.size());
        out.put("averagePrice", all.isEmpty() ? BigDecimal.ZERO
                : Money.scale(all.stream().map(ServiceItem::price).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(all.size()), 2, java.math.RoundingMode.HALF_UP)));
        out.put("averageDuration", all.isEmpty() ? 0
                : Math.round(all.stream().mapToInt(ServiceItem::durationMinutes).average().orElse(0)));
        return out;
    }

    // ------------------------------------------------------------------ writes

    public ServiceItem createService(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Map<String, Object> merged = new LinkedHashMap<>(body == null ? Map.of() : body);
        merged.putIfAbsent("id", Util.id("svc"));
        merged.putIfAbsent("createdAt", LocalDateTime.now().toString());
        merged.putIfAbsent("active", true);
        merged.putIfAbsent("onlineBookable", true);
        ServiceItem item = normalise(merged);
        if (services.get(item.id()) != null) throw ApiException.conflict("A service with that id already exists");
        if (Util.present(item.code()) && services.firstWhere(s -> item.code().equalsIgnoreCase(s.code())).isPresent()) {
            throw ApiException.conflict("Service code " + item.code() + " is already used");
        }
        services.put(item);
        search.index(item);
        return item;
    }

    public ServiceItem updateService(String id, Map<String, Object> patch, Actor actor) {
        actor.require(Role.MANAGER);
        ServiceItem existing = service(id);
        Map<String, Object> merged = JsonMapper.merge(JsonMapper.toMap(existing), patch);
        merged.put("id", existing.id());
        merged.put("createdAt", existing.createdAt());
        merged.put("updatedAt", LocalDateTime.now().toString());
        merged.put("timesBooked", existing.timesBooked());
        ServiceItem updated = normalise(merged);
        if (Util.present(updated.code())) {
            ServiceItem clash = services.firstWhere(s -> updated.code().equalsIgnoreCase(s.code()) && !s.id().equals(id)).orElse(null);
            if (clash != null) throw ApiException.conflict("Service code " + updated.code() + " belongs to " + clash.name());
        }
        services.put(updated);
        search.index(updated);
        return updated;
    }

    public void deleteService(String id, Actor actor) {
        actor.require(Role.OWNER);
        ServiceItem item = service(id);
        services.remove(id);
        search.remove("service", id);
        for (Look look : looks.where(l -> l.serviceIds() != null && l.serviceIds().contains(id))) {
            List<String> remaining = new ArrayList<>(look.serviceIds());
            remaining.remove(id);
            looks.put(new Look(look.id(), look.title(), look.season(), look.stylistId(), remaining, look.palette(),
                    look.notes(), look.story(), look.imageKey(), look.minutes(), look.featured(), look.active(), look.createdAt()));
            search.index(look);
        }
        Util.nz(item.name());
    }

    public ServiceItem setFeatured(String id, boolean featured, Actor actor) {
        actor.require(Role.MANAGER);
        ServiceItem item = service(id);
        ServiceItem updated = new ServiceItem(item.id(), item.code(), item.name(), item.categoryId(), item.tagline(),
                item.description(), item.editorial(), item.durationMinutes(), item.price(), item.variants(),
                item.staffIds(), item.tags(), item.prepNotes(), item.aftercareNotes(), item.imageKey(), item.active(),
                item.onlineBookable(), featured, item.timesBooked(), item.rating(), item.createdAt(),
                LocalDateTime.now().toString());
        services.put(updated);
        search.index(updated);
        return updated;
    }

    public ServiceItem recordBooking(String serviceId) {
        ServiceItem item = services.get(serviceId);
        if (item == null) return null;
        ServiceItem updated = item.withBookingCount(item.timesBooked() + 1).withUpdated(LocalDateTime.now().toString());
        services.put(updated);
        search.index(updated);
        return updated;
    }

    public ServiceItem recordRating(String serviceId, int rating) {
        ServiceItem item = services.get(serviceId);
        if (item == null) return null;
        int count = Math.max(1, item.timesBooked());
        double updated = (item.rating() * (count - 1) + rating) / count;
        ServiceItem next = item.withRating(Math.round(Math.min(5, Math.max(0, updated)) * 10) / 10.0);
        services.put(next);
        search.index(next);
        return next;
    }

    private ServiceItem normalise(Map<String, Object> merged) {
        ServiceItem item;
        try {
            item = JsonMapper.from(merged, ServiceItem.class, null);
        } catch (RuntimeException e) {
            throw new ApiException(422, "VALIDATION", "That service could not be read: " + e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        if (Util.blank(item.name())) problems.add("A service needs a name");
        if (item.name() != null && item.name().length() > 90) problems.add("Keep the name under 90 characters");
        if (item.durationMinutes() < 5 || item.durationMinutes() > 600) problems.add("Duration must be between 5 and 600 minutes");
        if (item.price() == null || item.price().signum() < 0) problems.add("Price cannot be negative");
        if (item.price() != null && item.price().compareTo(new BigDecimal("1000000")) > 0) problems.add("Price looks unrealistic");
        if (!problems.isEmpty()) throw ApiException.validation(problems);

        String id = Util.nz(item.id(), Util.id("svc"));
        String code = Util.blank(item.code()) ? "S" + String.format("%04d", Math.abs(id.hashCode() % 10000))
                : item.code().trim().toUpperCase(Locale.ROOT);
        List<ServiceItem.Variant> variants = new ArrayList<>();
        if (item.variants() != null) {
            int n = 0;
            for (ServiceItem.Variant variant : item.variants()) {
                if (variant == null || Util.blank(variant.label())) continue;
                variants.add(new ServiceItem.Variant(Util.nz(variant.id(), "v" + (++n)),
                        com.sillage.security.Validator.sanitise(variant.label()),
                        Math.max(0, variant.extraMinutes()),
                        Money.scale(variant.extraPrice() == null ? BigDecimal.ZERO : variant.extraPrice()),
                        com.sillage.security.Validator.sanitise(variant.note())));
            }
        }
        String categoryId = item.categoryId();
        if (Util.present(categoryId) && categories.get(categoryId) == null) {
            Category first = allCategories().stream().findFirst().orElse(null);
            categoryId = first == null ? null : first.id();
        }
        return new ServiceItem(id, code,
                com.sillage.security.Validator.sanitise(item.name().trim()),
                categoryId,
                com.sillage.security.Validator.sanitise(item.tagline()),
                com.sillage.security.Validator.sanitise(item.description()),
                com.sillage.security.Validator.sanitise(item.editorial()),
                item.durationMinutes(),
                Money.scale(item.price()),
                variants,
                cleanIds(item.staffIds()),
                cleanTags(item.tags()),
                com.sillage.security.Validator.sanitise(item.prepNotes()),
                com.sillage.security.Validator.sanitise(item.aftercareNotes()),
                slugKey(item.imageKey()),
                item.active(), item.onlineBookable(), item.featured(),
                Math.max(0, item.timesBooked()),
                Math.min(5, Math.max(0, item.rating())),
                Util.nz(item.createdAt(), LocalDateTime.now().toString()),
                Util.nz(item.updatedAt(), LocalDateTime.now().toString()));
    }

    // ------------------------------------------------------------------ categories

    public Category createCategory(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Category category = JsonMapper.from(body, Category.class, null);
        if (Util.blank(category.name())) throw ApiException.validation(List.of("A category needs a name"));
        String id = Util.nz(category.id(), Util.id("cat"));
        Category saved = new Category(id, com.sillage.security.Validator.sanitise(category.name().trim()),
                com.sillage.security.Validator.sanitise(category.blurb()),
                Util.nz(category.accent(), "stone"),
                category.order() == 0 ? categories.size() + 1 : category.order(),
                true, cleanTags(category.tags()));
        categories.put(saved);
        return saved;
    }

    public Category updateCategory(String id, Map<String, Object> patch, Actor actor) {
        actor.require(Role.MANAGER);
        Category existing = category(id);
        Category merged = JsonMapper.from(JsonMapper.merge(JsonMapper.toMap(existing), patch), Category.class, null);
        Category saved = new Category(existing.id(), com.sillage.security.Validator.sanitise(Util.nz(merged.name(), existing.name())),
                com.sillage.security.Validator.sanitise(merged.blurb()), Util.nz(merged.accent(), existing.accent()),
                merged.order(), merged.active(), cleanTags(merged.tags()));
        categories.put(saved);
        return saved;
    }

    public void deleteCategory(String id, Actor actor) {
        actor.require(Role.OWNER);
        category(id);
        long used = services.countWhere(s -> id.equals(s.categoryId()));
        if (used > 0) throw ApiException.conflict("Move or delete the " + used + " service(s) in this category first");
        categories.remove(id);
    }

    // ------------------------------------------------------------------ lookbook & quotes

    public Look createLook(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Look look = JsonMapper.from(body, Look.class, null);
        if (Util.blank(look.title())) throw ApiException.validation(List.of("A look needs a title"));
        String id = Util.nz(look.id(), Util.id("look"));
        Look saved = new Look(id, com.sillage.security.Validator.sanitise(look.title().trim()),
                Util.nz(look.season(), seasonOf(LocalDate.now())), look.stylistId(), cleanIds(look.serviceIds()),
                cleanTags(look.palette()), com.sillage.security.Validator.sanitise(look.notes()),
                com.sillage.security.Validator.sanitise(look.story()), slugKey(look.imageKey()),
                Math.max(0, look.minutes()), look.featured(), true,
                Util.nz(look.createdAt(), LocalDateTime.now().toString()));
        looks.put(saved);
        search.index(saved);
        return saved;
    }

    public Look updateLook(String id, Map<String, Object> patch, Actor actor) {
        actor.require(Role.MANAGER);
        Look existing = looks.get(id);
        if (existing == null) throw ApiException.notFound("Look");
        Look merged = JsonMapper.from(JsonMapper.merge(JsonMapper.toMap(existing), patch), Look.class, null);
        looks.put(merged);
        search.index(merged);
        return merged;
    }

    public void deleteLook(String id, Actor actor) {
        actor.require(Role.MANAGER);
        looks.remove(id);
        search.remove("look", id);
    }

    public Testimonial createTestimonial(Map<String, Object> body, Actor actor) {
        Testimonial quote = JsonMapper.from(body, Testimonial.class, null);
        if (Util.blank(quote.authorName()) || Util.blank(quote.quote())) {
            throw ApiException.validation(List.of("A quote needs an author and the quote itself"));
        }
        boolean staffSubmission = actor != null && actor.isStaff();
        String id = Util.nz(quote.id(), Util.id("tst"));
        Testimonial saved = new Testimonial(id,
                com.sillage.security.Validator.sanitise(quote.authorName().trim()),
                com.sillage.security.Validator.sanitise(quote.handle()),
                com.sillage.security.Validator.sanitise(quote.quote().trim()),
                Math.min(5, Math.max(1, quote.rating() == 0 ? 5 : quote.rating())),
                cleanIds(quote.serviceIds()), quote.staffId(),
                quote.date() == null ? LocalDate.now() : quote.date(),
                staffSubmission || quote.approved(), quote.featured(),
                Util.nz(quote.source(), staffSubmission ? "desk" : "web"),
                Util.nz(quote.createdAt(), LocalDateTime.now().toString()));
        testimonials.put(saved);
        return saved;
    }

    public Testimonial moderateTestimonial(String id, boolean approved, boolean featured, Actor actor) {
        actor.require(Role.MANAGER);
        Testimonial existing = testimonials.get(id);
        if (existing == null) throw ApiException.notFound("Testimonial");
        Testimonial saved = new Testimonial(existing.id(), existing.authorName(), existing.handle(), existing.quote(),
                existing.rating(), existing.serviceIds(), existing.staffId(), existing.date(), approved, featured,
                existing.source(), existing.createdAt());
        testimonials.put(saved);
        return saved;
    }

    public void deleteTestimonial(String id, Actor actor) {
        actor.require(Role.MANAGER);
        testimonials.remove(id);
    }

    // ------------------------------------------------------------------ helpers

    public static List<String> cleanIds(List<String> values) {
        if (values == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    public static List<String> cleanTags(List<String> values) {
        if (values == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            String cleaned = com.sillage.security.Validator.sanitise(value.trim());
            if (cleaned == null || cleaned.isEmpty()) continue;
            if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
            if (!out.contains(cleaned)) out.add(cleaned);
            if (out.size() >= 12) break;
        }
        return out;
    }

    public static String slugKey(String value) {
        if (Util.blank(value)) return null;
        String slug = Util.slugify(value);
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    public static String seasonOf(LocalDate date) {
        int month = date.getMonthValue();
        if (month >= 3 && month <= 5) return "Spring " + date.getYear();
        if (month >= 6 && month <= 8) return "Monsoon " + date.getYear();
        if (month >= 9 && month <= 11) return "Autumn " + date.getYear();
        return "Winter " + date.getYear();
    }
}
