package io.codiqo.api.jdtls;

import java.util.EnumSet;
import java.util.Objects;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import lombok.Getter;

public enum ServiceStatus {
    STARTING("Starting"),
    STARTED("Started"),
    MESSAGE("Message"),
    ERROR("Error"),
    SERVICE_READY("ServiceReady"),
    PROJECT_STATUS("ProjectStatus");

    private static final ImmutableMap<String, ServiceStatus> BY_JSON_VALUE = Maps.uniqueIndex(EnumSet.allOf(ServiceStatus.class), ServiceStatus::getJsonValue);

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
