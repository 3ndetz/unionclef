package kaptainwutax.tungsten.path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NodeRegressionTest {

    @Test
    void generatesFallbackNodesWhenSprintJumpIsCloseButStillAirborne() {
        assertTrue(Node.shouldGenerateFallbackNodes(true, false));
    }

    @Test
    void skipsFallbackNodesWhenSprintJumpIsCloseAndGrounded() {
        assertFalse(Node.shouldGenerateFallbackNodes(true, true));
    }

    @Test
    void allowsFinalAirborneTickOnlyWhileDropStaysSafe() {
        assertTrue(Node.shouldAppendFinalAirborneTick(false, -2.9));
        assertFalse(Node.shouldAppendFinalAirborneTick(false, -3.0));
    }

    @Test
    void ignoresFallThresholdWhenFallDamageIsDisabled() {
        assertTrue(Node.shouldAppendFinalAirborneTick(true, -20.0));
    }
}
