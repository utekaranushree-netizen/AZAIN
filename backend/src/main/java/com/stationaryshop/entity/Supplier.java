package com.stationaryshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

/** Maps the table suppliers. */
@Entity
@Table(name = "suppliers")
public class Supplier {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer supplierId;

    @NotBlank(message = "Supplier name is required")
    @Size(max = 80, message = "Supplier name can have at most 80 characters")
    public String supplierName;

    @Size(max = 60, message = "Contact person can have at most 60 characters")
    public String contactPerson;

    @Pattern(regexp = "^[0-9+\\- ]{0,15}$", message = "Phone may contain digits, +, - and spaces (max 15 characters)")
    public String phone;

    @Email(message = "Email address is not valid")
    @Size(max = 80, message = "Email can have at most 80 characters")
    public String email;

    @Size(max = 40, message = "City can have at most 40 characters")
    public String city;
}
