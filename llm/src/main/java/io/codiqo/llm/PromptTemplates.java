package io.codiqo.llm;

import java.nio.charset.StandardCharsets;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import lombok.experimental.UtilityClass;

/**
 * the one resolver over thymeleaf/templates/*.txt. every prompt in this module renders through here, so the
 * engine is configured (and its template cache warmed) exactly once instead of per client
 */
@UtilityClass
public class PromptTemplates {
    private static final TemplateEngine TEMPLATE_ENGINE;
    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);
    }

    public String process(String template, Context ctx) {
        return TEMPLATE_ENGINE.process(template, ctx);
    }
}
