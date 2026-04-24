package io.codiqo.util;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.transport.URIish;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RepositoryUrls {
    public static URI toUri(String raw) throws URISyntaxException {
        String normalized = Strings.CS.removeStart(StringUtils.trim(raw), "scm:git:");
        URIish uriIsh = new URIish(normalized);
        if (StringUtils.isNotEmpty(uriIsh.getScheme())) {
            return URI.create(uriIsh.toString());
        }

        String path = Strings.CS.removeStart(uriIsh.getPath(), "/");
        return URI.create(String.format("https://%s/%s", uriIsh.getHost(), path));
    }
}