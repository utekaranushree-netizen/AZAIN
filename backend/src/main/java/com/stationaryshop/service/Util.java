package com.stationaryshop.service;

import com.stationaryshop.exception.ApiException;
import java.time.LocalDate;
import java.util.List;

/** Small helpers shared by the services. */
final class Util {
    private Util() { }

    /** Blank text becomes NULL so that empty phone/email values do not clash with UNIQUE constraints. */
    static String blank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** Builds a LIKE pattern for case-insensitive "contains" searches. */
    static String like(String q) {
        return "%" + (q == null ? "" : q.trim()) + "%";
    }

    static boolean has(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Lower-case e-mail so that A@x.com and a@x.com are the same value for the UNIQUE rule. */
    static String lower(String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    /** A date range must not be reversed. */
    static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to))
            throw ApiException.badRequest("The 'from' date cannot be after the 'to' date");
    }

    static Object[] arr(List<Object> list) {
        return list.toArray();
    }
}
