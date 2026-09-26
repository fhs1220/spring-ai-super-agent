package com.fhs.aiagent.decision;

/** A provider response could not satisfy the requested decision contract. */
public final class MalformedSystemOneResponseException extends RuntimeException {

    public MalformedSystemOneResponseException(String message) {
        super(message);
    }

    public MalformedSystemOneResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
