package rw.rra.roomiq.audit.domain;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventImmutabilityTests {
    @Test
    void entityIsMarkedImmutableAndRepositoryExposesNoMutationMethods() {
        assertThat(AuditEvent.class.isAnnotationPresent(Immutable.class)).isTrue();

        assertThat(AuditEventRepository.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("save", "saveAll", "delete", "deleteById", "deleteAll");
    }
}
