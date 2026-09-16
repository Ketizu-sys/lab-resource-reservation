package com.azki.reservation.dto.admin;

import com.azki.reservation.dto.reservation.ReservationDetailsDto;

/** 管理员预约视图，在普通预约详情之外增加用户标识。 */
public record AdminReservationResponse(Long userId, String userEmail, ReservationDetailsDto reservation) { }
