package io.codiqo.lang.config;

import org.apache.commons.io.FilenameUtils;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;

public class ProtoFileSpec implements ConfigFileSpec {
    private static final String PROTO_EXTENSION = "proto";
    private static final IneffectiveLineFilter FILTER = new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX);

    @Override
    public boolean matches(String path) {
        return PROTO_EXTENSION.equalsIgnoreCase(FilenameUtils.getExtension(path));
    }
    @Override
    public IneffectiveLineFilter lineFilter() {
        return FILTER;
    }
}
