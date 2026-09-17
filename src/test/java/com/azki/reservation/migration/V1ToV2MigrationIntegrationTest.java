package com.azki.reservation.migration;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实 PostgreSQL 验证带 V1 历史数据的数据库可安全升级至 V2。 */
@Testcontainers(disabledWithoutDocker = true)
class V1ToV2MigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("migration_test")
                    .withUsername("migration_test")
                    .withPassword("migration_test");

    @Test
    void shouldUpgradePopulatedV1DatabaseWithoutLosingHistory() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        migrate(dataSource, "classpath:db/changelog/v1-main-changelog.xml");
        insertRepresentativeV1Reservations(jdbc);

        migrate(dataSource, "classpath:db/changelog/db.changelog-master.xml");

        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM z_liq_changelog WHERE id IN " +
                        "('4-introduce-resource-domain', '5-reservation-lifecycle', '6-add-user-role')",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource WHERE name = '默认实验室资源'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM available_slot WHERE resource_id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role IS NULL OR role <> 'USER'", Integer.class));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT r.status FROM reservation r JOIN available_slot s ON s.id = r.available_slot_id " +
                        "WHERE s.end_time < CURRENT_TIMESTAMP ORDER BY r.id LIMIT 1",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE status = 'COMPLETED' AND completed_at IS NOT NULL",
                Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT r.status FROM reservation r JOIN available_slot s ON s.id = r.available_slot_id " +
                        "WHERE s.start_time > CURRENT_TIMESTAMP ORDER BY r.id LIMIT 1",
                String.class));

        // 旧的“一个时段只能有一条历史记录”约束必须已移除，取消历史可以与 ACTIVE 记录共存。
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, status, cancelled_at, cancel_reason,
                     created_by, created_date, version)
                SELECT u.id, s.id, CURRENT_TIMESTAMP, 'CANCELLED', CURRENT_TIMESTAMP, 'migration-test',
                       'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u
                CROSS JOIN LATERAL (
                    SELECT id FROM available_slot WHERE start_time > CURRENT_TIMESTAMP ORDER BY start_time LIMIT 1
                ) s
                WHERE u.email = 'user125@example.com'
                """);

        // 新的部分唯一索引仍必须阻止同一时段出现两条 ACTIVE 预约。
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, status, created_by, created_date, version)
                SELECT u.id, s.id, CURRENT_TIMESTAMP, 'ACTIVE', 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u
                CROSS JOIN LATERAL (
                    SELECT available_slot_id AS id FROM reservation WHERE status = 'ACTIVE' LIMIT 1
                ) s
                WHERE u.email = 'user125@example.com'
                """));
    }

    private void insertRepresentativeV1Reservations(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE available_slot
                SET is_reserved = true
                WHERE id IN (
                    (SELECT id FROM available_slot WHERE end_time < CURRENT_TIMESTAMP ORDER BY end_time LIMIT 1),
                    (SELECT id FROM available_slot WHERE start_time > CURRENT_TIMESTAMP ORDER BY start_time LIMIT 1)
                )
                """);
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, created_by, created_date, version)
                SELECT u.id,
                       (SELECT id FROM available_slot WHERE end_time < CURRENT_TIMESTAMP ORDER BY end_time LIMIT 1),
                       CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u WHERE u.email = 'johndoe1@example.com'
                """);
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, created_by, created_date, version)
                SELECT u.id,
                       (SELECT id FROM available_slot WHERE start_time > CURRENT_TIMESTAMP ORDER BY start_time LIMIT 1),
                       CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u WHERE u.email = 'johndoe2@example.com'
                """);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private void migrate(DataSource dataSource, String changeLog) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setContexts("dev");
        liquibase.setDatabaseChangeLogTable("Z_LIQ_CHANGELOG");
        liquibase.setDatabaseChangeLogLockTable("Z_LIQ_CHANGELOG_LOCK");
        liquibase.afterPropertiesSet();
    }
}
