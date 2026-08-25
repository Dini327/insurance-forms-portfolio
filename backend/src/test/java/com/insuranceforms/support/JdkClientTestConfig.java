package com.insuranceforms.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.util.function.Supplier;

/**
 * TestRestTemplate's default HTTP client (java.net.HttpURLConnection) throws
 * HttpRetryException on any 401 response to a streamed POST/PATCH, before the
 * caller can read the status or body — a long-standing JDK limitation, not a bug
 * in our security config. Swapping in JdkClientHttpRequestFactory (java.net.http,
 * already part of the JDK/Spring, no new dependency) avoids it. Import alongside
 * PostgresTestContainerConfig in any test that asserts a 4xx status on a POST/PATCH.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JdkClientTestConfig {

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        Supplier<ClientHttpRequestFactory> factorySupplier = JdkClientHttpRequestFactory::new;
        return new RestTemplateBuilder().requestFactory(factorySupplier);
    }
}
