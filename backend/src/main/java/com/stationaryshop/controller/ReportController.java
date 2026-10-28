package com.stationaryshop.controller;

import com.stationaryshop.service.InventoryService;
import com.stationaryshop.service.ReportService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** Dashboard and report endpoints. */
@RestController
@RequestMapping("/api")
public class ReportController {
    private final ReportService reports;
    private final InventoryService inventory;

    public ReportController(ReportService reports, InventoryService inventory) {
        this.reports = reports;
        this.inventory = inventory;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    /** Report 1: Sales and Revenue. */
    @GetMapping("/reports/sales")
    public Map<String, Object> sales(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.sales(from, to);
    }

    /** Report 2: Inventory and Low Stock (filters: category and stock status). */
    @GetMapping("/reports/low-stock")
    public List<Map<String, Object>> lowStockReport(@RequestParam(name = "category_id", required = false) Integer categoryId,
                                                    @RequestParam(name = "status", defaultValue = "") String status) {
        return inventory.list("", categoryId, null, status);
    }

    @GetMapping("/reports/top-selling")
    public List<Map<String, Object>> topSelling() {
        return reports.topSelling();
    }

    @GetMapping("/reports/customer-spending")
    public List<Map<String, Object>> customerSpending() {
        return reports.customerSpending();
    }
}
