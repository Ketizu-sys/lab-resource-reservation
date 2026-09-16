package com.azki.reservation.repository;

import com.azki.reservation.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按邮箱查找用户并按主键升序返回。
     * 正常情况下 email 唯一；返回 List 是为了在历史脏数据存在重复值时仍能兼容读取。
     */
    @Query("SELECT u FROM User u WHERE u.email = :email ORDER BY u.id ASC")
    List<User> findByEmailOrderedById(@Param("email") String email);

    /** 返回邮箱匹配结果中的第一条；不存在时返回空 Optional。 */
    default Optional<User> findByEmail(String email) {
        List<User> users = findByEmailOrderedById(email);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }

    /**
     * 按邮箱锁定用户行，用于串行化同一用户的并发预约检查。
     * 这样两个并发事务不会同时通过“时间是否重叠”的校验。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email ORDER BY u.id ASC")
    List<User> findByEmailForUpdateOrderedById(@Param("email") String email);

    default Optional<User> findByEmailForUpdate(String email) {
        List<User> users = findByEmailForUpdateOrderedById(email);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }
}
