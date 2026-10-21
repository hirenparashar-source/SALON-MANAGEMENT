package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** An outflow, so the profit view is not fiction. */
public record Expense(
        String id,
        LocalDate date,
        String category,
        String vendor,
        String note,
        BigDecimal amount,
        String paymentMethod,
        String createdBy,
        LocalDateTime createdAt) {
}
