package com.azki.reservation.controller;

import com.azki.reservation.config.OpenApiConfig;
import com.azki.reservation.dto.slot.SlotResponseDto;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.service.SlotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "可用时段", description = "查询当前仍可预约的资源时段")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {
    private final SlotQueryService slotQueryService;

    @Operation(summary = "分页查询可用时段")
    @GetMapping
    public Page<SlotResponseDto> findAll(
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @ParameterObject Pageable pageable) {
        return slotQueryService.findAvailableSlots(resourceId, resourceType, start, end, pageable);
    }

    @Operation(summary = "查询单个可用时段")
    @GetMapping("/{id}")
    public SlotResponseDto findOne(@PathVariable Long id) {
        return slotQueryService.findAvailableSlot(id);
    }
}
