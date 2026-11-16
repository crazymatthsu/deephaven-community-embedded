package io.github.cmh.dhc.apps.dhcb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** T3: A asks for a pre-shared key and B has none. B must not start, and must say which setting is missing. */
class MissingKeyTest {

    static final InstanceA a = InstanceA.withKey();

    @AfterAll
    static void stopA() {
        a.close();
    }

    @Test
    void noKeyStopsTheStartAndNamesTheSetting() throws Exception {
        String messages = RefusedLogin.messages(RefusedLogin.startB(a, Optional.empty()));

        assertTrue(messages.contains("Upstream dhc-a at " + a.url() + " refused the login"), messages);
        assertTrue(messages.contains("It asks for a pre-shared key; give it in DHC_UPSTREAM_DHC_A_PSK"), messages);
    }
}
