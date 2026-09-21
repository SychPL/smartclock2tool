package pl.mateusz.clockadbprobe.bridge;

/**
 * What to do with a request that parsed (SPEC 0.12 pkt 4.1 - 4.5).
 *
 * <p>The order below is the contract and it is not arbitrary. An existing entry is resolved <em>before</em> the
 * conditions for new work, so asking again about a finished operation returns what happened, not a refusal caused by
 * something that changed afterwards. Firmware is checked only for the operation that actually runs the chain.
 */
public final class BridgeFlow {
    public enum Kind { ASK_TRUST, ASK_CONSENT, RUN, ANSWER }

    /** Who is calling, as read from the package manager at this moment. */
    public static final class Identity {
        public final String pkg;
        public final String fingerprint;
        public final boolean installed;

        public Identity(String pkg, String fingerprint, boolean installed) {
            this.pkg = pkg;
            this.fingerprint = fingerprint;
            this.installed = installed;
        }
    }

    /** Everything about the device that a decision may depend on. */
    public static final class Facts {
        public final boolean firmwareSupported;
        public final String chain;              // idle, running or unknown
        public final String preconditionError;  // null when the operation may run here

        public Facts(boolean firmwareSupported, String chain, String preconditionError) {
            this.firmwareSupported = firmwareSupported;
            this.chain = chain;
            this.preconditionError = preconditionError;
        }
    }

    public static final class Decision {
        public final Kind kind;
        public final String status;     // the code to answer with, when the kind is ANSWER
        public final String detail;
        private final Identity identity;
        private final long revision;

        private Decision(Kind kind, String status, String detail, Identity identity, long revision) {
            this.kind = kind;
            this.status = status;
            this.detail = detail;
            this.identity = identity;
            this.revision = revision;
        }

        static Decision of(Kind kind, String status, String detail, Identity identity, long revision) {
            return new Decision(kind, status, detail, identity, revision);
        }

        /**
         * Checked again immediately before the work starts: the package may have been uninstalled or replaced while
         * a consent screen was open, and a revocation may have happened in another window.
         */
        public Decision recheck(Identity now, long revisionNow) {
            if (kind != Kind.RUN) return this;
            if (now == null || !now.installed) return answer("denied", "the caller is gone");
            if (!now.pkg.equals(identity.pkg) || !now.fingerprint.equals(identity.fingerprint)) {
                return answer("denied", "the caller changed while we were asking");
            }
            if (revisionNow != revision) return answer("denied", "permission was withdrawn while we were asking");
            return this;
        }

        private Decision answer(String status, String detail) {
            return new Decision(Kind.ANSWER, status, detail, identity, revision);
        }
    }

    private BridgeFlow() {}

    /**
     * The decision to run, built after the user has just agreed. Deciding again from scratch would find the entry
     * this very request created and answer it as a duplicate, which is the opposite of what a fresh consent means.
     */
    public static Decision runNow(Identity identity, long revision) {
        return Decision.of(Kind.RUN, "ok", "", identity, revision);
    }

    public static Decision decide(BridgeRequest request, Identity identity, TrustStore trust,
                                  OpRegistry registry, Facts facts) {
        long revision = trust.revision();
        if (request.error != null) return Decision.of(Kind.ANSWER, request.error, "", identity, revision);
        if (identity == null || !identity.installed) return Decision.of(Kind.ANSWER, "denied", "", identity, revision);

        // a signature that changed voids everything this package was allowed, before any decision is made
        trust.seenFingerprint(identity.pkg, identity.fingerprint);
        revision = trust.revision();
        if (!trust.trusted(identity.pkg, identity.fingerprint)) {
            return Decision.of(Kind.ASK_TRUST, "denied", "", identity, revision);
        }

        OpRegistry.Key key = new OpRegistry.Key(identity.pkg, identity.fingerprint, request.opId);
        OpRegistry.Entry existing = registry.about(key);
        String digest = Ops.requestDigest(request.op, request.args, existing.fileDigest.isEmpty() ? null : existing.fileDigest);
        String digestWithoutFile = Ops.requestDigest(request.op, request.args, null);

        if (!OpRegistry.ABSENT.equals(existing.stage)) {
            boolean same = registry.duplicate(existing, request.op, digest)
                    || registry.duplicate(existing, request.op, digestWithoutFile);
            if (!same) {
                return Decision.of(Kind.ANSWER, "unsupported", "this id belongs to another request", identity, revision);
            }
            String status = existing.terminal() ? existing.status : "in_progress";
            return Decision.of(Kind.ANSWER, status, "", identity, revision);
        }

        // from here on this is new work, so the conditions for new work apply
        if (Ops.needsFirmware(request.op) && !facts.firmwareSupported) {
            return Decision.of(Kind.ANSWER, "wrong_firmware", "", identity, revision);
        }
        if (facts.preconditionError != null) {
            return Decision.of(Kind.ANSWER, facts.preconditionError, "", identity, revision);
        }
        if (Ops.READ.equals(Ops.risk(request.op))) {
            return Decision.of(Kind.ANSWER, "ok", "", identity, revision);
        }
        if (!ExecutorLock.IDLE.equals(facts.chain)) {
            return Decision.of(Kind.ANSWER, "busy", facts.chain, identity, revision);
        }

        String scope = Ops.consentScope(request.op, request.args);
        boolean asksEveryTime = Ops.HIGH.equals(Ops.risk(request.op));
        if (asksEveryTime || !trust.consented(identity.pkg, identity.fingerprint, request.op, scope)) {
            return Decision.of(Kind.ASK_CONSENT, "denied", "", identity, revision);
        }
        return Decision.of(Kind.RUN, "ok", "", identity, revision);
    }
}
