package io.codiqo.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.submit.auth.CodiqoCredentials;

/**
 * Authorises this machine through the browser and stores the login, which the Maven and Gradle plugins share.
 *
 * <p>Rarely needed: a submission without {@code codiqo.apiKey} runs this same flow by itself. The goal exists for the
 * cases where doing it up front is what you want (switching organization, replacing a login you revoked) and for
 * exercising the flow against a local server.
 *
 * <p>Runs without a project, since a developer authorises a machine, not a build.
 */
@Mojo(name = "login", requiresProject = false, aggregator = true, threadSafe = true)
public class LoginMojo extends AbstractMojo {
    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    /**
     * Defaults to true: submissions log in on their own when they need to, so someone running this goal by hand is
     * asking to authorise again, most often to switch organization. Set false to make it a no-op when a usable login
     * is already stored.
     */
    @Parameter(property = "codiqo.force", defaultValue = "true")
    private boolean force;

    @Override
    public void execute() throws MojoExecutionException {
        io.codiqo.api.logging.Log log = new MavenLogFactory(getLog()).getLogger(LoginMojo.class);
        try {
            if (force || CodiqoCredentials.resolve(null, authUrl, resourceUrl, false, log).isEmpty()) {
                CodiqoCredentials.login(authUrl, resourceUrl, log);
                return;
            }
            getLog().info("already authorized for " + authUrl + "; re-authorize with -Dcodiqo.force=true");
        } catch (Exception err) {
            throw new MojoExecutionException("browser login failed: " + err.getMessage(), err);
        }
    }
}
