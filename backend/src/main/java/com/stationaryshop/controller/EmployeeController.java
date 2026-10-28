package com.stationaryshop.controller;

import com.stationaryshop.entity.Employee;
import com.stationaryshop.service.EmployeeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q,
                                          @RequestParam(name = "role", defaultValue = "") String role) {
        return service.search(q, role);
    }

    @GetMapping("/{id}")
    public Employee get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Employee create(@Valid @RequestBody Employee body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Employee update(@PathVariable Integer id, @Valid @RequestBody Employee body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Integer id) {
        service.delete(id);
        return Map.of("message", "Employee deleted");
    }
}
