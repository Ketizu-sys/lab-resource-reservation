package com.azki.reservation.migration;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实 MySQL 验证带 V1 历史数据的数据库可安全升级至 V2。 */
@Testcontainers(disabledWithoutDocker = true)
class V1ToV2MigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
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

        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM Z_LIQ_CHANGELOG WHERE id IN " +
                        "('4-introduce-resource-domain', '5-reservation-lifecycle', " +
                        "'6-add-user-role', '7-unify-time-semantics', " +
                        "'8-add-demo-resources-and-slots', '8-remove-resource-version-labels')",
                Integer.class));
        assertEquals("datetime", columnType(jdbc, "reservation", "reserved_at"));
        assertEquals("datetime", columnType(jdbc, "reservation", "created_date"));
        assertEquals("datetime", columnType(jdbc, "users", "created_date"));
        assertEquals("datetime", columnType(jdbc, "available_slot", "start_time"));
        assertEquals("datetime", columnType(jdbc, "available_slot", "end_time"));
        assertEquals("bit", columnType(jdbc, "available_slot", "is_reserved"));
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('users', 'resource', 'available_slot', 'reservation')
                  AND engine = 'InnoDB'
                """, Integer.class));
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource WHERE name = '默认实验室资源'", Integer.class));
        assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource WHERE " +
                        "LOWER(CONCAT_WS(' ', name, type, location, status, description)) " +
                        "LIKE '%v1%' OR LOWER(CONCAT_WS(' ', name, type, location, status, description)) LIKE '%v2%'",
                Integer.class));
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM available_slot s JOIN resource r ON r.id = s.resource_id " +
                        "WHERE r.name <> '默认实验室资源' AND s.start_time > CURRENT_TIMESTAMP",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM available_slot s JOIN resource r ON r.id = s.resource_id " +
                        "WHERE r.status <> 'ACTIVE' AND r.name <> '默认实验室资源'",
                Integer.class));
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
                CROSS JOIN (SELECT id FROM available_slot
                            WHERE start_time > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')
                            ORDER BY start_time LIMIT 1) s
                WHERE u.email = 'user125@example.com'
                """);

        // 新的部分唯一索引仍必须阻止同一时段出现两条 ACTIVE 预约。
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, status, created_by, created_date, version)
                SELECT u.id, s.id, CURRENT_TIMESTAMP, 'ACTIVE', 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u
                CROSS JOIN (SELECT available_slot_id AS id FROM reservation WHERE status = 'ACTIVE' LIMIT 1) s
                WHERE u.email = 'user125@example.com'
                """));
    }

    private void insertRepresentativeV1Reservations(JdbcTemplate jdbc) {
        Long pastSlotId = jdbc.queryForObject(
                "SELECT id FROM available_slot WHERE end_time < " +
                        "CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00') ORDER BY end_time LIMIT 1",
                Long.class);
        Long futureSlotId = jdbc.queryForObject(
                "SELECT id FROM available_slot WHERE start_time > " +
                        "CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00') ORDER BY start_time LIMIT 1",
                Long.class);
        jdbc.update("UPDATE available_slot SET is_reserved = true WHERE id IN (?, ?)",
                pastSlotId, futureSlotId);
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, created_by, created_date, version)
                SELECT u.id, ?,
                       CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u WHERE u.email = 'johndoe1@example.com'
                """, pastSlotId);
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, created_by, created_date, version)
                SELECT u.id, ?,
                       CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP, 0
                FROM users u WHERE u.email = 'johndoe2@example.com'
                """, futureSlotId);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MYSQL.getDriverClassName());
        String jdbcUrl = MYSQL.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        dataSource.setUrl(jdbcUrl + separator
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
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
