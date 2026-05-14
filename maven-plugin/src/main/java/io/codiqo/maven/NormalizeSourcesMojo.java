package io.codiqo.maven;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.RemoveUnusedImports;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.internal.DefaultJavaTypeFactory;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.staticanalysis.AtomicPrimitiveEqualsUsesGet;
import org.openrewrite.staticanalysis.BigDecimalDoubleConstructorRecipe;
import org.openrewrite.staticanalysis.BooleanChecksNotInverted;
import org.openrewrite.staticanalysis.CaseInsensitiveComparisonsDoNotChangeCase;
import org.openrewrite.staticanalysis.ChainStringBuilderAppendCalls;
import org.openrewrite.staticanalysis.CollectionToArrayShouldHaveProperType;
import org.openrewrite.staticanalysis.CovariantEquals;
import org.openrewrite.staticanalysis.DefaultComesLast;
import org.openrewrite.staticanalysis.EqualsAvoidsNull;
import org.openrewrite.staticanalysis.FixStringFormatExpressions;
import org.openrewrite.staticanalysis.InlineVariable;
import org.openrewrite.staticanalysis.IsEmptyCallOnCollections;
import org.openrewrite.staticanalysis.LambdaBlockToExpression;
import org.openrewrite.staticanalysis.ModifierOrder;
import org.openrewrite.staticanalysis.NewStringBuilderBufferWithCharArgument;
import org.openrewrite.staticanalysis.NoDoubleBraceInitialization;
import org.openrewrite.staticanalysis.NoEmptyCollectionWithRawType;
import org.openrewrite.staticanalysis.NoEqualityInForCondition;
import org.openrewrite.staticanalysis.NoPrimitiveWrappersForToStringOrCompareTo;
import org.openrewrite.staticanalysis.NoRedundantJumpStatements;
import org.openrewrite.staticanalysis.NoToStringOnStringType;
import org.openrewrite.staticanalysis.NoValueOfOnStringType;
import org.openrewrite.staticanalysis.PrimitiveWrapperClassConstructorToValueOf;
import org.openrewrite.staticanalysis.RemoveExtraSemicolons;
import org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeInstanceof;
import org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeLiteralEquals;
import org.openrewrite.staticanalysis.ReplaceClassIsInstanceWithInstanceof;
import org.openrewrite.staticanalysis.ReplaceLambdaWithMethodReference;
import org.openrewrite.staticanalysis.ReplaceStringBuilderWithString;
import org.openrewrite.staticanalysis.ReplaceStringConcatenationWithStringValueOf;
import org.openrewrite.staticanalysis.SimplifyArraysAsList;
import org.openrewrite.staticanalysis.SimplifyBooleanExpression;
import org.openrewrite.staticanalysis.SimplifyBooleanReturn;
import org.openrewrite.staticanalysis.StringLiteralEquality;
import org.openrewrite.staticanalysis.UnnecessaryExplicitTypeArguments;
import org.openrewrite.staticanalysis.UnnecessaryParentheses;
import org.openrewrite.staticanalysis.UnnecessaryReturnAsLastStatement;
import org.openrewrite.staticanalysis.UseDiamondOperator;
import org.openrewrite.staticanalysis.UseJavaStyleArrayDeclarations;
import org.openrewrite.staticanalysis.UsePortableNewlines;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.util.JGit;

@Mojo(name = "normalize-sources",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        aggregator = true)
public class NormalizeSourcesMojo extends AbstractMojo {
    private static final Set<String> NON_CODE_PACKAGINGS = Set.of("pom", "bom");
    private static final String JAVA_EXTENSION = ".java";
    private static final List<Class<? extends Recipe>> DEFAULT_RECIPE_CLASSES = ImmutableList.<Class<? extends Recipe>>builder()
            .add(AtomicPrimitiveEqualsUsesGet.class)
            .add(BigDecimalDoubleConstructorRecipe.class)
            .add(BooleanChecksNotInverted.class)
            .add(CaseInsensitiveComparisonsDoNotChangeCase.class)
            .add(ChainStringBuilderAppendCalls.class)
            .add(CollectionToArrayShouldHaveProperType.class)
            .add(CovariantEquals.class)
            .add(DefaultComesLast.class)
            .add(EqualsAvoidsNull.class)
            .add(FixStringFormatExpressions.class)
            .add(InlineVariable.class)
            .add(IsEmptyCallOnCollections.class)
            .add(NewStringBuilderBufferWithCharArgument.class)
            .add(NoDoubleBraceInitialization.class)
            .add(NoEmptyCollectionWithRawType.class)
            .add(NoEqualityInForCondition.class)
            .add(NoPrimitiveWrappersForToStringOrCompareTo.class)
            .add(NoRedundantJumpStatements.class)
            .add(NoToStringOnStringType.class)
            .add(NoValueOfOnStringType.class)
            .add(PrimitiveWrapperClassConstructorToValueOf.class)
            .add(RemoveRedundantNullCheckBeforeInstanceof.class)
            .add(RemoveRedundantNullCheckBeforeLiteralEquals.class)
            .add(ReplaceClassIsInstanceWithInstanceof.class)
            .add(ReplaceStringBuilderWithString.class)
            .add(ReplaceStringConcatenationWithStringValueOf.class)
            .add(SimplifyArraysAsList.class)
            .add(SimplifyBooleanExpression.class)
            .add(SimplifyBooleanReturn.class)
            .add(StringLiteralEquality.class)
            .add(UnnecessaryReturnAsLastStatement.class)
            .add(UsePortableNewlines.class)
            //
            // ~ import / syntax cleanup
            //
            .add(RemoveUnusedImports.class)
            .add(UnnecessaryParentheses.class)
            .add(RemoveExtraSemicolons.class)
            .add(UseDiamondOperator.class)
            .add(UnnecessaryExplicitTypeArguments.class)
            .add(ModifierOrder.class)
            .add(UseJavaStyleArrayDeclarations.class)
            .add(ShortenFullyQualifiedTypeReferences.class)
            //
            // ~ lambda normalisation (last — operates on output of prior recipes)
            //
            .add(ReplaceLambdaWithMethodReference.class)
            .add(LambdaBlockToExpression.class)
            //.add(UseLambdaForFunctionalInterface.class)
            .build();

    @Parameter(property = "codiqo.recipes")
    private String recipes;

    @Parameter(property = "codiqo.commitAuthor", defaultValue = "Codiqo System")
    private String commitAuthor;

    @Parameter(property = "codiqo.commitEmail", defaultValue = "system@codiqo.io")
    private String commitEmail;

    @Parameter(property = "codiqo.commitMessage", defaultValue = "code normalize: apply open rewrites")
    private String commitMessage;

    @Parameter(property = "codiqo.dryRun", defaultValue = "true")
    private boolean dryRun;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    private List<MavenProject> reactors;

    @Override
    public void execute() throws MojoFailureException {
        try {
            Path baseDir = project.getBasedir().toPath().toAbsolutePath().normalize();
            List<Recipe> recipeList = instantiateRecipes();
            getLog().info(String.format("active recipes: %d", recipeList.size()));
            recipeList.forEach(r -> getLog().info("  — " + r.getClass().getSimpleName()));

            try (Repository repo = JGit.openRepository(project.getBasedir())) {
                try (Git git = Git.wrap(repo)) {
                    Status status = git.status().call();
                    if (hasTrackedModifications(status)) {
                        throw new MojoFailureException("working tree must be clean before normalization — commit or stash pending changes in advance");
                    }
                }

                StopWatch stopWatch = StopWatch.createStarted();
                List<SourceFile> allSourceFiles = parseAllModules(baseDir, repo);
                stopWatch.stop();

                getLog().info(String.format("parsed %d source files in %s", allSourceFiles.size(), stopWatch));

                Map<Path, List<String>> fileRecipeMap = Maps.newLinkedHashMap();

                stopWatch = StopWatch.createStarted();
                List<Result> results = runRecipes(recipeList, allSourceFiles, fileRecipeMap);
                stopWatch.stop();
                getLog().info(String.format("recipes completed in %s — %d file(s) changed", stopWatch, results.size()));

                logFileSummary(fileRecipeMap, baseDir);

                writeResults(results, baseDir);
                if (dryRun) {
                    getLog().info(String.format("dry run complete — %d file(s) written, review with 'git diff' and reset with 'git checkout .'", results.size()));
                } else {
                    commitChanges(repo, results, baseDir);
                }
            }
        } catch (MojoFailureException err) {
            throw err;
        } catch (Exception err) {
            throw new MojoFailureException("normalization failed: " + err.getMessage(), err);
        }
    }
    private List<Recipe> instantiateRecipes() throws MojoFailureException {
        List<Class<? extends Recipe>> classes = resolveRecipeClasses();
        List<Recipe> toReturn = Lists.newArrayList();

        for (Class<? extends Recipe> clazz : classes) {
            try {
                toReturn.add(clazz.getDeclaredConstructor().newInstance());
            } catch (Exception err) {
                throw new MojoFailureException("failed to instantiate recipe: " + clazz.getName(), err);
            }
        }
        return toReturn;
    }
    @SuppressWarnings("unchecked")
    private List<Class<? extends Recipe>> resolveRecipeClasses() throws MojoFailureException {
        if (StringUtils.isBlank(recipes)) {
            return DEFAULT_RECIPE_CLASSES;
        }

        List<String> names = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(recipes);
        List<Class<? extends Recipe>> toReturn = Lists.newArrayList();

        for (String name : names) {
            try {
                Class<?> clazz = Class.forName(name);
                toReturn.add((Class<? extends Recipe>) clazz);
            } catch (ClassNotFoundException err) {
                throw new MojoFailureException("recipe class not found: " + name
                        + " — ensure the recipe artifact is on the plugin classpath", err);
            }
        }
        return toReturn;
    }
    private List<SourceFile> parseAllModules(Path baseDir, Repository repo) throws Exception {
        ExecutionContext ctx = new InMemoryExecutionContext(err -> getLog().warn("parse error: " + err.getMessage()));
        JavaTypeCache typeCache = new JavaTypeCache();
        DirCache index = repo.readDirCache();
        List<SourceFile> toReturn = Lists.newArrayList();

        for (MavenProject reactor : reactors) {
            if (NON_CODE_PACKAGINGS.contains(reactor.getPackaging())) {
                continue;
            }

            List<Path> sourceFiles = collectJavaFiles(reactor, baseDir, index);
            if (CollectionUtils.isNotEmpty(sourceFiles)) {
                List<Path> classpath = resolveClasspath(reactor);

                getLog().info(String.format("parsing module %s — %d files, %d classpath entries",
                        reactor.getArtifactId(),
                        sourceFiles.size(),
                        classpath.size()));

                JavaParser parser = JavaParser.fromJavaVersion()
                        .classpath(classpath)
                        .logCompilationWarningsAndErrors(true)
                        .typeFactory(new DefaultJavaTypeFactory(typeCache))
                        .build();

                parser.parse(sourceFiles, baseDir, ctx).forEach(toReturn::add);
            }
        }
        return toReturn;
    }
    private List<Result> runRecipes(List<Recipe> recipeList, List<SourceFile> sourceFiles, Map<Path, List<String>> fileRecipeMap) {
        ExecutionContext ctx = new InMemoryExecutionContext(err -> getLog().warn("recipe error: " + err.getMessage()));
        List<SourceFile> current = Lists.newArrayList(sourceFiles);
        Set<Path> changedPaths = Sets.newLinkedHashSet();

        for (Recipe recipe : recipeList) {
            StopWatch stopWatch = StopWatch.createStarted();
            RecipeRun run = recipe.run(new InMemoryLargeSourceSet(current), ctx);
            List<Result> results = run.getChangeset().getAllResults().stream()
                    .filter(r -> BooleanUtils.and(new boolean[] { Objects.nonNull(r.getBefore()), Objects.nonNull(r.getAfter()) }))
                    .collect(Collectors.toList());
            stopWatch.stop();

            if (CollectionUtils.isNotEmpty(results)) {
                String recipeName = recipe.getClass().getSimpleName();
                getLog().info(String.format("  %s — %d change(s) in %s", recipeName, results.size(), stopWatch));

                for (Result result : results) {
                    Path path = result.getAfter().getSourcePath();
                    changedPaths.add(path);
                    fileRecipeMap.computeIfAbsent(path, k -> Lists.newArrayList()).add(recipeName);

                    current = current.stream()
                            .map(sf -> sf.getSourcePath().equals(result.getBefore().getSourcePath()) ? result.getAfter() : sf)
                            .collect(Collectors.toList());
                }
            }
        }

        List<Result> toReturn = Lists.newArrayList();
        for (int i = 0; i < sourceFiles.size(); i++) {
            SourceFile original = sourceFiles.get(i);
            SourceFile modified = current.get(i);
            if (changedPaths.contains(original.getSourcePath())) {
                toReturn.add(new Result(original, modified, List.of()));
            }
        }
        return toReturn;
    }
    private void logFileSummary(Map<Path, List<String>> fileRecipeMap, Path baseDir) {
        if (fileRecipeMap.isEmpty()) {
            return;
        }

        getLog().info("");
        getLog().info("per-file summary:");
        for (Map.Entry<Path, List<String>> entry : fileRecipeMap.entrySet()) {
            getLog().info(String.format("  %s — [%s]",
                    baseDir.resolve(entry.getKey()),
                    Joiner.on(", ").join(entry.getValue())));
        }
        getLog().info("");
    }
    private void writeResults(List<Result> results, Path baseDir) throws IOException {
        for (Result result : results) {
            Path target = baseDir.resolve(result.getAfter().getSourcePath());
            Charset charset = Optional.ofNullable(result.getAfter().getCharset()).orElse(StandardCharsets.UTF_8);

            try (BufferedWriter writer = Files.newBufferedWriter(target, charset)) {
                writer.write(result.getAfter().printAll());
            }
            getLog().info("rewritten: " + target);
        }
    }
    private void commitChanges(Repository repo, List<Result> results, Path baseDir) throws Exception {
        try (Git git = Git.wrap(repo)) {
            for (Result result : results) {
                String relativePath = result.getAfter().getSourcePath().toString();
                git.add().addFilepattern(relativePath).call();
            }

            PersonIdent author = new PersonIdent(commitAuthor, commitEmail);
            RevCommit commit = git.commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage(commitMessage)
                    .call();

            getLog().info(String.format("committed as %s <%s> — %s", commitAuthor, commitEmail, commit.getName()));
        }
    }
    private static List<Path> collectJavaFiles(MavenProject reactor, Path baseDir, DirCache index) {
        List<Path> toReturn = Lists.newArrayList();
        List<String> roots = Lists.newArrayList();
        roots.addAll(reactor.getCompileSourceRoots());
        roots.addAll(reactor.getTestCompileSourceRoots());

        for (String root : roots) {
            Path rootPath = Paths.get(root);
            if (Files.isDirectory(rootPath)) {
                try (Stream<Path> walk = Files.walk(rootPath)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(JAVA_EXTENSION))
                            .filter(p -> index.findEntry(baseDir.relativize(p).toString().replace(File.separatorChar, '/')) >= 0)
                            .forEach(toReturn::add);
                } catch (IOException err) {
                    ExceptionUtils.wrapAndThrow(err);
                }
            }
        }
        return toReturn;
    }
    private static boolean hasTrackedModifications(Status status) {
        return BooleanUtils.or(new boolean[] {
                CollectionUtils.isNotEmpty(status.getModified()),
                CollectionUtils.isNotEmpty(status.getChanged()),
                CollectionUtils.isNotEmpty(status.getAdded()),
                CollectionUtils.isNotEmpty(status.getRemoved()),
                CollectionUtils.isNotEmpty(status.getMissing()),
                CollectionUtils.isNotEmpty(status.getConflicting())
        });
    }
    private static List<Path> resolveClasspath(MavenProject reactor) throws DependencyResolutionRequiredException {
        Set<String> elements = Sets.newLinkedHashSet();
        elements.addAll(reactor.getCompileClasspathElements());
        elements.addAll(reactor.getTestClasspathElements());

        return elements.stream()
                .map(Paths::get)
                .filter(Files::exists)
                .collect(Collectors.toList());
    }
}
