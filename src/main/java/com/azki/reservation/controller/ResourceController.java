package com.azki.reservation.controller;

import com.azki.reservation.config.OpenApiConfig;
import com.azki.reservation.dto.resource.ResourceResponseDto;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.service.ResourceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
@Tag(name = "资源查询", description = "查询当前可预约的实验室资源")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ResourceController {

    private final ResourceQueryService resourceQueryService;

    @GetMapping
    @Operation(summary = "分页查询可用资源")
    public Page<ResourceResponseDto> findResources(
            @RequestParam(required = false) ResourceType type,
            @RequestParam(required = false) ResourceStatus status,
            @RequestParam(required = false) String location,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return resourceQueryService.findResources(type, status, location, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询资源详情")
    public ResourceResponseDto findResource(@PathVariable Long id) {
        return resourceQueryService.findResource(id);
    }
}
