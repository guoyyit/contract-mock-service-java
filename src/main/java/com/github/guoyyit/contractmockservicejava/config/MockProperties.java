package com.github.guoyyit.contractmockservicejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration knobs for the mock service. */
@Component
@ConfigurationProperties(prefix = "contractmock")
public class MockProperties {

    private String storePath = "data/mock-config.json";
    private long requestTimeoutMs = 30000L;

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
