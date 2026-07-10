package com.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * TEMPORARY smoke test - NOT part of the Phase 1 deliverable.
 * Used only to verify the Spring context starts and Hibernate can
 * generate valid DDL from every @Entity class against H2. Deleted
 * immediately after confirming success.
 */
@SpringBootTest
class SmokeTest {

    @Test
    void contextLoads() {
        // If the Spring application context (including Hibernate building
        // a schema from every @Entity) fails to start, this test fails.
    }
}
