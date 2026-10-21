package com.sillage.seed;

import com.sillage.Salon;
import com.sillage.core.Log;
import com.sillage.model.BusinessSettings;

import java.util.Random;

/**
 * First-boot seeding. Deterministic: the same seed produces the same salon, so screenshots,
 * tests and demos all agree.
 */
public final class SeedData {

    /** Fixed so the demo book is reproducible across machines. */
    public static final long SEED = 20261021L;

    private SeedData() {}

    public static void populate(Salon salon) {
        long started = System.nanoTime();
        Random rnd = new Random(SEED);

        if (salon.settings.isEmpty()) salon.settings.put(BusinessSettings.defaults());

        Catalogue.populate(salon);
        People.populate(salon, rnd);
        History.populate(salon, rnd);

        salon.store.flushAll();
        Log.info("seeded the demo salon in %d ms (seed %d)", (System.nanoTime() - started) / 1_000_000, SEED);
    }

    /** A short human-readable summary, printed at boot. */
    public static String summary(Salon salon) {
        return "%d services in %d categories, %d stylists, %d clients, %d bookings, %d invoices, %d products"
                .formatted(salon.services.size(), salon.categories.size(), salon.staff.size(),
                        salon.customers.size(), salon.appointments.size(), salon.invoices.size(),
                        salon.products.size());
    }
}
