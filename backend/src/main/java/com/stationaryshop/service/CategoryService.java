package com.stationaryshop.service;

import com.stationaryshop.entity.Category;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.repository.CategoryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {
    private final CategoryRepository repo;
    private final JdbcTemplate jdbc;

    public CategoryService(CategoryRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String q) {
        String like = Util.like(q);
        return jdbc.queryForList(
            "SELECT c.category_id, c.category_name, c.description, " +
            "       (SELECT COUNT(*) FROM products p WHERE p.category_id = c.category_id) AS product_count " +
            "FROM categories c " +
            "WHERE c.category_name ILIKE ? OR COALESCE(c.description, '') ILIKE ? " +
            "ORDER BY c.category_id", like, like);
    }

    public Category get(Integer id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
    }

    public Category create(Category in) {
        in.categoryId = null;
        in.categoryName = in.categoryName.trim();
        in.description = Util.blank(in.description);
        if (repo.existsByCategoryNameIgnoreCase(in.categoryName))
            throw ApiException.conflict("A category named '" + in.categoryName + "' already exists");
        return repo.saveAndFlush(in);
    }

    public Category update(Integer id, Category in) {
        Category c = get(id);
        String name = in.categoryName.trim();
        if (repo.existsByCategoryNameIgnoreCaseAndCategoryIdNot(name, id))
            throw ApiException.conflict("A category named '" + name + "' already exists");
        c.categoryName = name;
        c.description = Util.blank(in.description);
        return repo.saveAndFlush(c);
    }

    public void delete(Integer id) {
        Category c = get(id);
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE category_id = ?", Integer.class, id);
        if (used != null && used > 0)
            throw ApiException.conflict("Category '" + c.categoryName + "' is used by " + used +
                    " product(s) and cannot be deleted. Move those products to another category first.");
        repo.delete(c);
        repo.flush();
    }
}
