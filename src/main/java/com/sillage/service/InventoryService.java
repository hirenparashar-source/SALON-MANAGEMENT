package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.AuditEvent;
import com.sillage.model.Invoice;
import com.sillage.model.Product;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.StockMovement;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Retail and consumable stock, with an immutable movement ledger behind every quantity. */
public final class InventoryService {

    private final Repo<Product> products;
    private final Repo<StockMovement> movements;
    private final Repo<ServiceItem> services;
    private final SearchService search;
    private final AuditService audit;

    public InventoryService(Repo<Product> products, Repo<StockMovement> movements, Repo<ServiceItem> services,
                            SearchService search, AuditService audit) {
        this.products = products;
        this.movements = movements;
        this.services = services;
        this.search = search;
        this.audit = audit;
    }

    public Repo<Product> repo() { return products; }

    public List<Product> all() {
        List<Product> out = products.all();
        out.sort(Comparator.comparing(p -> Util.nz(p.name()).toLowerCase(Locale.ROOT)));
        return out;
    }

    public Product get(String id) {
        Product product = products.get(id);
        if (product == null) throw ApiException.notFound("Product " + Util.nz(id));
        return product;
    }

    public Product find(String id) { return products.get(id); }

    public Product bySku(String sku) {
        if (Util.blank(sku)) return null;
        return products.firstWhere(p -> sku.equalsIgnoreCase(p.sku())).orElse(null);
    }

    public List<Product> lowStock() {
        List<Product> out = products.where(p -> p.active() && p.isLow());
        out.sort(Comparator.comparingInt(Product::stock));
        return out;
    }

    public Map<String, Object> stats() {
        List<Product> all = products.all();
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal retail = BigDecimal.ZERO;
        int low = 0;
        int out = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (Product product : all) {
            cost = Money.add(cost, product.stockValue());
            retail = Money.add(retail, product.retailValue());
            if (product.isOut()) out++;
            else if (product.isLow()) low++;
            byCategory.merge(Util.nz(product.category(), "Other"), 1, Integer::sum);
        }
        int movements30 = (int) movements.countWhere(m -> m.at() != null && m.at().isAfter(LocalDateTime.now().minusDays(30)));
        return Util.map(
                "products", all.size(),
                "active", all.stream().filter(Product::active).count(),
                "stockValue", cost,
                "retailValue", retail,
                "potentialMargin", Money.subtract(retail, cost),
                "lowStock", low,
                "outOfStock", out,
                "byCategory", byCategory,
                "movements30d", movements30);
    }

    public List<StockMovement> movements(String productId, int limit) {
        List<StockMovement> out = productId == null ? movements.all()
                : movements.where(m -> productId.equals(m.productId()));
        out.sort(Comparator.comparing(StockMovement::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ------------------------------------------------------------------ writes

    public Product create(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Map<String, Object> merged = new LinkedHashMap<>(body == null ? Map.of() : body);
        merged.putIfAbsent("id", Util.id("prd"));
        merged.put("createdAt", LocalDateTime.now().toString());
        merged.putIfAbsent("active", true);
        Product product = normalise(JsonMapper.from(merged, Product.class, null), null);
        if (Util.present(product.sku()) && bySku(product.sku()) != null) {
            throw ApiException.conflict("SKU " + product.sku() + " already exists");
        }
        products.put(product);
        search.index(product);
        if (product.stock() != 0) {
            ledger(product, product.stock(), StockMovement.PURCHASE, null, "Opening stock", actor);
        }
        audit.record("inventory.create", "product", product.id(), "Added " + product.name(), actor);
        return product;
    }

    public Product update(String id, Map<String, Object> patch, Actor actor) {
        actor.require(Role.MANAGER);
        Product existing = get(id);
        Map<String, Object> merged = JsonMapper.merge(JsonMapper.toMap(existing), patch);
        merged.put("id", existing.id());
        merged.put("createdAt", existing.createdAt());
        merged.put("updatedAt", LocalDateTime.now().toString());
        boolean stockChanged = patch != null && patch.containsKey("stock")
                && JsonMapper.asInt(patch.get("stock"), existing.stock()) != existing.stock();
        if (stockChanged) merged.put("stock", existing.stock()); // stock only moves through the ledger
        Product product = normalise(JsonMapper.from(merged, Product.class, null), existing);
        products.put(product);
        search.index(product);
        if (stockChanged) {
            int target = JsonMapper.asInt(patch.get("stock"), existing.stock());
            adjust(id, target - existing.stock(), StockMovement.CORRECTION, null, "Corrected at the desk", actor);
        }
        audit.record("inventory.update", "product", id, "Updated " + product.name(), actor);
        return product;
    }

    public void delete(String id, Actor actor) {
        actor.require(Role.OWNER);
        Product product = get(id);
        products.remove(id);
        search.remove("product", id);
        audit.record("inventory.delete", "product", id, "Removed " + product.name(), actor, AuditEvent.WARN, null);
    }

    /** Applies a stock delta and writes the ledger entry. Returns the updated product. */
    public Product adjust(String id, int delta, String reason, String refId, String note, Actor actor) {
        Product product = get(id);
        if (delta == 0) return product;
        int updated = product.stock() + delta;
        if (updated < 0) {
            throw ApiException.conflict("Only " + product.stock() + " " + Util.nz(product.unit(), "unit")
                    + "(s) of " + product.name() + " are on the shelf");
        }
        Product saved = product.withStock(updated);
        products.put(saved);
        search.index(saved);
        ledger(saved, delta, reason, refId, note, actor);
        return saved;
    }

    private void ledger(Product product, int delta, String reason, String refId, String note, Actor actor) {
        movements.put(new StockMovement(Util.id("mov"), product.id(), delta, Util.nz(reason, StockMovement.CORRECTION),
                refId, com.sillage.security.Validator.sanitise(note), LocalDateTime.now(),
                actor == null ? null : actor.id(), actor == null ? "system" : actor.label(), product.stock()));
    }

    /**
     * Consumes stock for a settled invoice: retail lines decrement by quantity, and service lines
     * decrement the consumables linked to them by their per-service usage.
     */
    public List<Map<String, Object>> consumeForInvoice(Invoice invoice, Actor actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            if (Invoice.Line.PRODUCT.equals(line.kind()) && Util.present(line.refId())) {
                Product product = products.get(line.refId());
                if (product == null) continue;
                int qty = Math.max(1, line.qty());
                int target = Math.max(0, product.stock() - qty);
                Product saved = product.withStock(target).withSold(product.soldCount() + qty);
                products.put(saved);
                search.index(saved);
                ledger(saved, -(qty), StockMovement.SALE, invoice.id(), "Sold on " + invoice.number(), actor);
                out.add(Util.map("product", saved.name(), "delta", -qty, "stock", saved.stock(), "reason", "sale"));
            }
        }
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            if (!Invoice.Line.SERVICE.equals(line.kind()) || !Util.present(line.refId())) continue;
            ServiceItem service = services.get(line.refId());
            if (service == null) continue;
            for (Product consumable : products.where(p -> p.usagePerService() > 0
                    && p.linkedServiceIds() != null && p.linkedServiceIds().contains(service.id()))) {
                int units = (int) Math.ceil(consumable.usagePerService() * Math.max(1, line.qty()));
                if (units <= 0 || consumable.stock() <= 0) continue;
                int target = Math.max(0, consumable.stock() - units);
                Product saved = consumable.withStock(target);
                products.put(saved);
                search.index(saved);
                ledger(saved, -(units), StockMovement.SERVICE_USAGE, invoice.id(),
                        "Used for " + service.name(), actor);
                out.add(Util.map("product", saved.name(), "delta", -units, "stock", saved.stock(), "reason", "service usage"));
            }
        }
        return out;
    }

    public Product receive(String id, int qty, BigDecimal costPrice, String supplier, Actor actor) {
        actor.require(Role.MANAGER);
        Product product = get(id);
        if (qty <= 0) throw ApiException.validation(List.of("Received quantity must be positive"));
        Map<String, Object> patch = new LinkedHashMap<>();
        if (costPrice != null && costPrice.signum() > 0) patch.put("costPrice", costPrice);
        if (Util.present(supplier)) patch.put("supplier", supplier);
        if (!patch.isEmpty()) {
            product = normalise(JsonMapper.from(JsonMapper.merge(JsonMapper.toMap(product), patch), Product.class, null), product);
            products.put(product);
        }
        Product saved = adjust(id, qty, StockMovement.PURCHASE, null, "Received from " + Util.nz(supplier, product.supplier()), actor);
        audit.record("inventory.receive", "product", id, "Received " + qty + " \u00d7 " + saved.name(), actor);
        return saved;
    }

    private Product normalise(Product product, Product existing) {
        List<String> problems = new ArrayList<>();
        if (Util.blank(product.name())) problems.add("A product needs a name");
        if (product.price() == null || product.price().signum() < 0) problems.add("Retail price cannot be negative");
        if (product.costPrice() != null && product.costPrice().signum() < 0) problems.add("Cost price cannot be negative");
        if (product.stock() < 0) problems.add("Stock cannot be negative");
        if (product.reorderLevel() < 0) problems.add("Reorder level cannot be negative");
        if (product.taxPct() != null && (product.taxPct().signum() < 0 || product.taxPct().compareTo(new BigDecimal("40")) > 0)) {
            problems.add("Tax rate must be between 0% and 40%");
        }
        if (!problems.isEmpty()) throw ApiException.validation(problems);

        String id = Util.nz(product.id(), Util.id("prd"));
        String sku = Util.blank(product.sku())
                ? "SKU-" + id.substring(Math.max(0, id.length() - 5)).toUpperCase(Locale.ROOT)
                : product.sku().trim().toUpperCase(Locale.ROOT);
        return new Product(id, sku,
                com.sillage.security.Validator.sanitise(product.name().trim()),
                com.sillage.security.Validator.sanitise(product.brand()),
                com.sillage.security.Validator.sanitise(Util.nz(product.category(), "Retail")),
                com.sillage.security.Validator.sanitise(product.description()),
                Money.scale(product.price()), Money.scale(product.costPrice()),
                product.taxPct() == null ? new BigDecimal("18") : Money.scale(product.taxPct()),
                Math.max(0, product.stock()), Math.max(0, product.reorderLevel()),
                product.reorderQty() <= 0 ? Math.max(6, product.reorderLevel() * 2) : product.reorderQty(),
                Util.nz(product.unit(), "unit"),
                com.sillage.security.Validator.sanitise(product.supplier()),
                product.supplierPhone(),
                CatalogueService.cleanTags(product.tags()),
                CatalogueService.slugKey(product.imageKey()),
                Math.max(0, product.usagePerService()),
                CatalogueService.cleanIds(product.linkedServiceIds()),
                product.active(), product.retailOnly(), Math.max(0, product.soldCount()),
                product.createdAt() == null ? LocalDateTime.now() : product.createdAt(),
                product.updatedAt() == null ? LocalDateTime.now() : product.updatedAt());
    }

    public String toCsv(List<Product> list) {
        List<List<String>> rows = new ArrayList<>();
        for (Product p : list) {
            rows.add(List.of(Util.nz(p.sku()), Util.nz(p.name()), Util.nz(p.brand()), Util.nz(p.category()),
                    p.price() == null ? "0" : p.price().toPlainString(),
                    p.costPrice() == null ? "0" : p.costPrice().toPlainString(),
                    p.taxPct() == null ? "0" : p.taxPct().toPlainString(),
                    String.valueOf(p.stock()), String.valueOf(p.reorderLevel()), Util.nz(p.unit()),
                    Util.nz(p.supplier()), String.valueOf(p.active())));
        }
        return Util.toCsv(List.of("sku", "name", "brand", "category", "price", "cost_price", "tax_pct",
                "stock", "reorder_level", "unit", "supplier", "active"), rows);
    }
}
