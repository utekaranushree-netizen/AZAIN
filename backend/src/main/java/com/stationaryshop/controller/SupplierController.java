package com.stationaryshop.controller;

import com.stationaryshop.entity.Supplier;
import com.stationaryshop.service.SupplierService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {
    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q) {
        return service.search(q);
    }

    @GetMapping("/{id}/products")
    public List<Map<String, Object>> products(@PathVariable Integer id) {
        return service.products(id);
    }

    @GetMapping("/{id}")
    public Supplier get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Supplier create(@Valid @RequestBody Supplier body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Supplier update(@PathVariable Integer id, @Valid @RequestBody Supplier body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Integer id) {
        service.delete(id);
        return Map.of("message", "Supplier deleted");
    }
}
