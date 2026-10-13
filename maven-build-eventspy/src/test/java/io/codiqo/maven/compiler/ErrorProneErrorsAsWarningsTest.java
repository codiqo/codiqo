package io.codiqo.maven.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class ErrorProneErrorsAsWarningsTest {
    private static final String MAVEN_PLUGINS = "org.apache.maven.plugins";
    private static final String COMPILER = "maven-compiler-plugin";
    private static final String ERROR_PRONE = "-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode -Xep:MissingOverride:OFF";

    @Test
    void appendsTheFlagToTheErrorProneEntryOfCompilerArgs() {
        Plugin compiler = compiler(configuration(compilerArgs("-XDcompilePolicy=simple", ERROR_PRONE, "-parameters")));

        assertTrue(ErrorProneErrorsAsWarnings.relax(project(compiler)));

        Xpp3Dom args = config(compiler).getChild("compilerArgs");
        assertEquals("-XDcompilePolicy=simple", args.getChild(0).getValue());
        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", args.getChild(1).getValue());
        assertEquals("-parameters", args.getChild(2).getValue());
        assertEquals("true", config(compiler).getChild("showWarnings").getValue());
    }
    @Test
    void appendsTheFlagToASingleCompilerArgument() {
        Xpp3Dom configuration = configuration();
        configuration.addChild(element("compilerArgument", "\n    " + ERROR_PRONE + "\n  "));
        Plugin compiler = compiler(configuration);

        assertTrue(ErrorProneErrorsAsWarnings.relax(project(compiler)));

        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(compiler).getChild("compilerArgument").getValue());
    }
    @Test
    void changesEveryExecutionTheCompilationRunsWith() {
        Plugin compiler = compiler(configuration(compilerArgs(ERROR_PRONE)));
        PluginExecution defaultCompile = execution("default-compile", configuration(compilerArgs(ERROR_PRONE)));
        PluginExecution defaultTestCompile = execution("default-testCompile", configuration(compilerArgs("-XDcompilePolicy=simple", ERROR_PRONE)));
        compiler.setExecutions(List.of(defaultCompile, defaultTestCompile));

        assertTrue(ErrorProneErrorsAsWarnings.relax(project(compiler)));

        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(compiler).getChild("compilerArgs").getChild(0).getValue());
        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(defaultCompile).getChild("compilerArgs").getChild(0).getValue());
        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(defaultTestCompile).getChild("compilerArgs").getChild(1).getValue());
        assertEquals("true", config(defaultTestCompile).getChild("showWarnings").getValue());
    }
    @Test
    void leavesACompilationWithoutErrorProneAlone() {
        Xpp3Dom configuration = configuration(compilerArgs("-parameters", "-Xlint:all"));
        configuration.addChild(element("showWarnings", "false"));
        Plugin compiler = compiler(configuration);

        assertFalse(ErrorProneErrorsAsWarnings.relax(project(compiler)));

        assertEquals("-parameters", config(compiler).getChild("compilerArgs").getChild(0).getValue());
        assertEquals("-Xlint:all", config(compiler).getChild("compilerArgs").getChild(1).getValue());
        assertEquals("false", config(compiler).getChild("showWarnings").getValue());
    }
    @Test
    void doesNotRepeatAFlagTheProjectAlreadySets() {
        Xpp3Dom configuration = configuration(compilerArgs("-Xplugin:ErrorProne -XepAllErrorsAsWarnings"));
        configuration.addChild(element("showWarnings", "false"));
        Plugin compiler = compiler(configuration);

        assertTrue(ErrorProneErrorsAsWarnings.relax(project(compiler)));

        assertEquals("-Xplugin:ErrorProne -XepAllErrorsAsWarnings", config(compiler).getChild("compilerArgs").getChild(0).getValue());
        assertEquals("true", config(compiler).getChild("showWarnings").getValue(), "the warnings are what the analysis reads");
    }
    @Test
    void leavesOtherPluginsAlone() {
        Plugin other = new Plugin();
        other.setGroupId("org.example");
        other.setArtifactId(COMPILER);
        other.setConfiguration(configuration(compilerArgs(ERROR_PRONE)));

        assertFalse(ErrorProneErrorsAsWarnings.relax(project(other)));

        assertEquals(ERROR_PRONE, config(other).getChild("compilerArgs").getChild(0).getValue());
    }
    @Test
    void toleratesACompilerWithoutConfiguration() {
        Plugin compiler = compiler(null);
        compiler.setExecutions(List.of(execution("default-compile", null)));

        assertFalse(ErrorProneErrorsAsWarnings.relax(project(compiler)));
        assertNull(compiler.getConfiguration());
    }
    /**
     * A module inherits the compiler configuration of its parent, from the reactor or a released one, and Maven hands
     * the extension the merged effective model: the module's plugin carries the parent's arguments as if declared
     * there. Both modules of a reactor are changed, each in its own model.
     */
    @Test
    void changesTheConfigurationEveryReactorModuleInherited() {
        Plugin parentCompiler = compiler(configuration(compilerArgs(ERROR_PRONE)));
        Plugin childCompiler = compiler(configuration(compilerArgs(ERROR_PRONE)));
        MavenProject parent = project(parentCompiler);
        MavenProject child = project(childCompiler);
        child.setParent(parent);

        assertTrue(ErrorProneErrorsAsWarnings.relax(parent));
        assertTrue(ErrorProneErrorsAsWarnings.relax(child));

        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(parentCompiler).getChild("compilerArgs").getChild(0).getValue());
        assertEquals(ERROR_PRONE + " -XepAllErrorsAsWarnings", config(childCompiler).getChild("compilerArgs").getChild(0).getValue());
    }
    private static MavenProject project(Plugin plugin) {
        Build build = new Build();
        build.addPlugin(plugin);

        Model model = new Model();
        model.setGroupId("org.example");
        model.setArtifactId("module");
        model.setVersion("1.0");
        model.setBuild(build);
        return new MavenProject(model);
    }
    private static Plugin compiler(Xpp3Dom configuration) {
        Plugin toReturn = new Plugin();
        toReturn.setGroupId(MAVEN_PLUGINS);
        toReturn.setArtifactId(COMPILER);
        toReturn.setConfiguration(configuration);
        return toReturn;
    }
    private static PluginExecution execution(String id, Xpp3Dom configuration) {
        PluginExecution toReturn = new PluginExecution();
        toReturn.setId(id);
        toReturn.setConfiguration(configuration);
        return toReturn;
    }
    private static Xpp3Dom configuration(Xpp3Dom... children) {
        Xpp3Dom toReturn = new Xpp3Dom("configuration");
        for (Xpp3Dom child : children) {
            toReturn.addChild(child);
        }
        return toReturn;
    }
    private static Xpp3Dom compilerArgs(String... args) {
        Xpp3Dom toReturn = new Xpp3Dom("compilerArgs");
        for (String arg : args) {
            toReturn.addChild(element("arg", arg));
        }
        return toReturn;
    }
    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom toReturn = new Xpp3Dom(name);
        toReturn.setValue(value);
        return toReturn;
    }
    private static Xpp3Dom config(Plugin plugin) {
        return (Xpp3Dom) plugin.getConfiguration();
    }
    private static Xpp3Dom config(PluginExecution execution) {
        return (Xpp3Dom) execution.getConfiguration();
    }
}
