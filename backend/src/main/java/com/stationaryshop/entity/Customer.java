package com.stationaryshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Maps the table customers. Loyalty points are maintained only by the database trigger. */
@Entity
@Table(name = "customers")
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer customerId;

    @NotBlank(message = "Customer name is required")
    @Size(max = 60, message = "Customer name can have at most 60 characters")
    public String customerName;

    @Pattern(regexp = "^[0-9+\\- ]{0,15}$", message = "Phone may contain digits, +, - and spaces (max 15 characters)")
    public String phone;

    @Email(message = "Email address is not valid")
    @Size(max = 80, message = "Email can have at most 80 characters")
    public String email;

    @Size(max = 40, message = "City can have at most 40 characters")
    public String city;

    /** Read-only: never inserted or updated from Java (the payment trigger owns it). */
    @Column(insertable = false, updatable = false)
    public Integer loyaltyPoints;

    /** Read-only: the database default (CURRENT_DATE) is used. */
    @Column(insertable = false, updatable = false)
    public LocalDate joinedOn;
}
