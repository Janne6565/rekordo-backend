package com.rekordo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
// For the collection gauges, which are database counts on a timer rather than something
// a request path could ever update.
@EnableScheduling
// The service issues its own tokens and authenticates them in JwtFilter; there is no
// UserDetailsService, no form login and no basic auth. Left to itself, Spring Boot reads
// that absence as "not configured yet", invents an in-memory user and prints its random
// password at WARN on every boot -- telling anyone reading a production log that the
// security configuration must be updated before production. Nothing can reach that user,
// because the filter chain offers no way to present a password at all, so the warning is
// only ever wrong. Excluded so a real warning in that log still means something.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
