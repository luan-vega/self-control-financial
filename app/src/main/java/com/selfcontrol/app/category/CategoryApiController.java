package com.selfcontrol.app.category;

import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * API JSON de categorias. Em /api/categories usa a sessão + CSRF (o próprio app web); em
 * /api/v1/categories é a API pública, com chave de API (ver SecurityConfig).
 */
@RestController
@RequestMapping({"/api/categories", "/api/v1/categories"})
class CategoryApiController {

    record CategoryRequest(
            @NotBlank @Size(max = Category.MAX_NAME_LENGTH) String name,
            @NotNull TransactionType type,
            String color) {

        CategoryCommand toCommand() {
            return new CategoryCommand(name, type, color);
        }
    }

    record CategoryResponse(UUID id, String name, TransactionType type, String color) {

        static CategoryResponse of(Category c) {
            return new CategoryResponse(c.id(), c.name(), c.type(), c.color());
        }
    }

    private final CategoryClient categories;

    CategoryApiController(CategoryClient categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryResponse> list(@RequestParam(required = false) TransactionType type) {
        List<Category> result = type == null ? categories.list() : categories.list(type);
        return result.stream().map(CategoryResponse::of).toList();
    }

    @GetMapping("/{id}")
    CategoryResponse get(@PathVariable UUID id) {
        return CategoryResponse.of(categories.get(id));
    }

    @PostMapping
    ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        Category created = categories.create(request.toCommand());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(created.id());
        return ResponseEntity.created(location).body(CategoryResponse.of(created));
    }

    @PutMapping("/{id}")
    CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.of(categories.update(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        categories.delete(id);
    }
}
