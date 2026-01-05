package io.codiqo.api.common;

import reactor.core.publisher.Flux;

public interface AsFlux<T> {
    Flux<T> asFlux();
}
