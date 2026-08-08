package yunqi.zhibei.steward.lifecycle;

/** Describes what a successful binding-specific health probe actually establishes. */
public enum ProbeScope {
    /** A side-effect-free request reached the remote service. */
    REMOTE,
    /** The native SDK reports an established connection as usable. */
    CONNECTION_STATE,
    /** Only local client lifecycle state was checked. */
    LOCAL,
    /** Successful construction or startup is the only safe signal. */
    STARTUP_ONLY
}
