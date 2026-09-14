package com.azki.reservation.repository;

import com.azki.reservation.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
