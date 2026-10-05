package io.codiqo.api.code;

import java.io.File;

import lombok.Value;

@Value
public class DeclaredType {
    String name;
    File file;
    TypeKind kind;
    int ncss;
    boolean test;
}
