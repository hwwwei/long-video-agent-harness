package io.github.hwwwei.video.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateBudgetTest {
    @Test void terminalRunCannotTransitionAndFailedRunCanResume() {
        assertTrue(RunStatus.QUEUED.mayTransitionTo(RunStatus.RUNNING));
        assertTrue(RunStatus.FAILED.mayTransitionTo(RunStatus.QUEUED));
        assertFalse(RunStatus.COMPLETED.mayTransitionTo(RunStatus.CANCELLED));
        assertFalse(RunStatus.CANCELLED.mayTransitionTo(RunStatus.COMPLETED));
    }

    @Test void budgetTracksCallsTokensAndElapsedTime() {
        RunService.Budget budget = new RunService.Budget(2, 10, 20);
        budget = budget.consume(1, 5, 8);
        assertEquals(8, budget.usedMillis());
        assertThrows(RunService.BudgetExceededException.class, () -> new RunService.Budget(2, 10, 20).consume(3, 0, 0));
        assertThrows(RunService.BudgetExceededException.class, () -> new RunService.Budget(2, 10, 20).consume(0, 11, 0));
        assertThrows(RunService.BudgetExceededException.class, () -> new RunService.Budget(2, 10, 20).consume(0, 0, 21));
    }
}
