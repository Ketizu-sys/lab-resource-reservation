package com.azki.reservation.dto.admin;

import com.azki.reservation.entity.ResourceStatus;
import java.time.LocalDateTime;

/** 管理接口使用的时段视图，包含占用状态和资源状态。 */
public record AdminSlotResponse(Long id, Long resourceId, String resourceName,
                                ResourceStatus resourceStatus, LocalDateTime startTime,
                                LocalDateTime endTime, boolean reserved) { }
