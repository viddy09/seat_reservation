package com.paytmmoney.seatreservation.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A real server on a random port, backed by a real Postgres container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "JWT_SECRET=" + IntegrationTest.JWT_SECRET,
            "ADMIN_API_KEY=" + IntegrationTest.ADMIN_API_KEY,
            "ALLOW_DEV_TOKENS=true"
        })
@Import(PostgresContainerConfig.class)
public @interface IntegrationTest {

    String JWT_SECRET = "integration-test-secret-0123456789abcdef";
    String ADMIN_API_KEY = "integration-admin-key";
}
