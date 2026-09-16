package com.azki.reservation.service;

import com.azki.reservation.dto.admin.*;
import com.azki.reservation.entity.*;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.ResourceNotFoundException;
import com.azki.reservation.exception.SlotNotFoundException;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminSlotService {
    private final TimeSlotRepository slots;
    private final ResourceRepository resources;

    @Transactional public AdminSlotResponse create(AdminSlotRequest request) {
        return toDto(saveNew(request.resourceId(), request.startTime(), request.endTime()));
    }

    @Transactional
    public List<AdminSlotResponse> createBatch(BatchSlotRequest request) {
        validateBatch(request);
        List<AdminSlotResponse> result = new ArrayList<>();
        for (LocalDate date = request.startDate(); !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            LocalDateTime cursor = date.atTime(request.dailyStartTime());
            LocalDateTime dailyEnd = date.atTime(request.dailyEndTime());
            while (!cursor.plusMinutes(request.durationMinutes()).isAfter(dailyEnd)) {
                LocalDateTime end = cursor.plusMinutes(request.durationMinutes());
                result.add(toDto(saveNew(request.resourceId(), cursor, end)));
                cursor = end;
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Page<AdminSlotResponse> findAll(Pageable pageable) {
        return slots.findAllByOrderByStartTimeAsc(pageable).map(this::toDto);
    }

    @Transactional
    public AdminSlotResponse update(Long id, AdminSlotRequest request) {
        AvailableSlot slot = slots.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (slot.isReserved()) throw new BusinessException("Reserved slot cannot be modified");
        Resource resource = loadUsableResource(request.resourceId());
        validateRange(request.startTime(), request.endTime());
        if (slots.existsOverlappingSlot(resource.getId(), id, request.startTime(), request.endTime()))
            throw new BusinessException("Time slot overlaps an existing slot");
        slot.setResource(resource); slot.setStartTime(request.startTime()); slot.setEndTime(request.endTime());
        return toDto(slots.saveAndFlush(slot));
    }

    @Transactional
    public void delete(Long id) {
        AvailableSlot slot = slots.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (slot.isReserved()) throw new BusinessException("Reserved slot cannot be deleted");
        slots.delete(slot);
    }

    private AvailableSlot saveNew(Long resourceId, LocalDateTime start, LocalDateTime end) {
        Resource resource = loadUsableResource(resourceId); validateRange(start, end);
        if (slots.existsOverlappingSlot(resourceId, null, start, end))
            throw new BusinessException("Time slot overlaps an existing slot");
        AvailableSlot slot = new AvailableSlot(); slot.setResource(resource); slot.setStartTime(start); slot.setEndTime(end);
        return slots.saveAndFlush(slot);
    }

    private Resource loadUsableResource(Long id) {
        Resource resource = resources.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found for id: " + id));
        if (resource.getStatus() == ResourceStatus.DISABLED) throw new BusinessException("Disabled resource cannot receive slots");
        return resource;
    }
    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) throw new BusinessException("startTime must be earlier than endTime");
    }
    private void validateBatch(BatchSlotRequest q) {
        if (q.startDate().isAfter(q.endDate())) throw new BusinessException("startDate must not be after endDate");
        if (!q.dailyStartTime().isBefore(q.dailyEndTime())) throw new BusinessException("dailyStartTime must be earlier than dailyEndTime");
    }
    private AdminSlotResponse toDto(AvailableSlot s) {
        return new AdminSlotResponse(s.getId(), s.getResource().getId(), s.getResource().getName(),
                s.getResource().getStatus(), s.getStartTime(), s.getEndTime(), s.isReserved());
    }
    private SlotNotFoundException notFound(Long id) { return new SlotNotFoundException("Time slot not found for id: " + id); }
}
