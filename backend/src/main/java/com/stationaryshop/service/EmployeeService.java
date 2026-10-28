package com.stationaryshop.service;

import com.stationaryshop.entity.Employee;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.repository.EmployeeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService {
    private final EmployeeRepository repo;
    private final JdbcTemplate jdbc;

    public EmployeeService(EmployeeRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String q, String role) {
        String like = Util.like(q);
        List<Object> args = new ArrayList<>(List.of(like, like));
        StringBuilder sql = new StringBuilder(
            "SELECT e.emp_id, e.emp_name, e.role, e.phone, e.salary, e.hired_on, " +
            "       (SELECT COUNT(*) FROM orders o WHERE o.emp_id = e.emp_id) AS orders_handled " +
            "FROM employees e WHERE (e.emp_name ILIKE ? OR COALESCE(e.phone,'') ILIKE ?) ");
        if (Util.has(role)) {
            sql.append("AND e.role = ? ");
            args.add(role);
        }
        sql.append("ORDER BY e.emp_id");
        return jdbc.queryForList(sql.toString(), Util.arr(args));
    }

    public Employee get(Integer id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    public Employee create(Employee in) {
        in.empId = null;
        in.empName = in.empName.trim();
        in.phone = Util.blank(in.phone);
        return repo.saveAndFlush(in);
    }

    public Employee update(Integer id, Employee in) {
        Employee e = get(id);
        e.empName = in.empName.trim();
        e.role = in.role;
        e.phone = Util.blank(in.phone);
        e.salary = in.salary;
        if (in.hiredOn != null) e.hiredOn = in.hiredOn;   // hired_on is NOT NULL
        return repo.saveAndFlush(e);
    }

    public void delete(Integer id) {
        Employee e = get(id);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE emp_id = ?", Integer.class, id);
        if (orders != null && orders > 0)
            throw ApiException.conflict("Employee '" + e.empName + "' has handled " + orders +
                    " order(s) and cannot be deleted, because every order must keep the employee who served it.");
        repo.delete(e);
        repo.flush();
    }
}
