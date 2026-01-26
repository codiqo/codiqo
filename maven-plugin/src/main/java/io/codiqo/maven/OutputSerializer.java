package io.codiqo.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.maven.plugin.logging.Log;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@RequiredArgsConstructor
class OutputSerializer implements SubmissionPopulator {
    private final boolean preferYaml;
    private final Log log;

    @Override
    @SneakyThrows
    public void accept(SubmissionContext ctx) {
        if (!ctx.getArgs().isDumpAnalysis()) {
            return;
        }

        ObjectMapper mapper = preferYaml ? new YAMLMapper() : new ObjectMapper();
        mapper.setDefaultPropertyInclusion(Include.NON_NULL);
        mapper.setDateFormat(new StdDateFormat());
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.registerModule(new JavaTimeModule());

        String extension = preferYaml ? "yaml" : "json";
        File file = Files.createTempFile("codiqo-submission-", "." + extension).toFile();
        String output = mapper.writeValueAsString(ctx.getSubmissionModel());
        try (FileOutputStream stream = new FileOutputStream(file)) {
            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                bufferedStream.write(output.getBytes(StandardCharsets.UTF_8));
                bufferedStream.flush();
            }
        }
        log.info("analysis submission written to " + file.getAbsolutePath());
    }
}
