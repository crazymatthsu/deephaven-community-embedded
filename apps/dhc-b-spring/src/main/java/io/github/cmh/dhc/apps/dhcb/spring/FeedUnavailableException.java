package io.github.cmh.dhc.apps.dhcb.spring;

/** A question that needs live tables was asked while instance A is away. Answered with 503. */
public final class FeedUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FeedUnavailableException(String message) {
        super(message);
    }
}
