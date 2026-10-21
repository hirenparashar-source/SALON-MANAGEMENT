package com.sillage.api;

import com.sillage.Salon;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Invoice;
import com.sillage.model.Payment;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The printable receipt: one self-contained HTML document, set in the house editorial style and
 * optimised for A5/A4 printing. No external assets, no script \u2014 the Content-Security-Policy
 * stays strict and the page still prints on a salon's inkjet.
 */
public final class ReceiptPage {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' hh:mm a");

    private ReceiptPage() {}

    public static String render(Invoice invoice, Salon salon) {
        BusinessSettings config = salon.settingsService.current();
        String salonName = Util.nz(config.salonName(), "SILLAGE");
        StringBuilder b = new StringBuilder(16384);
        b.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
         .append("<title>Receipt ").append(esc(invoice.number())).append(" \u00b7 ").append(esc(salonName)).append("</title>\n")
         .append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n")
         .append("<link href=\"https://fonts.googleapis.com/css2?family=Bodoni+Moda:opsz,wght@6..96,400;6..96,600&family=Jost:wght@300;400;500&family=IBM+Plex+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">\n")
         .append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n");

        b.append("<div class=\"sheet\">\n");

        // masthead
        b.append("<header class=\"masthead\">\n")
         .append("<div class=\"mark\">").append(esc(salonName.toUpperCase(Locale.ROOT))).append("</div>\n")
         .append("<div class=\"sub\">").append(esc(Util.nz(config.tagline(), "Maison de Coiffure & Beaut\u00e9")))
         .append(" \u00b7 est. ").append(config.established()).append("</div>\n")
         .append("<div class=\"addr\">").append(esc(address(config))).append("</div>\n")
         .append("<div class=\"addr mono\">").append(esc(Util.nz(config.phone()))).append(" \u00b7 ")
         .append(esc(Util.nz(config.email()))).append(esc(Util.present(config.gstin()) ? " \u00b7 GSTIN " + config.gstin() : ""))
         .append("</div>\n</header>\n");

        // meta
        b.append("<section class=\"meta\">\n")
         .append(metaCell("Receipt", invoice.number()))
         .append(metaCell("Issued", invoice.createdAt() == null ? "\u2014" : STAMP.format(invoice.createdAt())))
         .append(metaCell("Client", Util.nz(invoice.customerName(), "Walk-in guest")))
         .append(metaCell("Fiscal year", Util.nz(invoice.fiscalYear())))
         .append(metaCell("Billed by", Util.nz(invoice.createdBy())))
         .append(metaCell("Status", invoice.status() == null ? "\u2014" : invoice.status().label()))
         .append("</section>\n");

        if (Util.present(invoice.customerPhone()) || Util.present(invoice.customerEmail())) {
            b.append("<p class=\"contact mono\">").append(esc(invoice.customerPhone()))
             .append(Util.present(invoice.customerEmail()) ? " \u00b7 " + esc(invoice.customerEmail()) : "")
             .append("</p>\n");
        }

        // lines
        b.append("<table class=\"lines\">\n<thead><tr>")
         .append("<th class=\"l\">Service / article</th><th class=\"c\">Qty</th>")
         .append("<th class=\"r\">Rate</th><th class=\"r\">Amount</th>")
         .append("</tr></thead>\n<tbody>\n");
        for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
            String kind = Util.nz(line.kind()).toLowerCase(Locale.ROOT);
            b.append("<tr class=\"kind-").append(esc(kind)).append("\">")
             .append("<td class=\"l\"><span class=\"name\">").append(esc(line.name())).append("</span>")
             .append(Util.present(line.detail()) ? "<span class=\"detail\">" + esc(line.detail()) + "</span>" : "")
             .append("<span class=\"tag\">").append(esc(labelFor(line.kind()))).append("</span></td>")
             .append("<td class=\"c mono\">").append(line.qty()).append("</td>")
             .append("<td class=\"r mono\">").append(Money.format(line.unitPrice())).append("</td>")
             .append("<td class=\"r mono strong\">").append(signed(line)).append("</td>")
             .append("</tr>\n");
        }
        b.append("</tbody>\n</table>\n");

        // totals
        b.append("<section class=\"totals\">\n");
        b.append(totalRow("Subtotal", invoice.subtotal(), false));
        if (Money.isPositive(invoice.discount())) {
            b.append(totalRow("Discount" + (Util.present(invoice.promoCode()) ? " \u00b7 " + invoice.promoCode() : ""),
                    invoice.discount().negate(), true));
        }
        if (Money.isPositive(invoice.loyaltyRedemption())) {
            b.append(totalRow("Loyalty redeemed \u00b7 " + invoice.pointsRedeemed() + " pts",
                    invoice.loyaltyRedemption().negate(), true));
        }
        if (config.pricesInclusiveOfTax() && Money.isPositive(invoice.taxable())) {
            b.append(totalRow("Taxable value", invoice.taxable(), false));
        }
        if (Money.isPositive(invoice.cgst())) b.append(totalRow("CGST", invoice.cgst(), false));
        if (Money.isPositive(invoice.sgst())) b.append(totalRow("SGST", invoice.sgst(), false));
        if (Money.isPositive(invoice.taxTotal()) && Money.isZero(invoice.cgst()) && Money.isZero(invoice.sgst())) {
            b.append(totalRow(Util.nz(config.taxLabel(), "Tax"), invoice.taxTotal(), false));
        }
        if (Money.isPositive(invoice.gratuity())) b.append(totalRow("Gratuity", invoice.gratuity(), false));
        if (!Money.isZero(invoice.roundOff())) {
            b.append(totalRow("Round off", invoice.roundOff(), invoice.roundOff().signum() < 0));
        }
        b.append("<div class=\"grand\"><span>Total</span><span class=\"mono\">")
         .append(Money.inr(invoice.total())).append("</span></div>\n");

        List<Payment> tenders = Util.nullSafe(invoice.payments()).stream().filter(Payment::isSuccessful).toList();
        if (!tenders.isEmpty()) {
            b.append("<div class=\"tenders\">\n");
            for (Payment payment : tenders) {
                b.append("<div class=\"tender\"><span>").append(esc(payment.tenderLabel())).append("</span>")
                 .append("<span class=\"mono\">").append(Money.format(payment.amount())).append("</span></div>\n");
            }
            b.append("</div>\n");
        }
        if (Money.isPositive(invoice.balance())) {
            b.append("<div class=\"balance\">Balance due <span class=\"mono\">")
             .append(Money.inr(invoice.balance())).append("</span></div>\n");
        }
        b.append("</section>\n");

        // loyalty + seal
        b.append("<section class=\"foot\">\n");
        if (config.loyaltyEnabled() && invoice.pointsEarned() > 0) {
            b.append("<p class=\"loyalty\">Points earned on this visit: <b>")
             .append(invoice.pointsEarned()).append("</b> \u00b7 worth ")
             .append(Money.inr(salon.loyalty.value(invoice.pointsEarned(), config))).append("</p>\n");
        }
        b.append("<div class=\"seal\">\n<div class=\"seal-label\">Receipt seal</div>\n")
         .append("<div class=\"seal-code mono\">").append(esc(Util.nz(invoice.receiptCode()))).append("</div>\n")
         .append("<div class=\"seal-note\">Verify at <span class=\"mono\">")
         .append(esc(Util.nz(config.website(), ""))).append("/api/receipts/verify?number=")
         .append(esc(invoice.number())).append("&amp;code=").append(esc(Util.nz(invoice.receiptCode())))
         .append("</span></div>\n</div>\n");
        if (Util.present(invoice.notes())) b.append("<p class=\"notes\">").append(esc(invoice.notes())).append("</p>\n");
        String policy = config.policies() == null ? null : config.policies().get("cancellation");
        if (Util.present(policy)) b.append("<p class=\"policy\">").append(esc(policy)).append("</p>\n");
        String thanks = config.policies() == null ? null : config.policies().get("receipt");
        b.append("<p class=\"thanks\">").append(esc(Util.nz(thanks,
                "Thank you \u2014 we look forward to your next visit."))).append("</p>\n");
        b.append("</section>\n");

        b.append("<footer class=\"colophon mono\">Generated by ").append(esc(salonName))
         .append(" \u00b7 ").append(esc(Util.nz(invoice.source(), "desk")))
         .append(" \u00b7 ").append(esc(java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))))
         .append(" \u00b7 page ").append(esc(invoice.number())).append("</footer>\n");

        b.append("</div>\n<div class=\"actions no-print\">")
         .append("<button type=\"button\" onclick=\"window.print()\">Print this receipt</button>")
         .append("<a href=\"/api/receipts/").append(esc(invoice.id())).append("/text?download=true\">Download .txt</a>")
         .append("<a href=\"/api/receipts/").append(esc(invoice.id())).append("/csv\">Download .csv</a>")
         .append("<a href=\"/\">Back to the salon</a>")
         .append("</div>\n</body>\n</html>");
        return b.toString();
    }

    // ------------------------------------------------------------------ pieces

    private static String address(BusinessSettings config) {
        return Util.join(List.of(Util.nz(config.addressLine()), Util.nz(config.city()),
                Util.nz(config.state()), Util.nz(config.pincode())), ", ");
    }

    private static String metaCell(String label, String value) {
        return "<div class=\"cell\"><span class=\"k\">" + esc(label) + "</span><span class=\"v\">"
                + esc(value) + "</span></div>\n";
    }

    private static String labelFor(String kind) {
        if (kind == null) return "";
        return switch (kind) {
            case Invoice.Line.SERVICE -> "service";
            case Invoice.Line.ADDON -> "add-on";
            case Invoice.Line.PRODUCT -> "retail";
            case Invoice.Line.DISCOUNT -> "discount";
            case Invoice.Line.LOYALTY -> "loyalty";
            case Invoice.Line.GRATUITY -> "gratuity";
            default -> kind.toLowerCase(Locale.ROOT);
        };
    }

    private static String totalRow(String label, BigDecimal amount, boolean deduction) {
        return "<div class=\"row" + (deduction ? " deduction" : "") + "\"><span>" + esc(label)
                + "</span><span class=\"mono\">" + (deduction ? "\u2212" + Money.format(amount.abs())
                : Money.format(amount)) + "</span></div>\n";
    }

    private static String signed(Invoice.Line line) {
        return line.isDeduction() ? "\u2212" + Money.format(line.amount().abs()) : Money.format(line.amount());
    }

    private static String esc(String value) {
        return Util.escapeHtml(Util.nz(value));
    }

    private static String css() {
        return """
                :root {
                  --bone: #F4F1EA; --paper: #EAE5DA; --ink: #151310; --soft: #3A352E;
                  --gold: #9C7B3F; --champagne: #C9A96A; --bordeaux: #7A2E3A; --stone: #CFC8BA;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0; padding: 32px 16px 64px; background: var(--paper); color: var(--ink);
                  font-family: "Jost", "Segoe UI", system-ui, sans-serif; font-weight: 300;
                  -webkit-font-smoothing: antialiased;
                }
                .mono { font-family: "IBM Plex Mono", ui-monospace, monospace; font-size: 0.82em; letter-spacing: 0.02em; }
                .sheet {
                  max-width: 780px; margin: 0 auto; background: var(--bone); padding: 52px 56px 40px;
                  border: 1px solid var(--stone); box-shadow: 0 24px 60px -40px rgba(21,19,16,.5);
                }
                .masthead { text-align: center; border-bottom: 2px solid var(--ink); padding-bottom: 22px; }
                .mark {
                  font-family: "Bodoni Moda", Georgia, serif; font-weight: 400; font-size: 40px;
                  letter-spacing: 0.34em; text-indent: 0.34em; line-height: 1;
                }
                .sub { margin-top: 10px; font-size: 12px; letter-spacing: 0.24em; text-transform: uppercase; color: var(--soft); }
                .addr { margin-top: 8px; font-size: 12.5px; color: var(--soft); }
                .meta {
                  display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px 24px;
                  margin: 28px 0 6px; padding-bottom: 20px; border-bottom: 1px solid var(--stone);
                }
                .cell { display: flex; flex-direction: column; gap: 3px; }
                .cell .k { font-size: 10px; letter-spacing: 0.2em; text-transform: uppercase; color: var(--gold); }
                .cell .v { font-size: 14px; }
                .contact { margin: 0 0 18px; color: var(--soft); font-size: 12px; }
                table.lines { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
                table.lines th {
                  font-size: 10px; letter-spacing: 0.18em; text-transform: uppercase; color: var(--gold);
                  border-bottom: 1px solid var(--ink); padding: 8px 4px; text-align: right; font-weight: 500;
                }
                table.lines th.l { text-align: left; } table.lines th.c { text-align: center; }
                table.lines td { padding: 11px 4px; border-bottom: 1px solid rgba(207,200,186,.7); vertical-align: top; }
                td.l { text-align: left; } td.c { text-align: center; } td.r { text-align: right; }
                .name { display: block; font-size: 15px; }
                .detail { display: block; font-size: 12px; color: var(--soft); margin-top: 2px; }
                .tag {
                  display: inline-block; margin-top: 5px; font-size: 9.5px; letter-spacing: 0.16em;
                  text-transform: uppercase; color: var(--gold); border: 1px solid var(--stone);
                  padding: 1px 6px; border-radius: 2px;
                }
                .strong { font-weight: 500; }
                .kind-discount .name, .kind-loyalty .name { color: var(--bordeaux); }
                .totals { margin-top: 18px; display: flex; flex-direction: column; align-items: flex-end; }
                .row { display: flex; justify-content: space-between; width: 320px; padding: 5px 0; font-size: 13.5px; color: var(--soft); }
                .row.deduction { color: var(--bordeaux); }
                .grand {
                  display: flex; justify-content: space-between; width: 320px; margin-top: 12px; padding: 14px 0 4px;
                  border-top: 2px solid var(--ink); font-family: "Bodoni Moda", Georgia, serif; font-size: 22px;
                }
                .tenders { width: 320px; margin-top: 12px; border-top: 1px solid var(--stone); }
                .tender { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; }
                .balance { width: 320px; display: flex; justify-content: space-between; padding: 10px 0 0; font-size: 14px; color: var(--bordeaux); }
                .foot { margin-top: 34px; border-top: 1px solid var(--stone); padding-top: 22px; }
                .loyalty { font-size: 13px; color: var(--soft); margin: 0 0 16px; }
                .seal { display: inline-block; border: 1px solid var(--gold); padding: 12px 18px; }
                .seal-label { font-size: 9.5px; letter-spacing: 0.22em; text-transform: uppercase; color: var(--gold); }
                .seal-code { font-size: 19px; letter-spacing: 0.18em; margin: 6px 0 4px; }
                .seal-note { font-size: 10.5px; color: var(--soft); word-break: break-all; }
                .notes { margin: 18px 0 0; font-size: 12.5px; color: var(--soft); font-style: italic; }
                .policy { margin: 12px 0 0; font-size: 11.5px; color: var(--soft); }
                .thanks {
                  margin: 26px 0 0; text-align: center; font-family: "Bodoni Moda", Georgia, serif;
                  font-size: 16px; letter-spacing: 0.06em;
                }
                .colophon { margin-top: 30px; padding-top: 14px; border-top: 1px solid var(--stone);
                  font-size: 10.5px; color: var(--soft); text-align: center; letter-spacing: 0.08em; }
                .actions { max-width: 780px; margin: 20px auto 0; display: flex; gap: 10px; flex-wrap: wrap; }
                .actions a, .actions button {
                  font: inherit; font-size: 12px; letter-spacing: 0.14em; text-transform: uppercase;
                  padding: 10px 16px; border: 1px solid var(--ink); background: transparent; color: var(--ink);
                  text-decoration: none; cursor: pointer;
                }
                .actions a:hover, .actions button:hover { background: var(--ink); color: var(--bone); }
                @media print {
                  body { background: #fff; padding: 0; }
                  .sheet { border: 0; box-shadow: none; max-width: none; padding: 12mm 14mm; }
                  .no-print { display: none !important; }
                  @page { margin: 12mm; }
                }
                @media (max-width: 640px) {
                  .sheet { padding: 30px 20px; }
                  .meta { grid-template-columns: 1fr 1fr; }
                  .row, .grand, .tenders, .balance { width: 100%; }
                  .mark { font-size: 28px; letter-spacing: 0.24em; text-indent: 0.24em; }
                }
                """;
    }

    /** Convenience for tests and the console's "last receipt" widget. */
    public static Map<String, Object> summary(Invoice invoice) {
        return Util.map("id", invoice.id(), "number", invoice.number(), "total", invoice.total(),
                "status", invoice.status() == null ? null : invoice.status().label(),
                "seal", invoice.receiptCode(), "url", "/api/receipts/" + invoice.id() + "/print");
    }
}
