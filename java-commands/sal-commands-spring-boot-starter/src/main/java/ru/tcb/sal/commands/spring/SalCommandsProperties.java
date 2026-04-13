package ru.tcb.sal.commands.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "sal.command-bus")
public record SalCommandsProperties(
    boolean enabled,
    String adapterName,
    Duration defaultTimeout,
    String concurrency
) {
    public SalCommandsProperties {
        if (adapterName == null || adapterName.isBlank()) adapterName = "JavaAdapter";
        if (defaultTimeout == null) defaultTimeout = Duration.ofSeconds(120);
        if (concurrency == null || concurrency.isBlank()) concurrency = "10";
    }
}
