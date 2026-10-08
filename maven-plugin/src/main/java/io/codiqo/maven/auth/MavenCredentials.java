package io.codiqo.maven.auth;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.auth.CodiqoCredentials;
import lombok.experimental.UtilityClass;

/** The shared credential lookup, with the advice a Maven user needs when it fails. */
@UtilityClass
public class MavenCredentials {
    public CodiqoCredential resolve(String configuredApiKey, String authUrl, String resourceUrl, Log log) throws MojoExecutionException {
        try {
            return CodiqoCredentials.resolve(configuredApiKey, authUrl, resourceUrl, true, new MavenLogFactory(log).getLogger(CodiqoCredentials.class))
                    .orElseThrow();
        } catch (Exception err) {
            /**
             * The advice belongs here rather than at the failure itself: whatever went wrong inside the login (no
             * browser on this machine, a denial, a timeout), the way past it is a configured key or a login run
             * somewhere with a desktop.
             */
            throw new MojoExecutionException("no usable codiqo credential: " + err.getMessage()
                    + "; set codiqo.apiKey, or run 'mvn io.codiqo:codiqo-maven-plugin:login' on a workstation and copy ~/.codiqo across", err);
        }
    }
}
