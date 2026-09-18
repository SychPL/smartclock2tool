package pl.mateusz.clockadbprobe;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class AgentServerTriggerTest {

    @After
    public void clearTriggers() {
        AgentServer.setProbeTrigger(null);
        AgentServer.setDefaultProbeTrigger(null);
    }

    @Test
    public void explicitTriggerOverridesDefaultAndDefaultRemainsFallback() {
        AgentServer.ProbeTrigger headless = new AgentServer.ProbeTrigger() {
            public boolean trigger(String name) { return true; }
        };
        AgentServer.ProbeTrigger activity = new AgentServer.ProbeTrigger() {
            public boolean trigger(String name) { return false; }
        };

        AgentServer.setProbeTrigger(null);
        AgentServer.setDefaultProbeTrigger(headless);
        assertSame(headless, AgentServer.selectedProbeTrigger());

        AgentServer.setProbeTrigger(activity);
        assertSame(activity, AgentServer.selectedProbeTrigger());

        AgentServer.setProbeTrigger(null);
        assertSame(headless, AgentServer.selectedProbeTrigger());

        AgentServer.setDefaultProbeTrigger(null);
        assertNull(AgentServer.selectedProbeTrigger());
    }

    @Test
    public void staleActivityCannotClearANewerTrigger() {
        AgentServer.ProbeTrigger oldActivity = new AgentServer.ProbeTrigger() {
            public boolean trigger(String name) { return true; }
        };
        AgentServer.ProbeTrigger newActivity = new AgentServer.ProbeTrigger() {
            public boolean trigger(String name) { return true; }
        };

        AgentServer.setProbeTrigger(oldActivity);
        AgentServer.setProbeTrigger(newActivity);
        AgentServer.clearProbeTrigger(oldActivity);
        assertSame(newActivity, AgentServer.selectedProbeTrigger());

        AgentServer.clearProbeTrigger(newActivity);
        assertNull(AgentServer.selectedProbeTrigger());
    }
}
