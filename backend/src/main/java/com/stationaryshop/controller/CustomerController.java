package com.stationaryshop.controller;

import com.stationaryshop.entity.Customer;
import com.stationaryshop.service.CustomerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q) {
        return service.search(q);
    }

    @GetMapping("/{id}/orders")
    public List<Map<String, Object>> orders(@PathVariable Integer id) {
        return service.orderHistory(id);
    }

    @GetMapping("/{id}")
    public Customer get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Customer create(@Valid @RequestBody Customer body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Customer update(@PathVariable Integer id, @Valid @RequestBody Customer body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Integer id) {
        service.delete(id);
        return Map.of("message", "Customer deleted");
    }
}
