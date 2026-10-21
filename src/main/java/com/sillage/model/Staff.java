package com.sillage.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * A stylist / therapist, with their chair-time availability.
 *
 * @param schedule   one {@link DayHours} per working day; missing days are treated as off
 * @param timeOff    approved leave days that block the calendar
 * @param colorKey   palette token used for the calendar column and avatar
 */
public record Staff(
        String id,
        String name,
        String title,
        String bio,
        String quote,
        List<String> specialties,
        List<String> serviceIds,
        List<DayHours> schedule,
        List<TimeOff> timeOff,
        String phone,
        String email,
        String colorKey,
        double commissionPct,
        double rating,
        int yearsExperience,
        boolean active,
        boolean acceptsWalkIns,
        int bufferMinutes,
        String joinedAt,
        String createdAt,
        String updatedAt) {

    /** Working hours for one weekday, as {@code HH:mm} strings. */
    public record DayHours(String day, String start, String end, boolean closed, String note) {
        public static DayHours of(DayOfWeek day, String start, String end) {
            return new DayHours(day.name(), start, end, false, null);
        }

        public static DayHours off(DayOfWeek day) {
            return new DayHours(day.name(), null, null, true, null);
        }

        public boolean matches(DayOfWeek other) {
            return other != null && day != null && day.equalsIgnoreCase(other.name());
        }
    }

    /** A blocked day (leave, training, holiday). */
    public record TimeOff(String id, LocalDate date, String reason, boolean allDay) {}

    public boolean worksOn(DayOfWeek day) {
        if (schedule == null || day == null) return false;
        for (DayHours hours : schedule) {
            if (hours.matches(day)) return !hours.closed() && hours.start() != null && hours.end() != null;
        }
        return false;
    }

    public DayHours hoursFor(DayOfWeek day) {
        if (schedule == null || day == null) return null;
        for (DayHours hours : schedule) if (hours.matches(day)) return hours;
        return null;
    }

    public boolean offOn(LocalDate date) {
        if (timeOff == null || date == null) return false;
        for (TimeOff off : timeOff) if (date.equals(off.date())) return true;
        return false;
    }

    public boolean certifiedFor(String serviceId) {
        return serviceIds == null || serviceIds.isEmpty() || serviceIds.contains(serviceId);
    }

    public String firstName() {
        if (name == null || name.isBlank()) return "";
        return name.split("\\s+")[0];
    }

    public Staff withRating(double newRating) {
        return new Staff(id, name, title, bio, quote, specialties, serviceIds, schedule, timeOff, phone, email,
                colorKey, commissionPct, newRating, yearsExperience, active, acceptsWalkIns, bufferMinutes,
                joinedAt, createdAt, updatedAt);
    }

    public Staff withTimeOff(List<TimeOff> newTimeOff) {
        return new Staff(id, name, title, bio, quote, specialties, serviceIds, schedule, newTimeOff, phone, email,
                colorKey, commissionPct, rating, yearsExperience, active, acceptsWalkIns, bufferMinutes,
                joinedAt, createdAt, updatedAt);
    }
}
