package io.tokenpilot.client.enforce;

import java.util.Objects;

/** The provider response together with the decision that let it through. */
public record EnforcedResult<T>(T response, Decision decision, EnforcementAction action) {

    public EnforcedResult {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }

    /** The call proceeded although the decision was not a plain allow; surface it to the caller as a warning. */
    public boolean advisory() {
        return action == EnforcementAction.PROCEED_WITH_ADVISORY;
    }
}
