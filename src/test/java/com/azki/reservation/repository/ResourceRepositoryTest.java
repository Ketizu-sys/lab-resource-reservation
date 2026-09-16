package com.azki.reservation.repository;

import com.azki.reservation.config.JpaAuditingConfig;
import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourceRepositoryTest extends ContainerIntegrationTestSupport {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldPersistResourceAndAssociateMultipleSlots() {
        Resource resource = newResource("GPU-A100-01");
        resource = resourceRepository.saveAndFlush(resource);

        AvailableSlot firstSlot = entityManager.persist(slot(resource, 1));
        entityManager.persist(slot(resource, 2));
        entityManager.flush();
        entityManager.clear();

        AvailableSlot first = entityManager.find(AvailableSlot.class, firstSlot.getId());
        assertEquals(resource.getId(), first.getResource().getId());
        assertEquals(2, entityManager.getEntityManager().createQuery(
                "SELECT s FROM AvailableSlot s WHERE s.resource.id = :resourceId", AvailableSlot.class)
            .setParameter("resourceId", resource.getId())
            .getResultList()
            .size());
    }

    @Test
    void shouldRejectDeletingResourceReferencedBySlot() {
        Resource resource = resourceRepository.saveAndFlush(newResource("protected-resource"));
        entityManager.persist(slot(resource, 3));
        entityManager.flush();

        resourceRepository.delete(resource);
        assertThrows(DataAccessException.class, () -> resourceRepository.flush());
    }

    private Resource newResource(String name) {
        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(ResourceType.GPU);
        resource.setLocation("实验楼 A203");
        resource.setStatus(ResourceStatus.ACTIVE);
        resource.setCapacity(1);
        resource.setDescription("Test resource");
        return resource;
    }

    private AvailableSlot slot(Resource resource, int offset) {
        LocalDateTime start = LocalDateTime.now().plusHours(offset);
        AvailableSlot slot = new AvailableSlot();
        slot.setResource(resource);
        slot.setStartTime(start);
        slot.setEndTime(start.plusMinutes(30));
        return slot;
    }
}
