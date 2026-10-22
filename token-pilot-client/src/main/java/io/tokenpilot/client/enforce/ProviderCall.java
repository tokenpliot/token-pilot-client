package io.tokenpilot.client.enforce;

/**
 * The guarded LLM provider call. Throw {@link CallNotDispatchedException} when the provider was certainly never
 * reached, so the reservation can be released instead of being held for reconciliation.
 */
@FunctionalInterface
public interface ProviderCall<T> {

    T call() throws Exception;
}
