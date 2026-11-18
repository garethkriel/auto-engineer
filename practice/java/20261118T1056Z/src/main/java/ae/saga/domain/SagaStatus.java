package ae.saga.domain;

/** Final (or stuck) state of a saga run. */
public enum SagaStatus { COMPLETED, COMPENSATED, STUCK }
