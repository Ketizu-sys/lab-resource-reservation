package com.azki.reservation.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Getter
@Setter
@ToString
@SuperBuilder
@MappedSuperclass
@Accessors(chain = true)
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@EntityListeners(AuditingEntityListener.class)
/**
 * 所有持久化实体共用的审计基类。
 *
 * <p>Spring Data JPA 在新增和更新实体时自动填写操作人、时间；
 * version 由 Hibernate 维护，用于发现并发更新冲突。</p>
 */
public abstract class Auditable {
    @CreatedBy
    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreatedDate
    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Column(name = "created_date", nullable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    @LastModifiedBy
    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Version
    @Setter(AccessLevel.NONE)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "version")
    private int version;

    @PrePersist
    protected void prePersist() {
        // 保留生命周期回调，实体首次持久化前由 JPA 调用。
    }

    @PreUpdate
    protected void preUpdate() {
        // 保留生命周期回调，实体更新前由 JPA 调用。
    }
}
