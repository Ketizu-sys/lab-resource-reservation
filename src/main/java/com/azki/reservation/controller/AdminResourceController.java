package com.azki.reservation.controller;

import com.azki.reservation.config.OpenApiConfig;
import com.azki.reservation.dto.admin.AdminResourceRequest;
import com.azki.reservation.dto.resource.ResourceResponseDto;
import com.azki.reservation.service.AdminResourceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/resources")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminResourceController {
    private final AdminResourceService service;

    @PostMapping public ResponseEntity<ResourceResponseDto> create(@Valid @RequestBody AdminResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
    @GetMapping public Page<ResourceResponseDto> findAll(Pageable pageable) { return service.findAll(pageable); }
    @PutMapping("/{id}") public ResourceResponseDto update(@PathVariable Long id, @Valid @RequestBody AdminResourceRequest request) {
        return service.update(id, request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> disable(@PathVariable Long id) {
        service.disable(id); return ResponseEntity.noContent().build();
    }
}
