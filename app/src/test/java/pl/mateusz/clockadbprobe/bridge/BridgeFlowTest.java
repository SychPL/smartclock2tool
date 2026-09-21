package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static pl.mateusz.clockadbprobe.bridge.BridgeFlow.Kind.ANSWER;
import static pl.mateusz.clockadbprobe.bridge.BridgeFlow.Kind.ASK_CONSENT;
import static pl.mateusz.clockadbprobe.bridge.BridgeFlow.Kind.ASK_TRUST;
import static pl.mateusz.clockadbprobe.bridge.BridgeFlow.Kind.RUN;

public class BridgeFlowTest {
    private static final String HELIOS = "pl.mateusz.helios";
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_ID = "fedcba9876543210fedcba9876543210";

    private final TrustStoreTest.FakeStore trustStore = new TrustStoreTest.FakeStore();
    private final OpRegistryTest.FakeStore registryStore = new OpRegistryTest.FakeStore();
    private final OpRegistryTest.FakeClock clock = new OpRegistryTest.FakeClock("b1", 1000);

    private OpRegistry registry() {
        return new OpRegistry(registryStore, clock);
    }

    private BridgeFlow.Identity helios() {
        return new BridgeFlow.Identity(HELIOS, "AA", true);
    }

    private TrustStore untrusting() {
        return new TrustStore(trustStore);
    }

    private TrustStore trusting() {
        TrustStore t = new TrustStore(trustStore);
        t.trust(HELIOS, "AA");
        return t;
    }

    private TrustStore consented(String op, String scope) {
        TrustStore t = trusting();
        t.consent(HELIOS, "AA", op, scope);
        return t;
    }

    private static BridgeFlow.Facts facts() {
        return new BridgeFlow.Facts(true, ExecutorLock.IDLE, null);
    }

    private static BridgeRequest request(String op, String opId, String args) {
        BridgeRequest.Input in = new BridgeRequest.Input();
        in.action = BridgeRequest.ACTION;
        in.hasApi = true;
        in.api = BridgeRequest.API;
        in.op = op;
        in.args = args;
        in.opId = opId;
        in.hasCaller = true;
        if (Ops.needsFile(op)) {
            in.hasUri = true;
            in.flags = BridgeRequest.FLAG_GRANT_READ_URI_PERMISSION;
        }
        return BridgeRequest.parse(in);
    }

    private static BridgeRequest request(String op) {
        return request(op, ID, "");
    }

    @Test
    public void anUnknownCallerIsAskedForTrustBeforeAnythingElse() {
        assertEquals(ASK_TRUST, BridgeFlow.decide(request(Ops.STATE), helios(), untrusting(), registry(), facts()).kind);
    }

    @Test
    public void aReadNeverAsksForConsent() {
        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.STATE), helios(), trusting(), registry(), facts());
        assertEquals(ANSWER, d.kind);
        assertEquals("ok", d.status);
    }

    @Test
    public void aNormalOperationAsksOnceAndThenRuns() {
        assertEquals(ASK_CONSENT, BridgeFlow.decide(request(Ops.ADB_OFF), helios(), trusting(), registry(), facts()).kind);
        assertEquals(RUN, BridgeFlow.decide(request(Ops.ADB_OFF), helios(), consented(Ops.ADB_OFF, ""), registry(), facts()).kind);
    }

    @Test
    public void aHighRiskOperationAsksEveryTime() {
        assertEquals(ASK_CONSENT, BridgeFlow.decide(request(Ops.ROOT_ADB_ON), helios(),
                consented(Ops.ROOT_ADB_ON, ""), registry(), facts()).kind);
    }

    @Test
    public void aDuplicateAnswersInsteadOfRunningTwice() {
        OpRegistry reg = registry();
        OpRegistry.Key key = new OpRegistry.Key(HELIOS, "AA", ID);
        reg.accept(key, Ops.ADB_OFF, Ops.requestDigest(Ops.ADB_OFF, "", null));
        reg.stage(key, OpRegistry.RUNNING);

        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.ADB_OFF), helios(), consented(Ops.ADB_OFF, ""), reg,
                new BridgeFlow.Facts(true, ExecutorLock.RUNNING, null));
        assertEquals(ANSWER, d.kind);
        assertEquals("in_progress", d.status);
    }

    @Test
    public void aFinishedDuplicateReplaysTheStoredResultWithoutRunning() {
        OpRegistry reg = registry();
        OpRegistry.Key key = new OpRegistry.Key(HELIOS, "AA", ID);
        reg.accept(key, Ops.ADB_OFF, Ops.requestDigest(Ops.ADB_OFF, "", null));
        reg.finish(key, "failed");

        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.ADB_OFF), helios(), consented(Ops.ADB_OFF, ""), reg, facts());
        assertEquals(ANSWER, d.kind);
        assertEquals("the same request gets the same answer", "failed", d.status);
    }

    @Test
    public void theSameIdWithAnotherOperationIsUnsupported() {
        OpRegistry reg = registry();
        reg.accept(new OpRegistry.Key(HELIOS, "AA", ID), Ops.ADB_OFF, Ops.requestDigest(Ops.ADB_OFF, "", null));
        assertEquals("unsupported", BridgeFlow.decide(request(Ops.ADB_ON), helios(), consented(Ops.ADB_ON, ""), reg, facts()).status);
    }

    @Test
    public void changedArgumentsAreNotADuplicate() {
        OpRegistry reg = registry();
        String args = "{\"expect\":\"sha-1\"}";
        reg.accept(new OpRegistry.Key(HELIOS, "AA", ID), Ops.INSTALL_APK, Ops.requestDigest(Ops.INSTALL_APK, args, null));
        assertEquals("unsupported", BridgeFlow.decide(request(Ops.INSTALL_APK, ID, "{\"expect\":\"sha-9\"}"),
                helios(), trusting(), reg, facts()).status);
        assertEquals("dropping the argument changes it too", "unsupported",
                BridgeFlow.decide(request(Ops.INSTALL_APK, ID, ""), helios(), trusting(), reg, facts()).status);
    }

    @Test
    public void anInstallRepeatIsRecognisedOnceTheCopyIsBound() {
        OpRegistry reg = registry();
        OpRegistry.Key key = new OpRegistry.Key(HELIOS, "AA", ID);
        reg.accept(key, Ops.INSTALL_APK, Ops.requestDigest(Ops.INSTALL_APK, "", null));
        reg.stage(key, OpRegistry.COPYING);
        assertEquals("in_progress", BridgeFlow.decide(request(Ops.INSTALL_APK), helios(), trusting(), reg,
                new BridgeFlow.Facts(true, ExecutorLock.RUNNING, null)).status);

        reg.bindDigest(key, "sha-of-copy");
        reg.stage(key, OpRegistry.INSTALLING);
        assertEquals("the copy is what identifies the file from now on", "in_progress",
                BridgeFlow.decide(request(Ops.INSTALL_APK), helios(), trusting(), reg,
                        new BridgeFlow.Facts(true, ExecutorLock.RUNNING, null)).status);
        assertEquals("and no second copy is ever made", 1, reg.about(key).copies);
    }

    @Test
    public void aNewRequestWhileAnotherRunsIsBusy() {
        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.ADB_OFF, OTHER_ID, ""), helios(),
                consented(Ops.ADB_OFF, ""), registry(), new BridgeFlow.Facts(true, ExecutorLock.RUNNING, null));
        assertEquals("busy", d.status);
    }

    @Test
    public void anUnknownChainBlocksNewWorkButNotReads() {
        BridgeFlow.Facts unknown = new BridgeFlow.Facts(true, ExecutorLock.UNKNOWN, null);
        assertEquals("busy", BridgeFlow.decide(request(Ops.ADB_OFF), helios(), consented(Ops.ADB_OFF, ""), registry(), unknown).status);
        assertEquals("ok", BridgeFlow.decide(request(Ops.STATE), helios(), trusting(), registry(), unknown).status);
    }

    @Test
    public void anUnsupportedFirmwareRefusesTheChainWithoutRunningIt() {
        BridgeFlow.Facts foreign = new BridgeFlow.Facts(false, ExecutorLock.IDLE, null);
        assertEquals("wrong_firmware", BridgeFlow.decide(request(Ops.ROOT_ADB_ON), helios(),
                consented(Ops.ROOT_ADB_ON, ""), registry(), foreign).status);
        assertEquals("firmware is irrelevant to operations that do not touch the chain",
                "ok", BridgeFlow.decide(request(Ops.STATE), helios(), trusting(), registry(), foreign).status);
    }

    @Test
    public void aStoredResultWinsOverConditionsThatChangedLater() {
        OpRegistry reg = registry();
        OpRegistry.Key key = new OpRegistry.Key(HELIOS, "AA", ID);
        reg.accept(key, Ops.ROOT_ADB_ON, Ops.requestDigest(Ops.ROOT_ADB_ON, "", null));
        reg.finish(key, "ok");

        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.ROOT_ADB_ON), helios(), consented(Ops.ROOT_ADB_ON, ""), reg,
                new BridgeFlow.Facts(false, ExecutorLock.IDLE, null));
        assertEquals("asking about a finished operation is not a new run", "ok", d.status);
    }

    @Test
    public void aFailedPreconditionRefusesBeforeAnyConsentIsAsked() {
        BridgeFlow.Facts missingDeclaration = new BridgeFlow.Facts(true, ExecutorLock.IDLE, "unsupported");
        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.WRITE_SETTINGS), helios(), trusting(), registry(), missingDeclaration);
        assertEquals(ANSWER, d.kind);
        assertEquals("unsupported", d.status);
    }

    @Test
    public void identityIsRecheckedAgainstTheAuthorisedRequestBeforeRunning() {
        TrustStore trust = consented(Ops.ADB_OFF, "");
        BridgeFlow.Decision d = BridgeFlow.decide(request(Ops.ADB_OFF), helios(), trust, registry(), facts());
        assertEquals(RUN, d.kind);

        assertEquals("denied", d.recheck(new BridgeFlow.Identity(HELIOS, "BB", true), trust.revision()).status);
        assertEquals("denied", d.recheck(new BridgeFlow.Identity(HELIOS, "AA", false), trust.revision()).status);
        assertEquals("denied", d.recheck(helios(), trust.revision() + 1).status);
        assertEquals("an unchanged world still runs", RUN, d.recheck(helios(), trust.revision()).kind);
    }

    @Test
    public void aMalformedRequestIsAnsweredWithItsOwnError() {
        BridgeRequest.Input anonymous = new BridgeRequest.Input();
        anonymous.action = BridgeRequest.ACTION;
        BridgeFlow.Decision d = BridgeFlow.decide(BridgeRequest.parse(anonymous), helios(), trusting(), registry(), facts());
        assertEquals(ANSWER, d.kind);
        assertEquals("denied", d.status);
    }
}
