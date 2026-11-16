package io.github.cmh.dhc.embed.spring;

import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a failed start of the embedded Deephaven into Spring Boot's "APPLICATION FAILED TO START"
 * report. The framework's messages already say what to change, so the report is the message
 * rather than a stack trace with the message somewhere inside.
 */
public final class EmbeddedDeephavenFailureAnalyzer extends AbstractFailureAnalyzer<EmbeddedDeephavenException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, EmbeddedDeephavenException cause) {
        return new FailureAnalysis("The embedded Deephaven did not start: " + cause.getMessage(),
                "Correct what the description names and start again. Settings are DHC_<NAME> in the environment,"
                        + " or dhc.<name> as a property.",
                cause);
    }
}
