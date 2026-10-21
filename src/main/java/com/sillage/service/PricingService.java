package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.Product;
import com.sillage.model.PromoCode;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a cart into money: line amounts, promotions, loyalty redemption, GST split and rounding.
 *
 * <p>Every figure is explained. The {@code explain} block lists each step with its input and
 * output so the desk can answer "why is this ₹2,949.90?" without opening a spreadsheet, and so the
 * receipt can be reproduced exactly from the invoice record.
 */
public final class PricingService {

    /** One priced line before it becomes an {@link Invoice.Line}. */
    public record QuoteLine(String kind, String refId, String name, String detail, int qty,
                            BigDecimal unitPrice, BigDecimal taxPct, BigDecimal discount,
                            BigDecimal amount, String staffId) {

        public Invoice.Line toInvoiceLine() {
            return new Invoice.Line(Util.id("ln"), kind, refId, name, detail, qty, unitPrice, taxPct, discount, amount, staffId);
        }
    }

    /** A fully worked-out total, ready to be printed or persisted. */
    public record Quote(List<QuoteLine> lines,
                        BigDecimal subtotal,
                        BigDecimal discount,
                        String promoCode,
                        BigDecimal loyaltyRedemption,
                        int pointsRedeemed,
                        BigDecimal gratuity,
                        BigDecimal taxable,
                        BigDecimal cgst,
                        BigDecimal sgst,
                        BigDecimal taxTotal,
                        BigDecimal roundOff,
                        BigDecimal total,
                        Map<String, Object> explain) {}

    private final SettingsService settings;
    private final LoyaltyService loyalty;
    private final RepoHolder repos;

    /** Small indirection so pricing does not depend on every repository type directly. */
    public interface RepoHolder {
        ServiceItem service(String id);
        Product product(String id);
        Staff staff(String id);
        PromoCode promo(String code);
    }

    public PricingService(SettingsService settings, LoyaltyService loyalty, RepoHolder repos) {
        this.settings = settings;
        this.loyalty = loyalty;
        this.repos = repos;
    }

    /**
     * @param cart keys: {@code items} (services), {@code products}, {@code discount},
     *             {@code promoCode}, {@code loyaltyPoints}, {@code gratuity}, {@code staffId}
     */
    public Quote quote(Map<String, Object> cart, Customer customer) {
        BusinessSettings config = settings.current();
        List<QuoteLine> lines = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        String defaultStaff = com.sillage.core.JsonMapper.asText(cart.get("staffId"));

        for (Map<String, Object> entry : objectList(cart.get("items"))) {
            String serviceId = com.sillage.core.JsonMapper.asText(entry.get("serviceId"));
            ServiceItem service = repos.service(serviceId);
            if (service == null) throw ApiException.notFound("Service " + Util.nz(serviceId));
            String variantId = com.sillage.core.JsonMapper.asText(entry.get("variantId"));
            if (Util.present(variantId) && service.variantLabel(variantId) == null) variantId = null;
            int qty = Math.max(1, com.sillage.core.JsonMapper.asInt(entry.get("qty"), 1));
            BigDecimal unit = service.priceWith(variantId);
            String label = service.name() + (variantId == null ? "" : " \u00b7 " + service.variantLabel(variantId));
            String staffId = Util.nz(com.sillage.core.JsonMapper.asText(entry.get("staffId")), defaultStaff);
            lines.add(new QuoteLine(Invoice.Line.SERVICE, service.id(), label,
                    Util.humanDuration(service.durationWith(variantId)) + (staffName(staffId) == null ? "" : " \u00b7 " + staffName(staffId)),
                    qty, unit, config.defaultTaxPct(), BigDecimal.ZERO, Money.multiply(unit, qty), staffId));
        }

        for (Map<String, Object> entry : objectList(cart.get("addons"))) {
            String name = com.sillage.core.JsonMapper.asText(entry.get("name"));
            BigDecimal unit = Money.of(entry.get("price"));
            if (Util.blank(name) || unit.signum() <= 0) continue;
            int qty = Math.max(1, com.sillage.core.JsonMapper.asInt(entry.get("qty"), 1));
            lines.add(new QuoteLine(Invoice.Line.ADDON, null, com.sillage.security.Validator.sanitise(name),
                    "Add-on", qty, unit, config.defaultTaxPct(), BigDecimal.ZERO, Money.multiply(unit, qty), defaultStaff));
        }

        for (Map<String, Object> entry : objectList(cart.get("products"))) {
            String productId = com.sillage.core.JsonMapper.asText(entry.get("productId"));
            Product product = repos.product(productId);
            if (product == null) throw ApiException.notFound("Product " + Util.nz(productId));
            int qty = Math.max(1, com.sillage.core.JsonMapper.asInt(entry.get("qty"), 1));
            BigDecimal unit = product.price() == null ? BigDecimal.ZERO : product.price();
            BigDecimal taxPct = product.taxPct() == null ? config.defaultTaxPct() : product.taxPct();
            lines.add(new QuoteLine(Invoice.Line.PRODUCT, product.id(), product.name(),
                    Util.nz(product.brand()) + " \u00b7 " + Util.nz(product.unit(), "unit"), qty, unit, taxPct,
                    BigDecimal.ZERO, Money.multiply(unit, qty), defaultStaff));
        }

        BigDecimal gratuity = Money.of(cart.get("gratuity"));
        if (gratuity.signum() > 0) {
            lines.add(new QuoteLine(Invoice.Line.GRATUITY, null, "Chair gratuity",
                    "Passed to the team, thank you", 1, gratuity, BigDecimal.ZERO, BigDecimal.ZERO, gratuity, defaultStaff));
        }

        if (lines.isEmpty()) throw ApiException.badRequest("The cart is empty");

        BigDecimal subtotal = BigDecimal.ZERO;
        for (QuoteLine line : lines) if (taxableKind(line)) subtotal = Money.add(subtotal, line.amount());
        steps.add("Subtotal: " + lines.size() + " line(s) = " + Money.inr(subtotal));

        // --- promotions ---------------------------------------------------
        BigDecimal discount = Money.of(cart.get("discount"));
        String promoCode = null;
        PromoCode promo = null;
        String requestedPromo = com.sillage.core.JsonMapper.asText(cart.get("promoCode"));
        if (Util.present(requestedPromo)) {
            promo = repos.promo(requestedPromo.trim().toUpperCase(java.util.Locale.ROOT));
            if (promo == null) throw ApiException.notFound("Promo code " + requestedPromo);
            if (!promo.isUsable(LocalDate.now())) throw ApiException.conflict("That code is not active right now");
            if (promo.minSpend() != null && subtotal.compareTo(promo.minSpend()) < 0) {
                throw ApiException.conflict("That code needs a minimum of " + Money.inr(promo.minSpend()));
            }
            promoCode = promo.code();
            BigDecimal promoDiscount = switch (Util.nz(promo.kind(), PromoCode.PERCENT).toUpperCase(java.util.Locale.ROOT)) {
                case PromoCode.FLAT -> Money.scale(promo.value());
                case PromoCode.FREE_ADDON -> freeAddonValue(lines);
                default -> Money.percent(subtotal, promo.value() == null ? 0 : promo.value().doubleValue());
            };
            if (promo.maxDiscount() != null && promo.maxDiscount().signum() > 0) {
                promoDiscount = Money.min(promoDiscount, promo.maxDiscount());
            }
            discount = Money.add(discount, promoDiscount);
            steps.add("Promo " + promoCode + " (" + promo.kind() + " " + (promo.value() == null ? "" : promo.value().toPlainString())
                    + ") = \u2212" + Money.inr(promoDiscount));
        } else if (discount.signum() > 0) {
            steps.add("Desk discount = \u2212" + Money.inr(discount));
        }
        discount = Money.min(discount, subtotal);

        // --- loyalty ------------------------------------------------------
        int pointsRequested = Math.max(0, com.sillage.core.JsonMapper.asInt(cart.get("loyaltyPoints"), 0));
        BigDecimal loyaltyRedemption = BigDecimal.ZERO;
        int pointsRedeemed = 0;
        if (pointsRequested > 0 && customer != null && config.loyaltyEnabled()) {
            pointsRedeemed = Math.min(pointsRequested, Math.max(0, customer.points()));
            loyaltyRedemption = loyalty.value(pointsRedeemed, config);
            BigDecimal ceiling = Money.subtract(subtotal, discount).multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
            if (loyaltyRedemption.compareTo(ceiling) > 0) {
                loyaltyRedemption = Money.scale(ceiling);
                pointsRedeemed = loyalty.pointsFor(loyaltyRedemption, config);
                steps.add("Loyalty capped at 50% of the discounted subtotal");
            }
            if (pointsRedeemed > 0) {
                lines.add(new QuoteLine(Invoice.Line.LOYALTY, null, "Loyalty redemption",
                        pointsRedeemed + " points", 1, loyaltyRedemption.negate(), BigDecimal.ZERO, BigDecimal.ZERO,
                        loyaltyRedemption.negate(), defaultStaff));
                steps.add("Loyalty: " + pointsRedeemed + " points = \u2212" + Money.inr(loyaltyRedemption));
            } else {
                loyaltyRedemption = BigDecimal.ZERO;
            }
        }

        // --- tax ----------------------------------------------------------
        BigDecimal base = Money.subtract(Money.subtract(subtotal, discount), loyaltyRedemption);
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        Map<BigDecimal, BigDecimal> perRate = new LinkedHashMap<>();
        for (QuoteLine line : lines) {
            if (!taxableKind(line) || line.taxPct() == null) continue;
            BigDecimal lineNet = config.pricesInclusiveOfTax()
                    ? line.amount().divide(BigDecimal.ONE.add(Money.rate(line.taxPct().doubleValue())), 2, RoundingMode.HALF_UP)
                    : line.amount();
            BigDecimal lineShare = base.signum() <= 0 || subtotal.signum() <= 0
                    ? BigDecimal.ZERO
                    : lineNet.multiply(base).divide(subtotal, 6, RoundingMode.HALF_UP);
            lineShare = Money.scale(lineShare);
            taxable = Money.add(taxable, lineShare);
            BigDecimal lineTax = config.pricesInclusiveOfTax()
                    ? Money.subtract(line.amount(), lineShare)
                    : Money.multiply(lineShare, Money.rate(line.taxPct().doubleValue()));
            taxTotal = Money.add(taxTotal, lineTax);
            perRate.merge(line.taxPct(), lineTax, Money::add);
        }
        if (config.pricesInclusiveOfTax()) {
            steps.add("Prices are tax-inclusive: " + config.taxLabel() + " of " + Money.inr(taxTotal)
                    + " is extracted from the " + Money.inr(base) + " paid");
        } else {
            steps.add(config.taxLabel() + " on " + Money.inr(taxable) + " = " + Money.inr(taxTotal));
        }

        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        if (BusinessSettings.SPLIT_CGST_SGST.equals(config.taxSplit())) {
            cgst = taxTotal.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            sgst = Money.subtract(taxTotal, cgst);
        } else if (BusinessSettings.SPLIT_IGST.equals(config.taxSplit())) {
            sgst = taxTotal;
        }

        BigDecimal total = Money.add(base, Money.add(taxTotal, gratuity));
        BigDecimal roundOff = BigDecimal.ZERO;
        if (config.autoRound() && config.roundTo() != null && config.roundTo().signum() > 0) {
            BigDecimal rounded = Money.roundTo(total, config.roundTo());
            roundOff = Money.subtract(rounded, total);
            total = rounded;
            if (roundOff.signum() != 0) steps.add("Rounded to the nearest " + config.roundTo().toPlainString()
                    + ": " + (roundOff.signum() > 0 ? "+" : "\u2212") + Money.inr(roundOff.abs()));
        }

        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("steps", steps);
        explain.put("rates", perRate.entrySet().stream()
                .map(e -> Util.map("rate", e.getKey(), "amount", Money.scale(e.getValue()))).toList());
        explain.put("taxSplit", config.taxSplit());
        explain.put("pricesInclusiveOfTax", config.pricesInclusiveOfTax());
        explain.put("pointsBalanceAfter", customer == null ? 0 : customer.points() - pointsRedeemed);
        explain.put("loyaltyPointValue", config.loyaltyPointValue());

        return new Quote(lines, subtotal, discount, promoCode, loyaltyRedemption, pointsRedeemed, gratuity,
                taxable, cgst, sgst, taxTotal, roundOff, total, explain);
    }

    private BigDecimal freeAddonValue(List<QuoteLine> lines) {
        BigDecimal lowest = null;
        for (QuoteLine line : lines) {
            if (!Invoice.Line.ADDON.equals(line.kind())) continue;
            if (lowest == null || line.amount().compareTo(lowest) < 0) lowest = line.amount();
        }
        return lowest == null ? BigDecimal.ZERO : lowest;
    }

    private String staffName(String staffId) {
        if (Util.blank(staffId)) return null;
        Staff member = repos.staff(staffId);
        return member == null ? null : member.name();
    }

    private static List<Map<String, Object>> objectList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> map = com.sillage.core.JsonMapper.asMap(entry);
                if (!map.isEmpty()) out.add(map);
            }
        }
        return out;
    }

    /** Convenience for a single-service cart (the public booking flow). */
    public Quote quoteService(String serviceId, String variantId, String staffId) {
        Map<String, Object> cart = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("serviceId", serviceId);
        if (Util.present(variantId)) item.put("variantId", variantId);
        if (Util.present(staffId)) item.put("staffId", staffId);
        items.add(item);
        cart.put("items", items);
        return quote(cart, null);
    }

    /** Exposes the quote as JSON for the desk's live total panel. */
    public Map<String, Object> view(Quote quote) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", quote.lines().stream().map(l -> Util.map(
                "kind", l.kind(), "refId", l.refId(), "name", l.name(), "detail", l.detail(),
                "qty", l.qty(), "unitPrice", l.unitPrice(), "taxPct", l.taxPct(),
                "discount", l.discount(), "amount", l.amount(), "staffId", l.staffId())).toList());
        out.put("subtotal", quote.subtotal());
        out.put("discount", quote.discount());
        out.put("promoCode", quote.promoCode());
        out.put("loyaltyRedemption", quote.loyaltyRedemption());
        out.put("pointsRedeemed", quote.pointsRedeemed());
        out.put("gratuity", quote.gratuity());
        out.put("taxable", quote.taxable());
        out.put("cgst", quote.cgst());
        out.put("sgst", quote.sgst());
        out.put("taxTotal", quote.taxTotal());
        out.put("roundOff", quote.roundOff());
        out.put("total", quote.total());
        out.put("explain", quote.explain());
        return out;
    }

    /** Adds {@code isTaxableKind} without polluting the record signature. */
    private static boolean taxableKind(QuoteLine line) {
        return Invoice.Line.SERVICE.equals(line.kind()) || Invoice.Line.PRODUCT.equals(line.kind())
                || Invoice.Line.ADDON.equals(line.kind());
    }
}
