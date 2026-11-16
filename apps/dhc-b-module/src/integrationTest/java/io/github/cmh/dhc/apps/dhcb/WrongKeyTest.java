package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** T3: A asks for a pre-shared key and B presents another one. B must not start, and must say why. */
class WrongKeyTest {

    static final InstanceA a = InstanceA.withKey();

    @AfterAll
    static void stopA() {
        a.close();
    }

    @Test
    void aWrongKeyStopsTheStartWithAClearError() throws Exception {
        long started = System.nanoTime();

        String messages = RefusedLogin.messages(RefusedLogin.startB(a, Optional.of("not-the-key")));

        assertTrue(messages.contains("Upstream dhc-a at " + a.url() + " refused the login"), messages);
        assertTrue(messages.contains("The key in DHC_UPSTREAM_DHC_A_PSK is not the upstream's key"), messages);
        assertFalse(messages.contains("not-the-key"), "the message shows the key:\n" + messages);
        // A refusal is an answer, not an outage: it is not retried until the connect timeout.
        assertTrue(System.nanoTime() - started < 30_000_000_000L, "a refused login was retried for a long time");
    }
}
