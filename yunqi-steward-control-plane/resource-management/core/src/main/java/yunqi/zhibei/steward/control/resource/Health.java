package yunqi.zhibei.steward.control.resource;

import java.util.Objects;

/**
 * A detail-free health result together with the strength of the probe that produced it.
 *
 * <p>The result never retains an exception, vendor message, endpoint, or configuration value.
 */
public final class Health {

    /** Binary outcome of one binding-specific probe. */
    public enum Status {
        /** The probe succeeded. */
        HEALTHY,
        /** The probe did not succeed. */
        UNHEALTHY
    }

    private static final Health[][] VALUES = createValues();

    private final Status status;
    private final ProbeScope scope;

    private Health(Status status, ProbeScope scope) {
        this.status = status;
        this.scope = scope;
    }

    /** Returns a healthy result for the supplied probe scope. */
    public static Health healthy(ProbeScope scope) {
        return value(Status.HEALTHY, scope);
    }

    /** Returns an unhealthy result for the supplied probe scope. */
    public static Health unhealthy(ProbeScope scope) {
        return value(Status.UNHEALTHY, scope);
    }

    /** Returns the binary probe outcome. */
    public Status status() {
        return status;
    }

    /** Returns what the probe actually establishes. */
    public ProbeScope scope() {
        return scope;
    }

    /** Returns whether the probe succeeded. */
    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Health health
                && status == health.status
                && scope == health.scope;
    }

    @Override
    public int hashCode() {
        return 31 * status.hashCode() + scope.hashCode();
    }

    @Override
    public String toString() {
        return "Health[status=" + status + ", scope=" + scope + ']';
    }

    private static Health value(Status status, ProbeScope scope) {
        Status checkedStatus = Objects.requireNonNull(status, "status");
        ProbeScope checkedScope = Objects.requireNonNull(scope, "scope");
        return VALUES[checkedStatus.ordinal()][checkedScope.ordinal()];
    }

    private static Health[][] createValues() {
        Status[] statuses = Status.values();
        ProbeScope[] scopes = ProbeScope.values();
        Health[][] values = new Health[statuses.length][scopes.length];
        for (Status status : statuses) {
            for (ProbeScope scope : scopes) {
                values[status.ordinal()][scope.ordinal()] = new Health(status, scope);
            }
        }
        return values;
    }
}
