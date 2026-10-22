package io.tokenpilot.client.enforce;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A decision for one {@link CallRequest}: either the Control Plane's judgment (HTTP 200, {@link #evaluated()}) or
 * the outcome the client mapped from a failure ({@code INDETERMINATE} / {@code CONTROL_PLANE_UNAVAILABLE}, never a
 * {@code DENY_*}).
 *
 * @param decisionId    server-issued id; null unless evaluated
 * @param effectiveMode mode the server recorded; null unless evaluated
 * @param policyVersion null unless evaluated
 * @param reservation   budget reservation; normally only for ALLOW in ENFORCE
 * @param approvalId    id of the pending approval for {@code REQUIRE_APPROVAL}
 * @param httpStatus    status of the decision response; 0 when no response arrived
 * @param errorCode     null for an evaluated decision; otherwise the server's error code, {@code HTTP_<status>},
 *                      {@code UNREADABLE_RESPONSE}, {@code UNEXPECTED_STATUS} or the transport exception's name
 * @param retryAfter    server back-off hint on overload, if any
 */
public record Decision(
    DecisionOutcome outcome,
    String decisionId,
    String requestId,
    EnforcementMode effectiveMode,
    List<DecisionReason> reasons,
    String policyVersion,
    Reservation reservation,
    String approvalId,
    int httpStatus,
    String errorCode,
    Duration retryAfter
) {

    public Decision {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Whether this is the Control Plane's judgment rather than an outcome mapped from a failure. */
    public boolean evaluated() {
        return httpStatus == 200 && errorCode == null;
    }

    static Decision failure(String requestId, DecisionOutcome outcome, int httpStatus, String errorCode,
                            Duration retryAfter) {
        return new Decision(outcome, null, requestId, null, List.of(), null, null, null, httpStatus, errorCode,
            retryAfter);
    }
}
