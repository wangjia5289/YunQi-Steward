package yunqi.zhibei.steward.binding.seata.tm.v2;

import java.util.Objects;

public final class SeataTm2Configuration {

    private final String applicationId;

    private final String transactionServiceGroup;

    SeataTm2Configuration(String applicationId, String transactionServiceGroup) {
        applicationId = requireText(applicationId, "applicationId");
        transactionServiceGroup = requireText(transactionServiceGroup, "transactionServiceGroup");
        this.applicationId = applicationId;
        this.transactionServiceGroup = transactionServiceGroup;
    }

    public String applicationId() {
        return applicationId;
    }

    public String transactionServiceGroup() {
        return transactionServiceGroup;
    }

    public static Builder builder(String applicationId, String transactionServiceGroup) {
        return new Builder(new SeataTm2Configuration(applicationId, transactionServiceGroup));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String applicationId;

        private String transactionServiceGroup;

        private Builder(SeataTm2Configuration source) {
            applicationId = source.applicationId();
            transactionServiceGroup = source.transactionServiceGroup();
        }

        public Builder applicationId(String value) {
            applicationId = value;
            return this;
        }

        public Builder transactionServiceGroup(String value) {
            transactionServiceGroup = value;
            return this;
        }

        public SeataTm2Configuration build() {
            return new SeataTm2Configuration(applicationId, transactionServiceGroup);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof SeataTm2Configuration that))
            return false;
        return java.util.Objects.equals(applicationId, that.applicationId) && java.util.Objects.equals(transactionServiceGroup, that.transactionServiceGroup);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(applicationId, transactionServiceGroup);
    }

    @Override
    public String toString() {
        return "SeataTm2Configuration[applicationId=" + applicationId + ", transactionServiceGroup=" + transactionServiceGroup + "]";
    }
}
