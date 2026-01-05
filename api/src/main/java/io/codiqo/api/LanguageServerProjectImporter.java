package io.codiqo.api;

import reactor.core.publisher.Mono;

public interface LanguageServerProjectImporter {
    Mono<?> load();
}
