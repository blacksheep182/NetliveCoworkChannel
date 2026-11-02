package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.TargetOwnership;
import org.junit.jupiter.api.Test;

class OwnershipRefreshRegressionTest {
    @Test void firstUnverifiedTargetIsRetriedAfterBackendRecovers() {
        TestModule test = new TestModule(false);
        try {
            test.module.management().syncTargets(); // Backend has no facts yet.
            assertNull(test.module.directory().targetOwner("target-1"));
            assertTrue(test.module.directory().ownershipRefreshDue(TestModule.TENANT,TestModule.WORKSPACE,
                test.clock.instant().plusSeconds(10)));
            assertFalse(test.module.grants().canAct(TestModule.TENANT,TestModule.WORKSPACE,"owner-1","target-1","input.submit"));
            test.backend.ownerships.add(new TargetOwnership("target-1","owner-1","evidence-1",1,1,
                test.clock.instant().plusSeconds(30)));
            test.module.tick();
            assertEquals("owner-1",test.module.directory().targetOwner("target-1"));
            assertTrue(test.module.grants().canAct(TestModule.TENANT,TestModule.WORKSPACE,"owner-1","target-1","input.submit"));
            assertFalse(test.module.directory().ownershipRefreshDue(TestModule.TENANT,TestModule.WORKSPACE,
                test.clock.instant().plusSeconds(10)));
        } finally { test.module.close(); test.db.close(); }
    }
}
