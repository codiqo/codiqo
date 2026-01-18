package io.codiq.lang.spec;

import lombok.Builder;
import lombok.Value;

public interface JavaBinarySignatureFormatter {
    BinarySignatureData toBinarySignature();

    @Value
    @Builder
    public static class BinarySignatureData {
        String ownerClass;
        String methodName;
        String descriptor;
    }
}
