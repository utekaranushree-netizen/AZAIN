package com.stationaryshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.DynamicInsert;

/** Maps the table employees. @DynamicInsert lets the database default apply when hired_on is not sent. */
@Entity
@Table(name = "employees")
@DynamicInsert
public class Employee {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer empId;

    @NotBlank(message = "Employee name is required")
    @Size(max = 60, message = "Employee name can have at most 60 characters")
    public String empName;

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "Manager|Cashier|Storekeeper", message = "Role must be Manager, Cashier or Storekeeper")
    public String role;

    @Pattern(regexp = "^[0-9+\\- ]{0,15}$", message = "Phone may contain digits, +, - and spaces (max 15 characters)")
    public String phone;

    @Positive(message = "Salary must be greater than zero")
    @Digits(integer = 8, fraction = 2, message = "Salary can have at most 8 digits and 2 decimals")
    public BigDecimal salary;

    @PastOrPresent(message = "Hire date cannot be in the future")
    public LocalDate hiredOn;
}
