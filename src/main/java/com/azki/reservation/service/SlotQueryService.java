package com.azki.reservation.service;

import com.azki.reservation.dto.slot.SlotResponseDto;
import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.SlotNotFoundException;
import com.azki.reservation.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;

/** 提供只读的可用时段查询，并在服务端统一应用可见性规则。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlotQueryService {
    private final TimeSlotRepository timeSlotRepository;
    private final Clock reservationClock;

    public Page<SlotResponseDto> findAvailableSlots(Long resourceId, ResourceType resourceType,
                                                     LocalDateTime start, LocalDateTime end,
                                                     Pageable pageable) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BusinessException("start must be earlier than end");
        }
        LocalDateTime now = LocalDateTime.now(reservationClock);
        LocalDateTime effectiveStart = start == null || start.isBefore(now) ? now : start;
        LocalDateTime effectiveEnd = end == null ? LocalDateTime.of(9999, 12, 31, 23, 59) : end;
        return timeSlotRepository.findAvailableSlots(resourceId, resourceType,
                effectiveStart, effectiveEnd, pageable).map(this::toDto);
    }

    public SlotResponseDto findAvailableSlot(Long id) {
        return timeSlotRepository.findVisibleAvailableById(id, LocalDateTime.now(reservationClock))
                .map(this::toDto)
                .orElseThrow(() -> new SlotNotFoundException("Available slot not found for id: " + id));
    }

    private SlotResponseDto toDto(AvailableSlot slot) {
        var resource = slot.getResource();
        return new SlotResponseDto(slot.getId(),
                new SlotResponseDto.ResourceSummary(resource.getId(), resource.getName(),
                        resource.getType(), resource.getLocation()),
                slot.getStartTime(), slot.getEndTime(), true);
    }
}
