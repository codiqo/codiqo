package io.codiqo.lang.config;

import org.apache.commons.io.FilenameUtils;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;

public class PomFileSpec implements ConfigFileSpec {
    private static final String POM_FILE_NAME = "pom.xml";
    private static final IneffectiveLineFilter FILTER = new IneffectiveLineFilter(CommentSyntax.XML, null);

    @Override
    public boolean matches(String path) {
        return POM_FILE_NAME.equalsIgnoreCase(FilenameUtils.getName(path));
    }
    @Override
    public IneffectiveLineFilter lineFilter() {
        return FILTER;
    }
}
