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
 * 预约记录，把一个用户与一个可用时段关联起来。
 * 数据库对 available_slot_id 设置唯一约束，保证同一时段最多对应一条预约。
 */
public class Reservation extends Auditable{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(optional = false)
    @JoinColumn(name = "available_slot_id", nullable = false)
    private AvailableSlot availableSlot;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

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
