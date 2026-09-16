package com.azki.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 可预约的实体资源，例如实验室、GPU、会议室或设备。
 *
 * <p>当前一个 AvailableSlot 只允许一条预约，capacity 在本阶段仅表达资源说明，
 * 不表示同一时段允许并发预约的人数。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "resource")
public class Resource extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResourceType type;

    @Column(nullable = false)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResourceStatus status = ResourceStatus.ACTIVE;

    @Column(nullable = false)
    private int capacity = 1;

    @Column(length = 1000)
    private String description;
}
