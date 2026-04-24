package io.codiqo.maven.populator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.logging.Log;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class OutputSerializer implements SubmissionPopulator {
    private final boolean preferYaml;
    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        if (ctx.getArgs().isDumpAnalysis()) {
            try {
                ObjectMapper mapper = preferYaml ? new YAMLMapper() : new ObjectMapper();
                mapper.setDefaultPropertyInclusion(Include.NON_NULL);
                mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.registerModule(new JavaTimeModule());

                String extension = preferYaml ? "yaml" : "json";
                File outputDir = ctx.getArgs().getOutputDirectory();
                File file;
                if (Objects.nonNull(outputDir)) {
                    FileUtils.forceMkdir(outputDir);
                    file = new File(outputDir, "codiqo-submission." + extension);
                } else {
                    file = Files.createTempFile("codiqo-submission-", "." + extension).toFile();
                }
                String output = mapper.writeValueAsString(ctx.getSubmissionModel());
                try (OutputStream stream = Files.newOutputStream(file.toPath())) {
                    try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                        bufferedStream.write(output.getBytes(StandardCharsets.UTF_8));
                        bufferedStream.flush();
                    }
                }
                log.info("analysis submission written to " + file.getAbsolutePath());
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
