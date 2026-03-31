package io.codiqo.api.jdtls;

import java.io.IOException;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class ServiceStatusAdapter extends TypeAdapter<ServiceStatus> {
    @Override
    public void write(JsonWriter out, ServiceStatus value) throws IOException {
        out.value(value.getJsonValue());
    }
    @Override
    public ServiceStatus read(JsonReader in) throws IOException {
        return ServiceStatus.fromJsonValue(in.nextString());
    }
}
