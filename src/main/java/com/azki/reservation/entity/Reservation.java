package com.azki.reservation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@Entity
@Table(name = "reservation")
/**
 * 预约记录，把一个用户与一个可用时段关联起来，并保留完整生命周期。
 *
 * <p>同一时段可以存在多条历史记录，但数据库只允许一条 ACTIVE 预约。</p>
 */
public class Reservation extends Auditable{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "available_slot_id", nullable = false)
    private AvailableSlot availableSlot;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    /** 当前生命周期状态；新建预约默认处于有效状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    /** 用户取消预约的时间，仅 CANCELLED 状态使用。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 预约自然结束并转为 COMPLETED 的时间。 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 取消原因；当前取消接口未接收原因时写入默认说明。 */
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Override
    public final boolean equals(Object o) {
        // Hibernate 可能传入代理对象，因此先取得代理背后的真实实体类型再比较。
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        Reservation that = (Reservation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        // 与 equals 保持一致：代理对象和真实实体使用相同的类型哈希值。
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
