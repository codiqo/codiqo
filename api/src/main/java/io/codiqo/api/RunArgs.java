package io.codiqo.api;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import lombok.Data;

@Data
public class RunArgs {
    private String commitId;

    @Nullable
    private String jdtlsVersion = "1.55.0";
    @Nullable
    private boolean includeUntracked = true;
    @Nullable
    private boolean autoBuild = false;
    @Nullable
    private boolean dumpAnalysis = true;
    @Nullable
    private boolean ignoreCoverage = false;
    @Nullable
    private boolean ignoreComplexity = false;
    @Nullable
    private boolean ignoreCpd = false;
    @Nullable
    private boolean ignoreDiagnostics = false;
    @Nullable
    private File javaHome;
    @Nullable
    private File mavenHome;
    @Nullable
    private Duration importTimeout = Duration.ofMinutes(15);
    @Nullable
    private Duration connectTimeout = Duration.ofSeconds(30);
    @Nullable
    private Duration readTimeout = Duration.ofMinutes(1);
    @Nullable
    private int maxRequests = Short.MAX_VALUE;
    @Nullable
    private int maxRequestsPerHost = Byte.MAX_VALUE;
    @Nullable
    private int cpdMinimumTileSize = Byte.MAX_VALUE / 2;
    @Nullable
    private transient List<ProjectSpec> projects = Lists.newArrayList();
    @Nullable
    private transient List<File> agents = Lists.newArrayList();
    @Nullable
    private Repository git;
    @Nullable
    private String defaultBranch;
    @Nullable
    private transient Set<String> remoteUrls = Sets.newHashSet();

    public Optional<ProjectSpec> owner(File filePath) {
        return projects.stream().filter(proj -> proj.contains(filePath)).findAny();
    }
    public static Options options() {
        Options toReturn = new Options();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            String camel = field.getName();
            String kebab = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, camel);
            Builder builder = Option.builder().longOpt(kebab).hasArg();
            builder = builder.required(Objects.isNull(field.getAnnotation(Nullable.class)));
            toReturn.addOption(builder.get());
        }
        return toReturn;
    }
    public static RunArgs from(CommandLine cmd) throws Exception {
        RunArgs toReturn = new RunArgs();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            String camel = field.getName();
            String kebab = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, camel);
            if (cmd.hasOption(kebab)) {
                String value = cmd.getOptionValue(kebab);
                if (field.getType().equals(String.class)) {
                    field.set(toReturn, value);
                } else if (field.getType().equals(int.class)) {
                    field.set(toReturn, Integer.parseInt(value));
                } else if (field.getType().equals(Duration.class)) {
                    field.set(toReturn, Duration.parse(value));
                } else if (field.getType().equals(File.class)) {
                    field.set(toReturn, new File(value));
                } else if (field.getType().equals(Repository.class)) {
                    field.set(toReturn, new FileRepositoryBuilder().setGitDir(new File(value, ".git")).readEnvironment().findGitDir().build());
                }
            }
        }
        return toReturn;
    }
}
