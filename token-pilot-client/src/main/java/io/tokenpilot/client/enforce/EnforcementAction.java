package io.tokenpilot.client.enforce;

/** What the client does with the provider call once it has a decision. */
public enum EnforcementAction {
    PROCEED,
    PROCEED_WITH_ADVISORY,
    BLOCK,
    HOLD_FOR_APPROVAL;

    public boolean stopsProviderCall() {
        return this == BLOCK || this == HOLD_FOR_APPROVAL;
    }
}
