package com.sillage.seed;

import com.sillage.Salon;
import com.sillage.core.Util;
import com.sillage.model.Customer;
import com.sillage.model.Role;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The people: seven chairs, thirty clients and four accounts. */
final class People {

    static final List<Staff> TEAM = new ArrayList<>();
    static final List<Customer> CLIENTS = new ArrayList<>();

    private People() {}

    static void populate(Salon salon, Random rnd) {
        team(salon);
        clients(salon, rnd);
        accounts(salon);
    }

    // ------------------------------------------------------------------ the team

    private static Staff chair(String id, String name, String title, String bio, String quote,
                               List<String> specialties, List<String> serviceIds, String phone, String email,
                               String colorKey, double commission, double rating, int years,
                               boolean walkIns, int buffer, String openedAt, String closedAt) {
        List<Staff.DayHours> schedule = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day == DayOfWeek.MONDAY) {
                schedule.add(Staff.DayHours.off(day));
            } else {
                schedule.add(Staff.DayHours.of(day, openedAt, closedAt));
            }
        }
        Staff member = new Staff(id, name, title, bio, quote, specialties, serviceIds, schedule, List.of(),
                phone, email, colorKey, commission, rating, years, true, walkIns, buffer,
                LocalDate.now().minusYears(years).toString(),
                LocalDateTime.now().minusYears(years).toString(), LocalDateTime.now().toString());
        TEAM.add(member);
        return member;
    }

    private static void team(Salon salon) {
        salon.staff.put(chair("stf-meher", "Meher Kapur", "Founder & Creative Director",
                "Opened the first chair on Ferozepur Road in 1987 and has cut hair every week since. Trained at Vidal "
                        + "Sassoon London, returned to Ludhiana in 1996.",
                "A haircut should look like the person, only more awake.",
                List.of("precision cutting", "curl work", "bridal hair", "colour correction"),
                List.of("svc-signature-cut", "svc-dry-cut", "svc-fringe", "svc-updo", "svc-global-colour",
                        "svc-balayage", "svc-toner-gloss", "svc-colour-consult", "svc-olaplex", "svc-keratin",
                        "svc-bridal-full"),
                "+91 98141 22001", "meher@sillage.example", "bordeaux", 0.0, 4.9, 38, false, 20, "10:00", "19:00"));

        salon.staff.put(chair("stf-aarav", "Aarav Sethi", "Master Stylist",
                "Ten years on the floor, six of them here. Known for dry cutting curls and for never rushing a fringe.",
                "Cut it dry and it stays where you put it.",
                List.of("dry cutting", "curls", "men's cutting", "fringe work"),
                List.of("svc-signature-cut", "svc-dry-cut", "svc-child-cut", "svc-fringe", "svc-olaplex",
                        "svc-scalp-detox", "svc-gents-cut"),
                "+91 98141 22002", "aarav@sillage.example", "gold", 12.0, 4.8, 10, true, 15, "10:30", "19:30"));

        salon.staff.put(chair("stf-ishaan", "Ishaan Bedi", "Head of Colour",
                "Balayage specialist. Spent two winters in Mumbai assisting on editorial colour before joining the atelier.",
                "Colour is a gradient with an exit strategy.",
                List.of("balayage", "colour correction", "foils", "toning", "grey blending"),
                List.of("svc-global-colour", "svc-roots", "svc-balayage", "svc-highlights", "svc-toner-gloss",
                        "svc-colour-consult", "svc-olaplex", "svc-keratin", "svc-grey-blend"),
                "+91 98141 22003", "ishaan@sillage.example", "verdigris", 14.0, 4.9, 8, false, 20, "11:00", "20:00"));

        salon.staff.put(chair("stf-noor", "Noor Sidhu", "Skin & Brow Artist",
                "Aesthetics diploma, Chandigarh. Runs the skin room and the twelve-week glow courses.",
                "Skin is a project with appointments.",
                List.of("facials", "peels", "brow mapping", "lash lifts"),
                List.of("svc-signature-facial", "svc-hydra-facial", "svc-peel", "svc-brow", "svc-threading",
                        "svc-lash-lift", "svc-pre-bridal", "svc-pedicure"),
                "+91 98141 22004", "noor@sillage.example", "champagne", 10.0, 4.7, 6, true, 15, "10:00", "18:30"));

        salon.staff.put(chair("stf-rehan", "Rehan Malhotra", "Gentlemen's Grooming",
                "Straight-razor trained in Amritsar. Keeps the Thursday-evening regulars in order.",
                "Same chair, same towel, same conversation.",
                List.of("hot towel shave", "beard sculpting", "classic cuts", "grey blending"),
                List.of("svc-gents-cut", "svc-shave", "svc-grey-blend"),
                "+91 98141 22005", "rehan@sillage.example", "stone", 11.0, 4.8, 12, true, 10, "11:00", "20:30"));

        salon.staff.put(chair("stf-taran", "Taran Kaur", "Nail Technician & Rituals",
                "Gel sculpting, spa pedicures and the scalp rituals. The calmest hands in the building.",
                "Two hours now, four weeks of not thinking about it.",
                List.of("gel extensions", "nail art", "spa pedicure", "scalp rituals", "blow dry"),
                List.of("svc-manicure", "svc-gel-manicure", "svc-pedicure", "svc-gel-extensions", "svc-hair-spa",
                        "svc-scalp-detox", "svc-champissage", "svc-blow-dry", "svc-child-cut", "svc-brow",
                        "svc-family-drape"),
                "+91 98141 22006", "taran@sillage.example", "bordeaux", 9.0, 4.6, 5, true, 10, "10:00", "19:00"));

        salon.staff.put(chair("stf-simran", "Simran Ahluwalia", "Bridal Lead",
                "Leads the bridal chambre. Two hundred and eleven weddings, and counting.",
                "One photograph, forty years of looking at it.",
                List.of("bridal hair", "HD make-up", "draping", "occasion styling"),
                List.of("svc-bridal-full", "svc-engagement-look", "svc-family-drape", "svc-pre-bridal", "svc-updo",
                        "svc-signature-cut", "svc-blow-dry"),
                "+91 98141 22007", "simran@sillage.example", "gold", 15.0, 5.0, 14, false, 25, "09:30", "18:30"));

        // two days of leave so the availability grid has something to explain
        Staff meher = salon.staff.get("stf-meher");
        salon.staff.put(meher.withTimeOff(List.of(
                new Staff.TimeOff(Util.id("tof"), LocalDate.now().plusDays(3), "Editorial shoot \u2014 Vogue", true),
                new Staff.TimeOff(Util.id("tof"), LocalDate.now().plusDays(4), "Editorial shoot \u2014 Vogue", true))));
        Staff ishaan = salon.staff.get("stf-ishaan");
        salon.staff.put(ishaan.withTimeOff(List.of(
                new Staff.TimeOff(Util.id("tof"), LocalDate.now().plusDays(9), "Colour masterclass, Delhi", true))));
    }

    // ------------------------------------------------------------------ the clients

    private static Customer client(String id, String code, String first, String last, String phone, String email,
                                   String gender, LocalDate birthday, String area, List<String> tags, String notes,
                                   String allergies, String source, String preferredStaff, String preferredTime,
                                   String hairType, String length, String skin, String finish, List<String> goals,
                                   int maintenance, int budgetBand, boolean marketing, boolean data) {
        LocalDateTime created = LocalDateTime.now().minusDays(200 + Math.abs(id.hashCode() % 400));
        return new Customer(id, code, first, last, phone, email, gender, birthday, area, "Ludhiana", "141001",
                tags, notes, allergies, marketing, data, "Atelier", 0, BigDecimal.ZERO, 0, null, null, source,
                preferredStaff, preferredTime, null,
                new Customer.StyleProfile(hairType, "medium", length, "normal", skin, finish, goals,
                        allergies == null ? List.of() : List.of(allergies), maintenance, new BigDecimal(budgetBand)),
                0.0, 0, 0, created, created, false);
    }

    private static void clients(Salon salon, Random rnd) {
        List<Customer> roster = new ArrayList<>();
        roster.add(client("cus-01", "C-00001", "Ritika", "Bansal", "+91 98145 61201", "ritika.bansal@example.com",
                "female", LocalDate.of(1994, 3, 14), "Model Town", List.of("colour", "regular", "referral"),
                "Four years of box dye corrected in 2024; keep the copper warm.", null, "google", "stf-ishaan",
                "weekend", "wavy", "long", "combination", "warm", List.of("copper tones", "healthy ends"), 4, 8000, true, true));
        roster.add(client("cus-02", "C-00002", "Harpreet Singh", "Gill", "+91 98145 61202", "hp.gill@example.com",
                "male", LocalDate.of(1981, 11, 2), "Civil Lines", List.of("men", "weekly", "regular"),
                "Thursday 6pm, always. Hot towel, no small talk before the shave.", null, "walk-in", "stf-rehan",
                "evening", "straight", "short", "normal", "clean", List.of("sharp beard line"), 2, 1500, true, true));
        roster.add(client("cus-03", "C-00003", "Ananya", "Sodhi", "+91 98145 61203", "ananya.sodhi@example.com",
                "female", LocalDate.of(1996, 7, 22), "Sarabha Nagar", List.of("bridal", "vip", "referral"),
                "Married in February. Comes back for cuts and gloss.", "Ammonia sensitivity", "referral", "stf-meher",
                "morning", "straight", "long", "dry", "natural", List.of("shine", "length retention"), 5, 12000, true, true));
        roster.add(client("cus-04", "C-00004", "Devika", "Malhotra", "+91 98145 61204", "devika.m@example.com",
                "female", LocalDate.of(1989, 6, 5), "Bhai Randhir Singh Nagar", List.of("keratin", "regular"),
                "Keratin every monsoon, cut in between.", null, "instagram", "stf-meher", "afternoon", "wavy", "medium",
                "oily", "sleek", List.of("frizz control"), 4, 9000, true, true));
        roster.add(client("cus-05", "C-00005", "Jasleen", "Kaur", "+91 98145 61205", "jasleen.k@example.com",
                "female", LocalDate.of(1999, 12, 30), "Dugri", List.of("skin", "brow", "student"),
                "Brow mapping every four weeks; saving for the lash lift.", null, "instagram", "stf-noor",
                "evening", "straight", "medium", "sensitive", "defined", List.of("brow shape", "clear skin"), 3, 3000,
                true, true));
        roster.add(client("cus-06", "C-00006", "Vikram", "Oberoi", "+91 98145 61206", "vikram.oberoi@example.com",
                "male", LocalDate.of(1977, 1, 19), "Pakhowal Road", List.of("men", "online", "corporate"),
                "Books online at odd hours. Prefers the last slot of the day.", null, "web", "stf-aarav", "evening",
                "straight", "short", "normal", "classic", List.of("low maintenance"), 1, 2000, false, true));
        roster.add(client("cus-07", "C-00007", "Simrat", "Sandhu", "+91 98145 61207", "simrat.sandhu@example.com",
                "female", LocalDate.of(2001, 9, 8), "Gill Road", List.of("nails", "gel", "regular"),
                "Oxblood gel every three weeks, no exceptions.", "Acetone sensitivity", "instagram", "stf-taran",
                "afternoon", "straight", "long", "normal", "bold", List.of("long-wear gel"), 4, 4000, true, true));
        roster.add(client("cus-08", "C-00008", "Karan", "Bajaj", "+91 98145 61208", "karan.bajaj@example.com",
                "male", LocalDate.of(1992, 4, 27), "Model Town", List.of("men", "beard", "new"),
                "New since August; referred by Harpreet.", null, "referral", "stf-rehan", "evening", "curly", "short",
                "normal", "groomed", List.of("beard shaping"), 2, 1500, true, true));
        roster.add(client("cus-09", "C-00009", "Meher", "Bhatia", "+91 98145 61209", "meher.bhatia@example.com",
                "female", LocalDate.of(1985, 2, 11), "Civil Lines", List.of("vip", "colour", "loyal"),
                "Named after the founder, which she never lets us forget. Maison tier.", null, "walk-in", "stf-meher",
                "morning", "wavy", "medium", "combination", "polished", List.of("grey blending", "shine"), 5, 15000,
                true, true));
        roster.add(client("cus-10", "C-00010", "Nikhil", "Sood", "+91 98145 61210", "nikhil.sood@example.com",
                "male", LocalDate.of(1990, 8, 16), "Ferozepur Road", List.of("men", "online"),
                "Books two appointments at a time \u2014 cut and beard, separately.", null, "web", "stf-aarav",
                "weekend", "straight", "short", "oily", "casual", List.of("quick service"), 1, 1800, true, true));
        roster.add(client("cus-11", "C-00011", "Prabhjot", "Kaur", "+91 98145 61211", "prabhjot@example.com",
                "female", LocalDate.of(1988, 10, 3), "Haibowal", List.of("bridal", "family", "vip"),
                "Sister of the February bride. Whole family books through her.", null, "referral", "stf-simran",
                "morning", "straight", "long", "normal", "traditional", List.of("drape", "updo"), 5, 20000, true, true));
        roster.add(client("cus-12", "C-00012", "Aisha", "Qureshi", "+91 98145 61212", "aisha.q@example.com",
                "female", LocalDate.of(1997, 5, 29), "Sarabha Nagar", List.of("skin", "course", "regular"),
                "On the twelve-week glow course with Noor.", "No fragrance", "instagram", "stf-noor", "afternoon",
                "curly", "medium", "sensitive", "natural", List.of("even tone", "no actives"), 4, 5000, true, true));
        roster.add(client("cus-13", "C-00013", "Rohan", "Chawla", "+91 98145 61213", "rohan.chawla@example.com",
                "male", LocalDate.of(2000, 12, 1), "Model Town", List.of("men", "student", "new"),
                "Student; only ever books the \u20B91,200 cut.", null, "web", "stf-aarav", "evening", "curly", "short",
                "normal", "casual", List.of("affordable"), 1, 1200, false, true));
        roster.add(client("cus-14", "C-00014", "Sanya", "Khanna", "+91 98145 61214", "sanya.khanna@example.com",
                "female", LocalDate.of(1993, 3, 30), "Pakhowal Road", List.of("colour", "balayage", "regular"),
                "Balayage twice a year, toner in between.", null, "google", "stf-ishaan", "weekend", "straight",
                "long", "dry", "sun-kissed", List.of("grow-out plan"), 5, 9500, true, true));
        roster.add(client("cus-15", "C-00015", "Gurleen", "Sandhu", "+91 98145 61215", "gurleen.s@example.com",
                "female", LocalDate.of(1986, 7, 12), "Dugri", List.of("spa", "regular", "referral"),
                "Hair spa every fortnight; buys the house scalp serum.", null, "referral", "stf-taran", "morning",
                "wavy", "medium", "dry", "soft", List.of("hydration"), 4, 3500, true, true));
        roster.add(client("cus-16", "C-00016", "Arjun", "Mehra", "+91 98145 61216", "arjun.mehra@example.com",
                "male", LocalDate.of(1983, 9, 24), "Civil Lines", List.of("men", "corporate", "loyal"),
                "Grey blending monthly. Discreet about it.", null, "walk-in", "stf-rehan", "morning", "straight",
                "short", "normal", "sharp", List.of("grey camouflage"), 3, 2500, true, true));
        roster.add(client("cus-17", "C-00017", "Ishita", "Verma", "+91 98145 61217", "ishita.verma@example.com",
                "female", LocalDate.of(1995, 11, 17), "Sarabha Nagar", List.of("nails", "bridal", "vip"),
                "Bridal party in December; gel extensions and a lash lift.", null, "instagram", "stf-taran",
                "weekend", "straight", "long", "combination", "polished", List.of("wedding ready"), 5, 7000, true, true));
        roster.add(client("cus-18", "C-00018", "Manav", "Puri", "+91 98145 61218", "manav.puri@example.com",
                "male", LocalDate.of(1998, 2, 6), "Gill Road", List.of("men", "new", "online"),
                "First visit last month; booked online, paid by UPI.", null, "web", "stf-rehan", "evening", "curly",
                "short", "normal", "textured", List.of("curl definition"), 2, 1600, true, true));
        roster.add(client("cus-19", "C-00019", "Ravneet", "Kaur", "+91 98145 61219", "ravneet@example.com",
                "female", LocalDate.of(1991, 4, 9), "Haibowal", List.of("colour", "correction", "regular"),
                "Ongoing correction project with Ishaan; strand test on file.", "Patch test required", "referral",
                "stf-ishaan", "afternoon", "wavy", "long", "normal", "warm", List.of("even base"), 5, 11000, true, true));
        roster.add(client("cus-20", "C-00020", "Sahil", "Narang", "+91 98145 61220", "sahil.narang@example.com",
                "male", LocalDate.of(1987, 6, 21), "Model Town", List.of("men", "weekly"),
                "Every ten days, cut only. Pays cash.", null, "walk-in", "stf-aarav", "evening", "straight", "short",
                "oily", "clean", List.of("consistency"), 1, 1200, false, true));
        roster.add(client("cus-21", "C-00021", "Tanya", "Ahluwalia", "+91 98145 61221", "tanya.a@example.com",
                "female", LocalDate.of(1994, 1, 25), "Bhai Randhir Singh Nagar", List.of("spa", "keratin", "regular"),
                "Keratin in June, olaplex in December.", null, "google", "stf-meher", "morning", "wavy", "long",
                "dry", "sleek", List.of("smoothness"), 4, 8500, true, true));
        roster.add(client("cus-22", "C-00022", "Kabir", "Sethi", "+91 98145 61222", "kabir.sethi@example.com",
                "male", LocalDate.of(1979, 10, 14), "Civil Lines", List.of("men", "vip", "loyal"),
                "Cut and shave monthly for eleven years. Priv\u00e9 tier.", null, "walk-in", "stf-rehan", "morning",
                "straight", "short", "normal", "classic", List.of("tradition"), 3, 3000, true, true));
        roster.add(client("cus-23", "C-00023", "Nupur", "Garg", "+91 98145 61223", "nupur.garg@example.com",
                "female", LocalDate.of(2002, 8, 3), "Dugri", List.of("skin", "student", "new"),
                "Acne course with Noor; strict about SPF now.", null, "instagram", "stf-noor", "evening", "straight",
                "medium", "oily", "clear", List.of("acne control"), 3, 3800, true, true));
        roster.add(client("cus-24", "C-00024", "Jaspreet", "Singh", "+91 98145 61224", "jaspreet.s@example.com",
                "male", LocalDate.of(1984, 5, 18), "Pakhowal Road", List.of("men", "beard", "regular"),
                "Beard only, no cut. Comes on Saturdays.", null, "walk-in", "stf-rehan", "weekend", "curly", "short",
                "normal", "full", List.of("beard length"), 2, 900, true, true));
        roster.add(client("cus-25", "C-00025", "Anjali", "Rajput", "+91 98145 61225", "anjali.rajput@example.com",
                "female", LocalDate.of(1990, 12, 7), "Sarabha Nagar", List.of("bridal", "family"),
                "Booked the December bridal date for her niece.", null, "referral", "stf-simran", "morning",
                "straight", "long", "normal", "traditional", List.of("family bookings"), 5, 18000, true, true));
        roster.add(client("cus-26", "C-00026", "Yuvraj", "Malhotra", "+91 98145 61226", "yuvraj.m@example.com",
                "male", LocalDate.of(1996, 3, 3), "Model Town", List.of("men", "colour", "new"),
                "First global colour in September; nervous about it.", null, "instagram", "stf-ishaan", "weekend",
                "straight", "short", "normal", "natural", List.of("subtle colour"), 2, 3800, true, true));
        roster.add(client("cus-27", "C-00027", "Sukhmani", "Dhillon", "+91 98145 61227", "sukhmani.d@example.com",
                "female", LocalDate.of(1982, 9, 15), "Haibowal", List.of("cut", "regular", "loyal"),
                "Signature cut every six weeks for nine years. Maison tier.", null, "walk-in", "stf-meher",
                "morning", "wavy", "medium", "combination", "polished", List.of("shape"), 4, 6000, true, true));
        roster.add(client("cus-28", "C-00028", "Zoya", "Sheikh", "+91 98145 61228", "zoya.sheikh@example.com",
                "female", LocalDate.of(1998, 7, 4), "Gill Road", List.of("nails", "art", "regular"),
                "Nail art every month; brings reference images.", null, "instagram", "stf-taran", "afternoon",
                "curly", "long", "normal", "expressive", List.of("nail art"), 4, 4500, true, true));
        roster.add(client("cus-29", "C-00029", "Aman", "Sahota", "+91 98145 61229", "aman.sahota@example.com",
                "male", LocalDate.of(1993, 11, 26), "Ferozepur Road", List.of("men", "no-show"),
                "Two no-shows this year; the desk calls to confirm.", null, "web", "stf-aarav", "evening",
                "straight", "short", "normal", "casual", List.of("convenience"), 1, 1500, false, true));
        roster.add(client("cus-30", "C-00030", "Kiran", "Bhatnagar", "+91 98145 61230", "kiran.b@example.com",
                "female", LocalDate.of(1975, 4, 18), "Civil Lines", List.of("spa", "vip", "loyal"),
                "Fortnightly champissage and hair spa. Priv\u00e9 tier, always cash.", null, "walk-in", "stf-taran",
                "morning", "straight", "short", "dry", "relaxed", List.of("relaxation"), 5, 5000, true, true));

        for (Customer customer : roster) {
            CLIENTS.add(customer);
            salon.customers.put(customer);
        }
    }

    // ------------------------------------------------------------------ accounts

    private static void accounts(Salon salon) {
        salon.auth.ensureOwner("meher", "Sillage#2026", "Meher Kapur");
        var owner = salon.auth.byUsername("meher");
        if (owner != null) {
            salon.users.put(new com.sillage.model.UserAccount(owner.id(), owner.username(), owner.email(),
                    owner.displayName(), Role.OWNER, "cus-09", "stf-meher", "+91 98141 22001", true, true, false,
                    0, null, null, null, 0, owner.preferences(), owner.createdAt(), LocalDateTime.now()));
        }
        salon.auth.provision("frontdesk", "Kiran Dhillon", "frontdesk@sillage.example", "+91 98141 22000",
                "FrontDesk#2026", Role.MANAGER, null, null, false);
        salon.auth.provision("ishaan", "Ishaan Bedi", "ishaan@sillage.example", "+91 98141 22003",
                "Colour#2026", Role.STYLIST, "stf-ishaan", null, true);
        salon.auth.provision("taran", "Taran Kaur", "taran@sillage.example", "+91 98141 22006",
                "Rituals#2026", Role.STYLIST, "stf-taran", null, true);
        salon.auth.provision("ritika", "Ritika Bansal", "ritika.bansal@example.com", "+91 98145 61201",
                "Ritika#2026", Role.CLIENT, null, "cus-01", true);
    }
}
