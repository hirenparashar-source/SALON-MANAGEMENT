package com.sillage.seed;

import com.sillage.Salon;
import com.sillage.core.Util;
import com.sillage.model.Category;
import com.sillage.model.Look;
import com.sillage.model.Product;
import com.sillage.model.PromoCode;
import com.sillage.model.ServiceItem;
import com.sillage.model.Testimonial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The opening book: the menu, the lookbook, the shelf and the promotions. All prices are INR and
 * deliberately realistic for a Ludhiana flagship \u2014 the demo should feel like a real salon, not a
 * catalogue of ones and zeros.
 */
final class Catalogue {

    private Catalogue() {}

    static void populate(Salon salon) {
        categories(salon);
        services(salon);
        looks(salon);
        testimonials(salon);
        products(salon);
        promos(salon);
    }

    // ------------------------------------------------------------------ categories

    private static void categories(Salon salon) {
        salon.categories.put(new Category("cat-hair", "Hair", "Cut, finish and the architecture underneath.",
                "bordeaux", 1, true, List.of("cut", "blow dry", "styling")));
        salon.categories.put(new Category("cat-colour", "Colour Atelier", "Hand-painted light, disciplined regrowth.",
                "gold", 2, true, List.of("balayage", "global colour", "gloss")));
        salon.categories.put(new Category("cat-spa", "Spa & Rituals", "Repair, smoothing and the long lunch-hour escape.",
                "verdigris", 3, true, List.of("spa", "keratin", "scalp")));
        salon.categories.put(new Category("cat-skin", "Skin & Face", "Facials, brows and the quiet science of glow.",
                "champagne", 4, true, List.of("facial", "brow", "lash")));
        salon.categories.put(new Category("cat-nails", "Nails", "Manicure, pedicure and gel that survives a wedding week.",
                "stone", 5, true, List.of("manicure", "pedicure", "gel")));
        salon.categories.put(new Category("cat-bridal", "Bridal Chambre", "Four hours, one photograph, forty years of looking at it.",
                "bordeaux", 6, true, List.of("bridal", "drape", "makeup")));
        salon.categories.put(new Category("cat-gents", "Gentlemen's Room", "Cut, beard, hot towel. In and out in forty-five.",
                "ink", 7, true, List.of("men", "beard", "shave")));
    }

    // ------------------------------------------------------------------ services

    /** One helper so the menu reads as a menu rather than as constructor soup. */
    private static ServiceItem service(String id, String code, String name, String categoryId, String tagline,
                                       String description, String editorial, int minutes, int price,
                                       List<ServiceItem.Variant> variants, List<String> staffIds, List<String> tags,
                                       String prep, String aftercare, boolean online, boolean featured) {
        String now = LocalDateTime.now().minusDays(210).toString();
        return new ServiceItem(id, code, name, categoryId, tagline, description, editorial, minutes,
                Money(price), variants == null ? List.of() : variants, staffIds, tags, prep, aftercare,
                id.replace("svc-", "img-"), true, online, featured, 0, 0.0, now, now);
    }

    private static ServiceItem.Variant variant(String id, String label, int extraMinutes, int extraPrice, String note) {
        return new ServiceItem.Variant(id, label, extraMinutes, Money(extraPrice), note);
    }

    private static BigDecimal Money(int value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void services(Salon salon) {
        List<ServiceItem> menu = new ArrayList<>();

        // ---- hair
        menu.add(service("svc-signature-cut", "SIL-H-01", "Signature Cut & Finish", "cat-hair",
                "The house cut \u2014 consultation, cut, wash, set.",
                "A full consultation, a precision cut on damp hair, a wash with the ritual shampoo and a blown finish.",
                "Every head of hair arrives with a history. We read it, then we cut.",
                60, 1800,
                List.of(variant("var-sc-senior", "With a senior stylist", 10, 600, "Ten years on the floor"),
                        variant("var-sc-director", "With the creative director", 15, 1200, "Meher Kapur")),
                List.of("stf-meher", "stf-aarav", "stf-simran"),
                List.of("cut", "signature", "finish"), "Arrive with dry, unstyled hair if you can.",
                "Wash every third day; a cut holds its shape for six to eight weeks.", true, true));

        menu.add(service("svc-dry-cut", "SIL-H-02", "Precision Dry Cut", "cat-hair",
                "Cut on dry hair \u2014 for curls and cowlicks that lie differently wet.",
                "No wash, no blow dry. We cut the hair exactly as you wear it.",
                "Curls shrink. Fringes deceive. Dry cutting does not lie.",
                40, 1200, null, List.of("stf-aarav", "stf-simran"),
                List.of("cut", "curls", "dry cut"), "Come as you are.",
                "Book a refresh at week six.", true, false));

        menu.add(service("svc-child-cut", "SIL-H-03", "Children's Cut", "cat-hair",
                "Under twelve. Patience included.",
                "A gentle first-cut or a school-term tidy, with a booster seat and a mirror at their height.",
                "The first haircut is a family event. We behave accordingly.",
                30, 800, null, List.of("stf-aarav", "stf-taran"),
                List.of("children", "cut", "family"), "Bring the favourite snack.",
                "Six weeks keeps the fringe out of the eyes.", true, false));

        menu.add(service("svc-fringe", "SIL-H-04", "Fringe Trim", "cat-hair",
                "Fifteen minutes between cuts.",
                "A tidy of the fringe or the nape, no wash.",
                "The cheapest appointment on the menu, and often the most satisfying.",
                15, 300, null, List.of("stf-meher", "stf-aarav", "stf-simran"),
                List.of("fringe", "trim", "quick"), "Dry hair only.", "Every three weeks.", true, false));

        menu.add(service("svc-blow-dry", "SIL-H-05", "Blow Dry & Set", "cat-hair",
                "Wash, blow, set. Out in thirty-five.",
                "A wash with the ritual range, a blown finish and a set that holds through an evening.",
                "The forty-minute transformation, before anyone asks what you had done.",
                35, 900, List.of(variant("var-bd-volume", "Extra volume", 10, 300, "Round brush work")),
                List.of("stf-simran", "stf-taran"), List.of("blow dry", "finish", "event"),
                "Nothing needed.", "Sleep on silk if you have it.", true, false));

        menu.add(service("svc-updo", "SIL-H-06", "Occasion Styling", "cat-hair",
                "Updo, waves or a sculpted set for the evening.",
                "Pinned, waved or sculpted \u2014 built to survive a dance floor.",
                "Photographed from three angles before we let you leave.",
                75, 2600, null, List.of("stf-meher", "stf-simran"),
                List.of("occasion", "updo", "party", "styling"), "Day-old hair grips better.",
                "A silk scarf preserves it for the morning after.", true, true));

        // ---- colour
        menu.add(service("svc-global-colour", "SIL-C-01", "Global Colour", "cat-colour",
                "One shade, root to end, gloss included.",
                "Ammonia-conscious global colour with an olaplex add-in and a finishing gloss.",
                "Colour is a commitment measured in regrowth. We plan for it.",
                105, 3800,
                List.of(variant("var-gc-medium", "Medium length", 15, 600, "Below the shoulder"),
                        variant("var-gc-long", "Long length", 30, 1200, "Mid-back or longer")),
                List.of("stf-ishaan", "stf-meher"), List.of("colour", "global", "gloss"),
                "Skip the wash for 24 hours beforehand.", "Sulphate-free only; a gloss refresh at week five.",
                true, true));

        menu.add(service("svc-roots", "SIL-C-02", "Roots Only", "cat-colour",
                "Regrowth retouch, matched to your existing shade.",
                "Two centimetres of regrowth, blended and glossed.",
                "The four-week appointment that keeps a \u20B93,800 colour looking like \u20B93,800.",
                60, 2200, null, List.of("stf-ishaan"), List.of("roots", "regrowth", "colour", "maintenance"),
                "Come with dry roots.", "Every four to five weeks.", true, false));

        menu.add(service("svc-balayage", "SIL-C-03", "Balayage", "cat-colour",
                "Hand-painted light. No foil lines, no helmet.",
                "Freehand painting, a bond builder through the lengths and a toner to finish.",
                "Balayage is not a colour, it is a gradient with an exit strategy.",
                180, 7500, List.of(variant("var-bal-full", "Full head", 30, 1800, "Crown to ends")),
                List.of("stf-ishaan", "stf-meher"), List.of("balayage", "colour", "blonde", "hand-painted"),
                "A strand test may be needed for dark bases.",
                "Purple shampoo weekly; a toner at week six.", true, true));

        menu.add(service("svc-highlights", "SIL-C-04", "Foil Highlights", "cat-colour",
                "Half or full head, fine foils, clean regrowth.",
                "Precision foils with a bond builder, toned and glossed.",
                "Foil work is arithmetic: section, angle, timing. Get one wrong and the head shows it.",
                135, 4800, List.of(variant("var-hl-full", "Full head", 30, 1600, null)),
                List.of("stf-ishaan"), List.of("highlights", "foils", "colour"),
                "No oil on the day.", "Toner refresh at week six.", true, false));

        menu.add(service("svc-toner-gloss", "SIL-C-05", "Toner & Gloss", "cat-colour",
                "Twelve minutes of shine, five weeks of compliments.",
                "A demi-permanent gloss to correct tone and add mirror shine.",
                "The most under-booked service on any menu.",
                40, 1600, null, List.of("stf-ishaan", "stf-meher"),
                List.of("toner", "gloss", "shine", "colour"), "Clean, dry hair.",
                "Repeat at week five.", true, false));

        menu.add(service("svc-colour-consult", "SIL-C-06", "Colour Correction Consultation", "cat-colour",
                "Strand tests, a plan and an honest price.",
                "An hour with the colour lead: history, strand tests, staged plan and a written estimate.",
                "Correction is a project, not an appointment. This is where the project starts.",
                60, 2500, null, List.of("stf-ishaan"), List.of("consultation", "correction", "colour"),
                "Bring photographs and your product history.", "The consultation fee is credited to the correction.",
                false, false));

        // ---- spa & rituals
        menu.add(service("svc-olaplex", "SIL-S-01", "Olaplex Bond Repair", "cat-spa",
                "For hair that has been through something.",
                "A three-step bond rebuild, sealed with heat.",
                "Bleach takes bonds. This puts some of them back.",
                45, 2400, null, List.of("stf-ishaan", "stf-meher", "stf-aarav"),
                List.of("olaplex", "repair", "treatment", "bond"), "No preparation needed.",
                "Every six weeks for chemically treated hair.", true, false));

        menu.add(service("svc-hair-spa", "SIL-S-02", "Hair Spa", "cat-spa",
                "Deep conditioning, steam, scalp massage.",
                "A cleanse, a mask under steam and a fifteen-minute scalp massage.",
                "Forty-five minutes of nothing being asked of you.",
                50, 1900, null, List.of("stf-taran", "stf-simran"),
                List.of("spa", "conditioning", "massage", "relax"), "Come with product-free hair if possible.",
                "Weekly in monsoon, fortnightly in winter.", true, true));

        menu.add(service("svc-keratin", "SIL-S-03", "Keratin Smoothing Ritual", "cat-spa",
                "Frizz out, movement in. Formaldehyde-free.",
                "A formaldehyde-free smoothing ritual, sealed plate by plate.",
                "Ludhiana monsoon, meet your opposite.",
                210, 8500, List.of(variant("var-ker-long", "Long hair", 45, 2000, null)),
                List.of("stf-meher", "stf-ishaan"), List.of("keratin", "smoothing", "frizz", "monsoon"),
                "Wash with a clarifying shampoo the night before.",
                "Sulphate-free shampoo only; do not tie the hair for 48 hours.", true, true));

        menu.add(service("svc-scalp-detox", "SIL-S-04", "Scalp Detox", "cat-spa",
                "Micro-exfoliation, serum, ten minutes of quiet.",
                "A scalp scan, micro-exfoliation and a targeted serum.",
                "Everything you put on your hair starts here.",
                45, 2200, null, List.of("stf-taran", "stf-aarav"),
                List.of("scalp", "detox", "exfoliation", "clarify"), "No styling products on the day.",
                "Monthly if you use dry shampoo.", true, false));

        menu.add(service("svc-champissage", "SIL-S-05", "Champissage", "cat-spa",
                "Head, neck and shoulder \u2014 the Indian classic.",
                "A thirty-minute seated oil-free head massage.",
                "The appointment people book for the massage and stay for the cut.",
                30, 1100, null, List.of("stf-taran", "stf-simran"),
                List.of("massage", "champissage", "oil", "relax"), "Nothing.", "Drink water afterwards.",
                true, false));

        // ---- skin
        menu.add(service("svc-signature-facial", "SIL-K-01", "Signature Facial", "cat-skin",
                "Cleanse, exfoliate, extract, mask, massage.",
                "Six steps, tailored to the skin in front of us that day.",
                "Skin is a twelve-week project with a weekly appointment.",
                75, 3200, null, List.of("stf-noor"), List.of("facial", "skin", "glow", "signature"),
                "No retinol for 48 hours before.", "SPF, always.", true, true));

        menu.add(service("svc-hydra-facial", "SIL-K-02", "Hydra Brightening Facial", "cat-skin",
                "Deep cleanse and infusion \u2014 the one before the wedding.",
                "A hydradermabrasion cleanse with a brightening infusion.",
                "Booked two days before every important photograph.",
                90, 4500, null, List.of("stf-noor"), List.of("hydra", "brightening", "facial", "bridal"),
                "Arrive make-up free.", "Avoid actives for 24 hours.", true, true));

        menu.add(service("svc-peel", "SIL-K-03", "Gentle Chemical Peel", "cat-skin",
                "Lactic or mandelic, staged over a course.",
                "A superficial peel with a neutraliser and a calming mask.",
                "Peels are a ladder, not a jump.",
                60, 3800, null, List.of("stf-noor"), List.of("peel", "resurfacing", "skin", "acne"),
                "Patch test on the first visit.", "Strict SPF for two weeks.", true, false));

        menu.add(service("svc-brow", "SIL-K-04", "Brow Architecture & Tint", "cat-skin",
                "Mapped, shaped, tinted.",
                "Brow mapping, wax or thread, and a tint matched to the hair.",
                "Brows are the frame. Everything else is the picture.",
                35, 1200, null, List.of("stf-noor", "stf-taran"), List.of("brow", "tint", "threading", "shaping"),
                "Grow for two weeks beforehand.", "Every four weeks.", true, false));

        menu.add(service("svc-threading", "SIL-K-05", "Full-Face Threading", "cat-skin",
                "Brow, upper lip, forehead, sideburns.",
                "Traditional threading across the full face.",
                "Thirty minutes, no chemicals, no downtime.",
                30, 700, null, List.of("stf-noor", "stf-taran"), List.of("threading", "face", "quick"),
                "Nothing.", "Soothing gel applied afterwards.", true, false));

        menu.add(service("svc-lash-lift", "SIL-K-06", "Lash Lift & Tint", "cat-skin",
                "Eight weeks of mascara-free mornings.",
                "A perm and tint for the natural lash.",
                "The best return per minute on the entire menu.",
                60, 2800, null, List.of("stf-noor"), List.of("lash", "lift", "tint"),
                "Come without eye make-up.", "Keep dry for 24 hours.", true, false));

        // ---- nails
        menu.add(service("svc-manicure", "SIL-N-01", "Classic Manicure", "cat-nails",
                "Soak, shape, cuticle, massage, polish.",
                "A full classic manicure with a hand massage.",
                "Hands are the second face.",
                45, 1100, null, List.of("stf-taran"), List.of("manicure", "nails", "classic"),
                "Remove old polish if you can.", "Oil the cuticles nightly.", true, false));

        menu.add(service("svc-gel-manicure", "SIL-N-02", "Gel Manicure", "cat-nails",
                "Three weeks, no chips.",
                "A gel manicure cured under LED, finished with cuticle oil.",
                "Chosen for weddings and for typing.",
                60, 1900, null, List.of("stf-taran"), List.of("gel", "manicure", "nails"),
                "Nothing.", "Do not peel it off \u2014 book the soak.", true, true));

        menu.add(service("svc-pedicure", "SIL-N-03", "Spa Pedicure", "cat-nails",
                "Soak, scrub, mask, massage, polish.",
                "A spa pedicure with a mineral soak and a foot mask.",
                "Forty-five minutes that change how you walk out.",
                60, 1600, null, List.of("stf-taran", "stf-noor"),
                List.of("pedicure", "spa", "feet"), "Nothing.", "Moisturise nightly.", true, false));

        menu.add(service("svc-gel-extensions", "SIL-N-04", "Gel Extensions", "cat-nails",
                "A full set, any length, any shape.",
                "Sculpted gel extensions with your chosen shape and finish.",
                "Two hours now, four weeks of not thinking about it.",
                120, 3800, List.of(variant("var-gel-art", "Nail art, per set", 30, 900, null)),
                List.of("stf-taran"), List.of("extensions", "gel", "nails", "art"),
                "Nothing.", "Infill at week three.", true, false));

        // ---- bridal
        menu.add(service("svc-bridal-full", "SIL-B-01", "Bridal Atelier \u2014 Full Look", "cat-bridal",
                "Hair, make-up, drape, lashes, touch-up kit.",
                "Four hours with the bridal lead: trial-informed hair, HD make-up, drape and a touch-up kit.",
                "One photograph, forty years of looking at it. We plan for that.",
                240, 32000, List.of(variant("var-br-airbrush", "Airbrush finish", 30, 4000, null)),
                List.of("stf-simran", "stf-meher"), List.of("bridal", "wedding", "makeup", "drape"),
                "Trial six weeks ahead; no new skincare in the last fortnight.",
                "Blotting papers in the kit; the touch-up artist can stay.", true, true));

        menu.add(service("svc-engagement-look", "SIL-B-02", "Engagement & Reception Look", "cat-bridal",
                "Hair, make-up and drape for the second-biggest day.",
                "Three hours of hair, make-up and drape for the engagement or reception.",
                "Same craft as the wedding day, half the nerves.",
                180, 18000, null, List.of("stf-simran"), List.of("bridal", "engagement", "reception", "makeup"),
                "Trial recommended.", "Book the touch-up slot at the venue.", true, false));

        menu.add(service("svc-family-drape", "SIL-B-03", "Sibling & Family Drape", "cat-bridal",
                "Hair and drape for the people in the photographs with you.",
                "Ninety minutes of hair styling and drape for a sibling, parent or friend.",
                "The family arrives at eleven; the photographs are at one.",
                90, 6500, null, List.of("stf-simran", "stf-taran"),
                List.of("bridal", "family", "drape", "hair"), "Pin-proof hair ties help.",
                "A second pin kit is provided.", true, false));

        menu.add(service("svc-pre-bridal", "SIL-B-04", "Pre-Bridal Course", "cat-bridal",
                "Four sessions over six weeks.",
                "A staged course: two facials, a hair ritual, a body polish and a brow shape.",
                "Glow is scheduled, not summoned.",
                180, 24000, null, List.of("stf-noor", "stf-simran"),
                List.of("bridal", "pre-bridal", "course", "skin"), "Start eight weeks before the date.",
                "Sessions are booked at the desk.", true, false));

        // ---- gentlemen
        menu.add(service("svc-gents-cut", "SIL-G-01", "Gentlemen's Cut & Beard Sculpt", "cat-gents",
                "Cut, beard line, hot towel, done in forty-five.",
                "A scissor-or-clipper cut with a sculpted beard line and a hot towel finish.",
                "The Thursday-evening appointment, every Thursday.",
                45, 1200, null, List.of("stf-rehan", "stf-aarav"),
                List.of("men", "cut", "beard", "gentlemen"), "Two weeks of growth on the beard helps.",
                "Beard oil nightly.", true, true));

        menu.add(service("svc-shave", "SIL-G-02", "Hot Towel Shave & Beard Detail", "cat-gents",
                "Straight razor, hot towel, cold towel.",
                "A traditional hot-towel shave with a straight razor and a finishing balm.",
                "Thirty-five minutes of a very old craft.",
                35, 900, null, List.of("stf-rehan"), List.of("shave", "beard", "men", "hot towel"),
                "Nothing.", "Balm, not alcohol.", true, false));

        menu.add(service("svc-grey-blend", "SIL-G-03", "Grey Blending", "cat-gents",
                "Ten years off, without looking dyed.",
                "A five-minute camo colour that softens grey rather than erasing it.",
                "Nobody says you coloured it. That is the point.",
                45, 1800, null, List.of("stf-rehan", "stf-ishaan"),
                List.of("men", "colour", "grey", "camo"), "Patch test on first visit.",
                "Every four weeks.", true, false));

        for (ServiceItem item : menu) salon.services.put(item);
    }

    // ------------------------------------------------------------------ lookbook

    private static void looks(Salon salon) {
        salon.looks.put(new Look("look-copper", "Copper Curtain", "AUTUMN", "stf-ishaan",
                List.of("svc-balayage", "svc-toner-gloss", "svc-signature-cut"),
                List.of("#8C4A2F", "#C9793F", "#E8C39E"), "Warm copper melted through a dark base.",
                "Painted in three passes, toned twice. The grow-out is the design.",
                "look-copper", 240, true, true, LocalDateTime.now().minusDays(60).toString()));
        salon.looks.put(new Look("look-monsoon", "Monsoon Straight", "MONSOON", "stf-meher",
                List.of("svc-keratin", "svc-scalp-detox"),
                List.of("#2F3B36", "#6E7F74", "#C9CFC6"), "Frizz out, movement in \u2014 for July.",
                "Sealed plate by plate, then a scalp detox to undo the humidity.",
                "look-monsoon", 255, true, true, LocalDateTime.now().minusDays(45).toString()));
        salon.looks.put(new Look("look-bride", "The Long Photograph", "WINTER", "stf-simran",
                List.of("svc-bridal-full", "svc-hydra-facial", "svc-lash-lift"),
                List.of("#7A2E3A", "#C9A96A", "#F4F1EA"), "Bordeaux, gold and a great deal of patience.",
                "A four-hour build with a trial six weeks earlier and a touch-up kit for the venue.",
                "look-bride", 240, true, true, LocalDateTime.now().minusDays(30).toString()));
        salon.looks.put(new Look("look-bob", "The Blunt Bob", "SPRING", "stf-aarav",
                List.of("svc-signature-cut", "svc-blow-dry"),
                List.of("#151310", "#3A352E", "#CFC8BA"), "One line, no layers, absolute precision.",
                "Cut dry so the line stays where we put it.",
                "look-bob", 95, false, true, LocalDateTime.now().minusDays(80).toString()));
        salon.looks.put(new Look("look-silver", "Silver Discipline", "AUTUMN", "stf-rehan",
                List.of("svc-gents-cut", "svc-grey-blend"),
                List.of("#8E9298", "#C4C8CC", "#2A2C2F"), "Grey kept, not hidden.",
                "A camo rinse and a clean beard line; four weeks of looking intentional.",
                "look-silver", 90, false, true, LocalDateTime.now().minusDays(20).toString()));
        salon.looks.put(new Look("look-glow", "Twelve-Week Glow", "SUMMER", "stf-noor",
                List.of("svc-signature-facial", "svc-peel", "svc-brow"),
                List.of("#E8C39E", "#C9A96A", "#F4F1EA"), "A course, not a visit.",
                "Staged peels with strict SPF; the brow frames the whole thing.",
                "look-glow", 170, false, true, LocalDateTime.now().minusDays(15).toString()));
        salon.looks.put(new Look("look-nail", "Oxblood Gel", "WINTER", "stf-taran",
                List.of("svc-gel-manicure", "svc-manicure"),
                List.of("#4A1520", "#7A2E3A", "#C9A96A"), "Three weeks of oxblood.",
                "Sculpted, cured, oiled \u2014 and a soak-off booked at week three.",
                "look-nail", 60, false, true, LocalDateTime.now().minusDays(10).toString()));
    }

    // ------------------------------------------------------------------ testimonials

    private static void testimonials(Salon salon) {
        salon.testimonials.put(new Testimonial("tst-1", "Ritika Bansal", "@ritika.reads",
                "Ishaan fixed four years of box dye in two sittings and told me the truth about the first one. I have never gone anywhere else.",
                5, List.of("svc-balayage"), "stf-ishaan", LocalDate.now().minusDays(18), true, true, "google",
                LocalDateTime.now().minusDays(18).toString()));
        salon.testimonials.put(new Testimonial("tst-2", "Harpreet Singh Gill", "@hp.gill",
                "Thursday at six, cut and beard, out by quarter to seven. Same chair, same towel, same conversation.",
                5, List.of("svc-gents-cut"), "stf-rehan", LocalDate.now().minusDays(31), true, true, "walk-in",
                LocalDateTime.now().minusDays(31).toString()));
        salon.testimonials.put(new Testimonial("tst-3", "Ananya Sodhi", "@ananya.s",
                "Meher cut my hair for my wedding and then again the morning after, because I panicked. Both were perfect.",
                5, List.of("svc-bridal-full", "svc-signature-cut"), "stf-meher", LocalDate.now().minusDays(52), true, true,
                "google", LocalDateTime.now().minusDays(52).toString()));
        salon.testimonials.put(new Testimonial("tst-4", "Devika Malhotra", "@devika.m",
                "The keratin ritual got me through a Ludhiana July. That is the whole review.",
                4, List.of("svc-keratin"), "stf-meher", LocalDate.now().minusDays(9), true, false, "instagram",
                LocalDateTime.now().minusDays(9).toString()));
        salon.testimonials.put(new Testimonial("tst-5", "Jasleen Kaur", "@jasleen",
                "Noor mapped my brows and explained every step. I finally know what shape suits me.",
                5, List.of("svc-brow"), "stf-noor", LocalDate.now().minusDays(4), true, false, "google",
                LocalDateTime.now().minusDays(4).toString()));
        salon.testimonials.put(new Testimonial("tst-6", "Vikram Oberoi", "@v.oberoi",
                "Booked online at eleven at night, got a reminder the next morning, paid by UPI at the desk. It just works.",
                4, List.of("svc-signature-cut"), "stf-aarav", LocalDate.now().minusDays(2), false, false, "web",
                LocalDateTime.now().minusDays(2).toString()));
    }

    // ------------------------------------------------------------------ retail

    private static Product product(String id, String sku, String name, String brand, String category,
                                   String description, int price, int cost, int taxPct, int stock, int reorder,
                                   String unit, String supplier, List<String> tags, double usage,
                                   List<String> linked) {
        LocalDateTime now = LocalDateTime.now().minusDays(120);
        return new Product(id, sku, name, brand, category, description, Money(price), Money(cost), Money(taxPct),
                stock, reorder, Math.max(4, reorder * 2), unit, supplier, "+91 98150 00000", tags,
                id.replace("prd-", "img-"), usage, linked, true, linked == null || linked.isEmpty(), 0, now, now);
    }

    private static void products(Salon salon) {
        List<Product> shelf = new ArrayList<>();
        shelf.add(product("prd-olaplex3", "OLX-003", "Olaplex No.3 Hair Perfector", "Olaplex", "Treatment",
                "At-home bond builder, 100 ml. The single most recommended product in the salon.",
                3200, 2100, 18, 24, 8, "bottle", "Olaplex India",
                List.of("bond", "repair", "bestseller"), 0.0, List.of("svc-olaplex", "svc-balayage")));
        shelf.add(product("prd-kerastase-shampoo", "KER-250", "K\u00e9rastase Bain Chroma Respect", "K\u00e9rastase", "Shampoo",
                "Sulphate-free, colour-safe, 250 ml.", 2650, 1750, 18, 18, 6, "bottle", "L'Or\u00e9al Professional",
                List.of("colour", "sulphate-free", "shampoo"), 0.0, List.of("svc-global-colour", "svc-toner-gloss")));
        shelf.add(product("prd-moroccanoil", "MRC-100", "Moroccanoil Treatment", "Moroccanoil", "Oil",
                "Argan-oil finishing treatment, 100 ml.", 2900, 1900, 18, 12, 5, "bottle", "Moroccanoil Asia",
                List.of("oil", "shine", "finish"), 0.0, List.of("svc-blow-dry")));
        shelf.add(product("prd-scalp-serum", "SIL-SS50", "Sillage Scalp Serum", "Sillage House Blend", "Scalp",
                "House blend with niacinamide and peppermint, 50 ml.", 1800, 620, 18, 30, 10, "bottle",
                "In-house", List.of("scalp", "house blend", "margin"), 0.5, List.of("svc-scalp-detox")));
        shelf.add(product("prd-heat-protect", "THR-200", "Thermal Heat Protectant", "Wella Professionals", "Styling",
                "Spray protectant to 220\u00b0C, 200 ml.", 1450, 880, 18, 9, 8, "bottle", "Wella India",
                List.of("heat", "styling", "protect"), 0.4, List.of("svc-blow-dry", "svc-updo", "svc-keratin")));
        shelf.add(product("prd-wella-toner", "WEL-T18", "Wella Colour Fresh Toner T18", "Wella Professionals", "Colour",
                "Salon-only demi toner, 60 ml. Not for retail.", 980, 520, 18, 22, 12, "tube", "Wella India",
                List.of("toner", "salon only"), 1.0, List.of("svc-toner-gloss", "svc-balayage", "svc-highlights")));
        shelf.add(product("prd-dermalogica", "DRM-150", "Dermalogica Daily Microfoliant", "Dermalogica", "Skin",
                "Rice-enzyme powder exfoliant, 74 g.", 4600, 3100, 18, 7, 4, "jar", "Dermalogica India",
                List.of("exfoliant", "skin", "premium"), 0.0, List.of("svc-signature-facial")));
        shelf.add(product("prd-spf50", "UVB-050", "Ultra Sheer SPF 50", "Neutrogena", "Skin",
                "Lightweight daily SPF, 50 ml.", 890, 480, 18, 40, 15, "tube", "Johnson & Johnson",
                List.of("spf", "daily", "skin"), 0.2, List.of("svc-peel", "svc-signature-facial")));
        shelf.add(product("prd-oil-gel", "OPI-G16", "OPI Infinite Gel \u2014 Oxblood", "OPI", "Nails",
                "Salon gel polish, 15 ml.", 1650, 900, 18, 14, 8, "bottle", "OPI India",
                List.of("gel", "nails", "colour"), 1.0, List.of("svc-gel-manicure", "svc-gel-extensions")));
        shelf.add(product("prd-cuticle-oil", "CND-OIL", "CND SolarOil", "CND", "Nails",
                "Cuticle oil with jojoba and rice bran, 15 ml.", 1250, 620, 18, 5, 12, "bottle", "CND Asia",
                List.of("nails", "oil", "low stock"), 0.3, List.of("svc-manicure", "svc-gel-manicure")));
        shelf.add(product("prd-beard-oil", "BRD-030", "Beard Oil \u2014 Cedar & Vetiver", "Bombay Shaving Co.", "Grooming",
                "30 ml, unscented enough for the office.", 780, 380, 18, 26, 10, "bottle", "Bombay Shaving Co.",
                List.of("beard", "men", "gift"), 0.2, List.of("svc-gents-cut", "svc-shave")));
        shelf.add(product("prd-silk-scrunchie", "SIL-SK2", "Silk Scrunchie, Set of Two", "Sillage Atelier", "Accessory",
                "Mulberry silk, bone and bordeaux.", 950, 340, 18, 35, 12, "set", "In-house",
                List.of("silk", "accessory", "gift", "house"), 0.0, List.of()));
        shelf.add(product("prd-gift-set", "SIL-GIFT", "The Sillage Gift Set", "Sillage Atelier", "Gift",
                "Scalp serum, silk scrunchies and a \u20B92,000 gift card in a bone box.", 4200, 1900, 18, 3, 4,
                "box", "In-house", List.of("gift", "set", "festive"), 0.0, List.of()));
        shelf.add(product("prd-clarifying", "KER-CL250", "Clarifying Shampoo", "K\u00e9rastase", "Shampoo",
                "Pre-service cleanse, 250 ml. Salon use with retail refill.", 2200, 1400, 18, 11, 6, "bottle",
                "L'Or\u00e9al Professional", List.of("clarify", "pre-service"), 0.6,
                List.of("svc-scalp-detox", "svc-keratin")));
        for (Product item : shelf) salon.products.put(item);
    }

    // ------------------------------------------------------------------ promotions

    private static void promos(Salon salon) {
        LocalDate today = LocalDate.now();
        salon.promos.put(new PromoCode(Util.id("prm"), "FIRSTSILLAGE", "Ten per cent off your first chair",
                PromoCode.PERCENT, new BigDecimal("10.00"), new BigDecimal("1500.00"), new BigDecimal("1200.00"),
                List.of(), List.of(), today.minusDays(90), today.plusDays(120), 500, 42, true, false,
                today.minusDays(90).toString()));
        salon.promos.put(new PromoCode(Util.id("prm"), "MONSOONGLOSS", "A free toner & gloss with any colour service",
                PromoCode.FREE_ADDON, new BigDecimal("0.00"), new BigDecimal("3000.00"), new BigDecimal("1600.00"),
                List.of("cat-colour"), List.of("svc-global-colour", "svc-balayage", "svc-highlights"),
                today.minusDays(20), today.plusDays(45), 120, 17, true, false, today.minusDays(20).toString()));
        salon.promos.put(new PromoCode(Util.id("prm"), "ATELIER500", "\u20B9500 off any bill above \u20B93,000",
                PromoCode.FLAT, new BigDecimal("500.00"), new BigDecimal("3000.00"), new BigDecimal("500.00"),
                List.of(), List.of(), today.minusDays(40), today.plusDays(60), 200, 63, true, false,
                today.minusDays(40).toString()));
        salon.promos.put(new PromoCode(Util.id("prm"), "WEDDING10", "Ten per cent off bridal, November to February",
                PromoCode.PERCENT, new BigDecimal("10.00"), new BigDecimal("10000.00"), new BigDecimal("6000.00"),
                List.of("cat-bridal"), List.of(), today.minusDays(10), today.plusDays(100), 60, 4, true, false,
                today.minusDays(10).toString()));
        salon.promos.put(new PromoCode(Util.id("prm"), "TUESDAYQUIET", "Expired test code \u2014 kept for the audit trail",
                PromoCode.PERCENT, new BigDecimal("15.00"), new BigDecimal("1000.00"), new BigDecimal("900.00"),
                List.of(), List.of(), today.minusDays(200), today.minusDays(30), 100, 88, false, false,
                today.minusDays(200).toString()));
    }
}
