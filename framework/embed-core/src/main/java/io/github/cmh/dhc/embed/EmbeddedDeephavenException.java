package io.github.cmh.dhc.embed;

/** Something about embedding Deephaven went wrong. The message says what to do about it. */
public class EmbeddedDeephavenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmbeddedDeephavenException(String message) {
        super(message);
    }

    public EmbeddedDeephavenException(String message, Throwable cause) {
        super(message, cause);
    }
}
