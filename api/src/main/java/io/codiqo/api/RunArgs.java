package io.codiqo.api;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;

import lombok.Data;

@Data
public class RunArgs {
    private String commitId;
    private boolean includeUntracked = true;

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
    private transient Collection<Project> projects = Lists.newArrayList();
    @Nullable
    private transient Collection<File> agents = Lists.newArrayList();
    @Nullable
    private transient Repository git;

    public Optional<Project> owner(File filePath) {
        for (Project proj : projects) {
            if (filePath.toPath().startsWith(proj.getBaseDirectory().toPath())) {
                return Optional.of(proj);

            }
        }
        return Optional.empty();
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
                }
            }
        }
        return toReturn;
    }
}
