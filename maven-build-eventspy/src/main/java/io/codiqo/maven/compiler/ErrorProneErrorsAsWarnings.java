package io.codiqo.maven.compiler;

import java.util.Objects;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import lombok.extern.slf4j.Slf4j;

/**
 * Core extension (loaded through maven.ext.class.path into the codiqo-forked build only) that turns every Error Prone
 * error into a warning, by adding {@code -XepAllErrorsAsWarnings} to each {@code -Xplugin:ErrorProne} argument of
 * maven-compiler-plugin. A commit whose code compiles but trips an ERROR-level check (a {@code FormatString} with more
 * arguments than placeholders in a test, for one) otherwise fails javac, and the whole commit is excluded as a build
 * failure although nothing is wrong with the build. The warnings the build then prints are read back by the plugin and
 * triaged like PMD and SpotBugs findings, so a real defect still becomes a bug of the commit.
 *
 * <p>The change is made to the effective model after the projects are read, never to a file: the analysis clone's
 * POMs stay byte for byte the commit's (the local review reads that clone while the build runs), and an argument a
 * project inherits from a released parent, a profile or a property is reached as well as one in the reactor's own
 * POMs. The flag has to sit inside the same argument string, after the plugin name: javac hands a {@code -Xplugin}
 * argument's remaining words to the plugin, and a separate {@code <arg>} is a javac option javac rejects.
 *
 * <p>{@code showWarnings} is switched on where Error Prone runs: off, the compiler plugin passes {@code -nowarn}, which
 * stops javac emitting warnings, and the demoted findings would never reach the output they are read from.
 */
@Slf4j
@Singleton
@Named("codiqo-errorprone-errors-as-warnings")
public class ErrorProneErrorsAsWarnings extends AbstractMavenLifecycleParticipant {
    public static final String ALL_ERRORS_AS_WARNINGS = "-XepAllErrorsAsWarnings";
    private static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
    private static final String COMPILER_ARTIFACT_ID = "maven-compiler-plugin";
    private static final String ERROR_PRONE_PLUGIN = "-Xplugin:ErrorProne";
    private static final String COMPILER_ARGS = "compilerArgs";
    private static final String COMPILER_ARGUMENT = "compilerArgument";
    private static final String SHOW_WARNINGS = "showWarnings";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getAllProjects()) {
            if (relax(project)) {
                log.info("[codiqo] Error Prone runs in {}: its errors are reported as warnings", project.getArtifactId());
            }
        }
    }
    /**
     * Whether Error Prone runs in the project. Every configuration of the compiler plugin is changed, the plugin-level
     * one and each execution's: the model builder has already merged the plugin-level configuration into
     * {@code default-compile} and {@code default-testCompile}, and an execution's own configuration is the one its mojo
     * runs with, so changing only the plugin level would leave the compilation untouched.
     */
    public static boolean relax(MavenProject project) {
        boolean toReturn = false;
        for (Plugin plugin : project.getBuildPlugins()) {
            if (BooleanUtils.and(new boolean[] { MAVEN_PLUGINS_GROUP_ID.equals(plugin.getGroupId()), COMPILER_ARTIFACT_ID.equals(plugin.getArtifactId()) })) {
                toReturn |= relax((Xpp3Dom) plugin.getConfiguration());
                for (PluginExecution execution : plugin.getExecutions()) {
                    toReturn |= relax((Xpp3Dom) execution.getConfiguration());
                }
            }
        }
        return toReturn;
    }
    private static boolean relax(Xpp3Dom configuration) {
        boolean toReturn = false;
        if (Objects.nonNull(configuration)) {
            Xpp3Dom compilerArgs = configuration.getChild(COMPILER_ARGS);
            if (Objects.nonNull(compilerArgs)) {
                for (Xpp3Dom arg : compilerArgs.getChildren()) {
                    toReturn |= relaxArgument(arg);
                }
            }

            Xpp3Dom compilerArgument = configuration.getChild(COMPILER_ARGUMENT);
            if (Objects.nonNull(compilerArgument)) {
                toReturn |= relaxArgument(compilerArgument);
            }

            if (toReturn) {
                Xpp3Dom showWarnings = configuration.getChild(SHOW_WARNINGS);
                if (Objects.isNull(showWarnings)) {
                    showWarnings = new Xpp3Dom(SHOW_WARNINGS);
                    configuration.addChild(showWarnings);
                }
                showWarnings.setValue(Boolean.TRUE.toString());
            }
        }
        return toReturn;
    }
    /** whether the argument runs Error Prone; one that already demotes its errors is left as it is */
    private static boolean relaxArgument(Xpp3Dom argument) {
        String value = StringUtils.defaultString(argument.getValue());
        boolean toReturn = Strings.CS.contains(value, ERROR_PRONE_PLUGIN);
        if (BooleanUtils.and(new boolean[] { toReturn, BooleanUtils.negate(Strings.CS.contains(value, ALL_ERRORS_AS_WARNINGS)) })) {
            argument.setValue(value.strip() + StringUtils.SPACE + ALL_ERRORS_AS_WARNINGS);
        }
        return toReturn;
    }
}
