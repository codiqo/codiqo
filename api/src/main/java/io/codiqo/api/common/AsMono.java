package io.codiqo.api.common;

import reactor.core.publisher.Mono;

public interface AsMono<T> {
    Mono<T> asMono();
}
