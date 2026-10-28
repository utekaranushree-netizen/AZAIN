package com.stationaryshop.controller;

import com.stationaryshop.entity.Product;
import com.stationaryshop.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q,
                                          @RequestParam(name = "category_id", required = false) Integer categoryId,
                                          @RequestParam(name = "supplier_id", required = false) Integer supplierId,
                                          @RequestParam(name = "status", defaultValue = "") String status) {
        return service.search(q, categoryId, supplierId, status);
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody Product body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Integer id, @Valid @RequestBody Product body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Integer id) {
        service.delete(id);
        return Map.of("message", "Product deleted");
    }
}
