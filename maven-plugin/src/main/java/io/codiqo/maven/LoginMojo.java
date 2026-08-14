package io.codiqo.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.maven.auth.CredentialStore;

/**
 * Authorises this machine through the browser and stores the resulting api key.
 *
 * <p>Rarely needed: an analysis submitted without {@code codiqo.apiKey} runs this same flow by itself. The goal
 * exists for the cases where doing it up front is what you want — switching organisation, replacing a revoked key —
 * and for exercising the flow against a local server.
 *
 * <p>Runs without a project, since a developer authorises a machine, not a build.
 */
@Mojo(name = "login", requiresProject = false, aggregator = true, threadSafe = true)
public class LoginMojo extends AbstractMojo {
    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    /**
     * Defaults to true: submissions log in on their own when they need to, so someone running this goal by hand is
     * asking to authorise again — most often to switch organisation or replace a key they just revoked. Set false to
     * make it a no-op when a usable key is already stored.
     */
    @Parameter(property = "codiqo.force", defaultValue = "true")
    private boolean force;

    @Override
    public void execute() throws MojoExecutionException {
        CredentialStore store = new CredentialStore(authUrl);
        try {
            if (force || store.find().isEmpty()) {
                store.store(new BrowserLogin(authUrl, getLog()).login());
                getLog().info("credentials stored in " + store.file());
                return;
            }
            getLog().info("already authorized for " + authUrl + " — re-authorize with -Dcodiqo.force=true");
        } catch (Exception err) {
            throw new MojoExecutionException("browser login failed: " + err.getMessage(), err);
        }
    }
}
