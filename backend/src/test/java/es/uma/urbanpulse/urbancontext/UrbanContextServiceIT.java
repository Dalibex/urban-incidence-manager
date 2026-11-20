package es.uma.urbanpulse.urbancontext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.urbancontext.service.UrbanContextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

/** The urban context is created only inside the transaction of the report (ADR-0018). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UrbanContextServiceIT {

    @Autowired private UrbanContextService contexts;

    // The transaction is required before the method runs, so no incident is needed to see it fail.
    @Test
    void aPendingContextCannotBeCreatedOutsideATransaction() {
        assertThatThrownBy(() -> contexts.createPending(null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
