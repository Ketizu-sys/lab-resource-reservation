package com.azki.reservation.controller;

import com.azki.reservation.config.OpenApiConfig;
import com.azki.reservation.dto.admin.*;
import com.azki.reservation.service.AdminSlotService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/slots")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminSlotController {
    private final AdminSlotService service;

    @PostMapping public ResponseEntity<AdminSlotResponse> create(@Valid @RequestBody AdminSlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
    @PostMapping("/batch") public ResponseEntity<List<AdminSlotResponse>> batch(@Valid @RequestBody BatchSlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBatch(request));
    }
    @GetMapping public Page<AdminSlotResponse> findAll(@ParameterObject Pageable pageable) { return service.findAll(pageable); }
    @PutMapping("/{id}") public AdminSlotResponse update(@PathVariable Long id, @Valid @RequestBody AdminSlotRequest request) {
        return service.update(id, request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id); return ResponseEntity.noContent().build();
    }
}
