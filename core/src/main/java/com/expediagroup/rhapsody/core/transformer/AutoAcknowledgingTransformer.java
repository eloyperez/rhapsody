/**
 * Copyright 2019 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.rhapsody.core.transformer;

import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public final class AutoAcknowledgingTransformer<T, U> implements Function<Publisher<T>, Flux<T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoAcknowledgingTransformer.class);

    private static final Scheduler SCHEDULER =
        Schedulers.newParallel(AutoAcknowledgingTransformer.class.getSimpleName(), Runtime.getRuntime().availableProcessors(), true);

    private final AutoAcknowledgementConfig config;

    private final Function<? super Flux<T>, ? extends Publisher<U>> reducer;

    private final Consumer<? super U> acknowledger;

    public AutoAcknowledgingTransformer(AutoAcknowledgementConfig config,
        Function<? super Flux<T>, ? extends Publisher<U>> reducer,
        Consumer<? super U> acknowledger) {
        this.config = config;
        this.reducer = reducer;
        this.acknowledger = acknowledger;
    }

    @Override
    public Flux<T> apply(Publisher<T> publisher) {
        FluxSink<T> acknowledgingSink = createAcknowledgingSink();
        return Flux.from(publisher)
            .concatMap(t -> Mono.just(t).doAfterTerminate(() -> acknowledgingSink.next(t)), config.getPrefetch())
            .doOnCancel(acknowledgingSink::complete)
            .doAfterTerminate(acknowledgingSink::complete);
    }

    private FluxSink<T> createAcknowledgingSink() {
        FluxProcessor<T, T> processor = DirectProcessor.create();
        processor.window(config.getInterval(), SCHEDULER)
            .doOnError(error -> LOGGER.warn("Failed to window Acknowledgements. Resubscribing...", error))
            .retry()
            .concatMap(reducer)
            .flatMapSequential(u -> applyDelay(processor, u), calculateMaxConcurrentAcknowledgementWindows().intValue())
            .doOnNext(acknowledger)
            .doOnError(error -> LOGGER.warn("Failed to run acknowledger. Resubscribing...", error))
            .retry()
            .subscribe();
        return processor.sink();
    }

    private Mono<U> applyDelay(Flux<?> upstream, U reduced) {
        return Mono.just(reduced).delayUntil(u -> Mono.first(upstream.ignoreElements(), Mono.delay(config.getDelay(), SCHEDULER)));
    }

    private Long calculateMaxConcurrentAcknowledgementWindows() {
        return (config.getDelay().toMillis() / config.getInterval().toMillis()) + 2;
    }
}
