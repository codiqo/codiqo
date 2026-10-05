package io.codiqo.api.code;

import lombok.Value;

@Value
public class TypeReference {
    String from;
    String to;
    TypeReferenceKind kind;
    int count;
}
