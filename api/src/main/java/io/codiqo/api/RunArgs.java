package io.codiqo.api;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.eclipse.jgit.annotations.Nullable;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;

import lombok.Data;

@Data
public class RunArgs {
    private Path repo;
    private String commitId;
    @Nullable
    private final String javaExecutable = "java";
    @Nullable
    private final Duration importTimeout = Duration.ofMinutes(15);
    @Nullable
    private final Duration connectTimeout = Duration.ofSeconds(30);
    @Nullable
    private final Duration readTimeout = Duration.ofMinutes(1);
    @Nullable
    private final int maxRequests = Short.MAX_VALUE;
    @Nullable
    private final int maxRequestsPerHost = Byte.MAX_VALUE;
    @Nullable
    private final int cpdMinimumTileSize = Byte.MAX_VALUE / 2;
    @Nullable
    private final Collection<Project> projects = Lists.newArrayList();
    @Nullable
    private final Collection<Path> agents = Lists.newArrayList();

    public Optional<Project> owningProject(File filePath) {
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
                } else if (field.getType().equals(Path.class)) {
                    field.set(toReturn, Paths.get(value));
                }
            }
        }
        return toReturn;
    }
}
