package io.codiqo.gradle;

import java.io.IOException;
import java.util.Optional;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import io.codiqo.submit.auth.CodiqoCredentials;

/**
 * Authorises this machine through the browser and stores the login in {@code ~/.codiqo}, where the Maven plugin keeps
 * its own: one login serves both. The analysis and index tasks then use it, so a laptop needs no {@code codiqo.apiKey};
 * they never open the browser themselves, since a Gradle worker has nobody at its terminal.
 */
public class CodiqoLoginTask extends DefaultTask {
    @TaskAction
    public void login() throws IOException {
        CodiqoExtension ext = getProject().getExtensions().getByType(CodiqoExtension.class);
        CodiqoCredentials.login(prop("codiqo.authUrl", ext.getAuthUrl()), prop("codiqo.resourceUrl", ext.getResourceUrl()), new GradleLifecycleLog(getLogger()));
    }
    private String prop(String name, String fallback) {
        return Optional.ofNullable(getProject().findProperty(name)).map(Object::toString).orElse(fallback);
    }
}
