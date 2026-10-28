package com.stationaryshop.controller;

import com.stationaryshop.entity.Category;
import com.stationaryshop.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q) {
        return service.search(q);
    }

    @GetMapping("/{id}")
    public Category get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Category create(@Valid @RequestBody Category body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Category update(@PathVariable Integer id, @Valid @RequestBody Category body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Integer id) {
        service.delete(id);
        return Map.of("message", "Category deleted");
    }
}
