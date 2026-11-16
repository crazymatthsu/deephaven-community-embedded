package io.github.cmh.dhc.embed.spring.host;

import io.github.cmh.dhc.embed.spring.HostBinding;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/** A host as small as a host gets: the starter on the classpath and one bean for the scripts. */
@SpringBootApplication
public class TestHost {

    /** {@code host.greeting} in the test module. */
    @Component
    public static class Greeting implements HostBinding {

        @Override
        public String bindingName() {
            return "greeting";
        }

        public String text() {
            return "hello from a Spring bean";
        }
    }
}
