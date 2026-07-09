package io.codiqo.api.jdtls;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;

public enum ServiceStatus {
    STARTING("Starting"),
    STARTED("Started"),
    MESSAGE("Message"),
    ERROR("Error"),
    SERVICE_READY("ServiceReady"),
    PROJECT_STATUS("ProjectStatus");

    private static final Map<String, ServiceStatus> BY_JSON_VALUE = EnumSet.allOf(ServiceStatus.class).stream()
            .collect(Collectors.toUnmodifiableMap(ServiceStatus::getJsonValue, Function.identity()));

    @Getter
    private final String jsonValue;
    private ServiceStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }
    public static ServiceStatus fromJsonValue(String value) {
        ServiceStatus toReturn = BY_JSON_VALUE.get(value);
        if (Objects.isNull(toReturn)) {
            throw new IllegalArgumentException("Unknown ServiceStatus JSON value: " + value);
        }
        return toReturn;
    }
}
