package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** A retail product / consumable held in stock. */
public record Product(
        String id,
        String sku,
        String name,
        String brand,
        String category,
        String description,
        BigDecimal price,
        BigDecimal costPrice,
        BigDecimal taxPct,
        int stock,
        int reorderLevel,
        int reorderQty,
        String unit,
        String supplier,
        String supplierPhone,
        List<String> tags,
        String imageKey,
        double usagePerService,
        List<String> linkedServiceIds,
        boolean active,
        boolean retailOnly,
        int soldCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean isLow() {
        return stock <= reorderLevel;
    }

    public boolean isOut() {
        return stock <= 0;
    }

    public BigDecimal stockValue() {
        return (costPrice == null ? BigDecimal.ZERO : costPrice).multiply(BigDecimal.valueOf(Math.max(0, stock)));
    }

    public BigDecimal retailValue() {
        return (price == null ? BigDecimal.ZERO : price).multiply(BigDecimal.valueOf(Math.max(0, stock)));
    }

    public BigDecimal marginPct() {
        if (price == null || costPrice == null || price.signum() == 0) return BigDecimal.ZERO;
        return price.subtract(costPrice).multiply(BigDecimal.valueOf(100)).divide(price, 1, java.math.RoundingMode.HALF_UP);
    }

    public Product withStock(int newStock) {
        return new Product(id, sku, name, brand, category, description, price, costPrice, taxPct, newStock,
                reorderLevel, reorderQty, unit, supplier, supplierPhone, tags, imageKey, usagePerService,
                linkedServiceIds, active, retailOnly, soldCount, createdAt, LocalDateTime.now());
    }

    public Product withSold(int newSoldCount) {
        return new Product(id, sku, name, brand, category, description, price, costPrice, taxPct, stock,
                reorderLevel, reorderQty, unit, supplier, supplierPhone, tags, imageKey, usagePerService,
                linkedServiceIds, active, retailOnly, newSoldCount, createdAt, LocalDateTime.now());
    }
}
