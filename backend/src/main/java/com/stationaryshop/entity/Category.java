package com.stationaryshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Maps the table categories. */
@Entity
@Table(name = "categories")
public class Category {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer categoryId;

    @NotBlank(message = "Category name is required")
    @Size(max = 50, message = "Category name can have at most 50 characters")
    public String categoryName;

    @Size(max = 200, message = "Description can have at most 200 characters")
    public String description;
}
