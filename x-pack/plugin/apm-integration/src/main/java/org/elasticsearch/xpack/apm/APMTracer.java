/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.apm;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.SecureSetting;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.tracing.Traceable;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.common.settings.Setting.Property.Dynamic;
import static org.elasticsearch.common.settings.Setting.Property.NodeScope;
import static org.elasticsearch.xpack.apm.APM.TRACE_HEADERS;

public class APMTracer extends AbstractLifecycleComponent implements org.elasticsearch.tracing.Tracer {

    private static final Logger LOGGER = LogManager.getLogger(APMTracer.class);

    public static final CapturingSpanExporter CAPTURING_SPAN_EXPORTER = new CapturingSpanExporter();

    static final Setting<Boolean> APM_ENABLED_SETTING = Setting.boolSetting("xpack.apm.tracing.enabled", false, Dynamic, NodeScope);
    static final Setting<SecureString> APM_ENDPOINT_SETTING = SecureSetting.secureString("xpack.apm.endpoint", null);
    static final Setting<SecureString> APM_TOKEN_SETTING = SecureSetting.secureString("xpack.apm.token", null);
    static final Setting<Float> APM_SAMPLE_RATE_SETTING = Setting.floatSetting("xpack.apm.tracing.sample_rate", 1.0f, Dynamic, NodeScope);
    static final Setting<List<String>> APM_TRACING_NAMES_INCLUDE_SETTING = Setting.listSetting(
        "xpack.apm.tracing.names.include",
        Collections.emptyList(),
        Function.identity(),
        Dynamic,
        NodeScope
    );

    private final Semaphore shutdownPermits = new Semaphore(Integer.MAX_VALUE);
    private final Map<String, Span> spans = ConcurrentCollections.newConcurrentMap();
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final SecureString endpoint;
    private final SecureString token;

    private volatile boolean enabled;
    private volatile float sampleRate;
    private volatile APMServices services;

    private List<String> includeNames;

    public void setSampleRate(float sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * This class is required to make all open telemetry services visible at once
     */
    private record APMServices(SdkTracerProvider provider, Tracer tracer, OpenTelemetry openTelemetry) {}

    public APMTracer(Settings settings, ThreadPool threadPool, ClusterService clusterService) {
        this.threadPool = Objects.requireNonNull(threadPool);
        this.clusterService = Objects.requireNonNull(clusterService);
        this.endpoint = APM_ENDPOINT_SETTING.get(settings);
        this.token = APM_TOKEN_SETTING.get(settings);
        this.enabled = APM_ENABLED_SETTING.get(settings);
        this.includeNames = APM_TRACING_NAMES_INCLUDE_SETTING.get(settings);
        this.sampleRate = APM_SAMPLE_RATE_SETTING.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(APM_ENABLED_SETTING, this::setEnabled);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(APM_TRACING_NAMES_INCLUDE_SETTING, this::setIncludeNames);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(APM_SAMPLE_RATE_SETTING, this::setSampleRate);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            createApmServices();
        } else {
            destroyApmServices();
        }
    }

    private void setIncludeNames(List<String> includeNames) {
        this.includeNames = includeNames;
    }

    @Override
    protected void doStart() {
        if (enabled) {
            createApmServices();
        }
    }

    @Override
    protected void doStop() {
        destroyApmServices();
        try {
            final boolean stopped = shutdownPermits.tryAcquire(Integer.MAX_VALUE, 30L, TimeUnit.SECONDS);
            assert stopped : "did not stop tracing within timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void doClose() {

    }

    private void createApmServices() {
        assert enabled;

        var acquired = shutdownPermits.tryAcquire();
        if (acquired == false) {
            return;// doStop() is already executed
        }

        final String endpoint = this.endpoint.toString();
        final String token = this.token.toString();

        var provider = AccessController.doPrivileged(
            (PrivilegedAction<SdkTracerProvider>) () -> SdkTracerProvider.builder()
                .setResource(
                    Resource.create(
                        Attributes.of(
                            ResourceAttributes.SERVICE_NAME,
                            "elasticsearch",
                            ResourceAttributes.SERVICE_NAMESPACE,
                            clusterService.getClusterName().value(),
                            ResourceAttributes.SERVICE_INSTANCE_ID,
                            clusterService.getNodeName(),
                            ResourceAttributes.SERVICE_VERSION,
                            Version.CURRENT.toString(),
                            ResourceAttributes.DEPLOYMENT_ENVIRONMENT,
                            "dev"
                        )
                    )
                )
                // TODO make dynamic
                .setSampler(Sampler.traceIdRatioBased(this.sampleRate))
                .addSpanProcessor(createSpanProcessor(endpoint, token))
                .build()
        );

        var openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        var tracer = openTelemetry.getTracer("elasticsearch", Version.CURRENT.toString());

        assert this.services == null;
        this.services = new APMServices(provider, tracer, openTelemetry);
    }

    private void destroyApmServices() {
        var services = this.services;
        this.services = null;
        if (services == null) {
            return;
        }
        spans.clear();// discard in-flight spans
        services.provider.shutdown().whenComplete(shutdownPermits::release);
    }

    @Override
    public void onTraceStarted(ThreadContext threadContext, Traceable traceable) {
        var services = this.services;
        if (services == null) {
            return;
        }

        if (isSpanNameIncluded(traceable.getSpanName()) == false) {
            return;
        }

        spans.computeIfAbsent(traceable.getSpanId(), spanId -> {
            // services might be in shutdown state by this point, but this is handled by the open telemetry internally
            final SpanBuilder spanBuilder = services.tracer.spanBuilder(traceable.getSpanName());
            Context parentContext = getParentSpanContext();
            if (parentContext != null) {
                spanBuilder.setParent(parentContext);
            }

            for (Map.Entry<String, Object> entry : traceable.getAttributes().entrySet()) {
                final Object value = entry.getValue();
                if (value instanceof String) {
                    spanBuilder.setAttribute(entry.getKey(), (String) value);
                } else if (value instanceof Long) {
                    spanBuilder.setAttribute(entry.getKey(), (Long) value);
                } else if (value instanceof Integer) {
                    spanBuilder.setAttribute(entry.getKey(), (Integer) value);
                } else if (value instanceof Double) {
                    spanBuilder.setAttribute(entry.getKey(), (Double) value);
                } else if (value instanceof Boolean) {
                    spanBuilder.setAttribute(entry.getKey(), (Boolean) value);
                } else {
                    throw new IllegalArgumentException(
                        "span attributes do not support value type of [" + value.getClass().getCanonicalName() + "]"
                    );
                }
            }

            // These attributes don't apply to HTTP spans. The APM server can infer a number of things
            // when "http." attributes are present
            final boolean isHttpSpan = traceable.getAttributes().keySet().stream().anyMatch(key -> key.startsWith("http."));
            if (isHttpSpan) {
                spanBuilder.setSpanKind(SpanKind.SERVER);
            } else {
                spanBuilder.setSpanKind(SpanKind.INTERNAL);
//                // hack transactions to avoid the 'custom' transaction type
//                // this one is not part of OTel semantic attributes
//                spanBuilder.setAttribute("type", "elasticsearch");
//                // hack spans to avoid the 'app' span.type, will make it use external/elasticsearch
//                // also allows to set destination resource name in map
                spanBuilder.setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "elasticsearch");
                spanBuilder.setAttribute(SemanticAttributes.MESSAGING_DESTINATION, clusterService.getNodeName());
            }

            // this will duplicate the "resource attributes" that are defined globally
            // but providing them as span attributes allow easier mapping through labels as otel attributes are stored as-is only in
            // 7.16.
            spanBuilder.setAttribute(Traceable.AttributeKeys.NODE_NAME, clusterService.getNodeName());
            spanBuilder.setAttribute(Traceable.AttributeKeys.CLUSTER_NAME, clusterService.getClusterName().value());

            final String xOpaqueId = threadPool.getThreadContext().getHeader(Task.X_OPAQUE_ID_HTTP_HEADER);
            if (xOpaqueId != null) {
                spanBuilder.setAttribute("es.x-opaque-id", xOpaqueId);
            }
            final Span span = spanBuilder.startSpan();

            final Map<String, String> spanHeaders = new HashMap<>();
            final Context contextForNewSpan = Context.current().with(span);
            services.openTelemetry.getPropagators().getTextMapPropagator().inject(contextForNewSpan, spanHeaders, Map::put);
            spanHeaders.keySet().removeIf(k -> isSupportedContextKey(k) == false);

            threadContext.putHeader(spanHeaders);

            // logGraphviz(span);

            return span;
        });
    }

    private static final Set<String> CACHE = new HashSet<>();

    @Override
    public void onTraceException(Traceable traceable, Throwable throwable) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.recordException(throwable);
        }
    }

    @Override
    public void setAttribute(Traceable traceable, String key, boolean value) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void setAttribute(Traceable traceable, String key, double value) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void setAttribute(Traceable traceable, String key, long value) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void setAttribute(Traceable traceable, String key, String value) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    private boolean isSpanNameIncluded(String name) {
        // Alternatively we could use automata here but it is much more complex
        // and it needs wrapping like done for use in the security plugin.
        return includeNames.isEmpty() || Regex.simpleMatch(includeNames, name);
    }

    private Context getParentSpanContext() {
        // Check for a parent context in the thread context.
        final ThreadContext threadContext = threadPool.getThreadContext();
        final String traceParentHeader = threadContext.getTransient("parent_" + Task.TRACE_PARENT_HTTP_HEADER);
        final String traceStateHeader = threadContext.getTransient("parent_" + Task.TRACE_STATE);

        if (traceParentHeader != null) {
            final Map<String, String> traceContextMap = new HashMap<>(2);
            // traceparent and tracestate should match the keys used by W3CTraceContextPropagator
            traceContextMap.put(Task.TRACE_PARENT_HTTP_HEADER, traceParentHeader);
            if (traceStateHeader != null) {
                traceContextMap.put(Task.TRACE_STATE, traceStateHeader);
            }
            return services.openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), traceContextMap, new MapKeyGetter());
        }
        return null;
    }

    @Override
    public void onTraceStopped(Traceable traceable) {
        final var span = spans.remove(traceable.getSpanId());
        if (span != null) {
            span.end();
        }
    }

    @Override
    public void onTraceEvent(Traceable traceable, String eventName) {
        final var span = spans.get(traceable.getSpanId());
        if (span != null) {
            span.addEvent(eventName);
        }
    }

    private static SpanProcessor createSpanProcessor(String endpoint, String token) {
        SpanProcessor processor = SimpleSpanProcessor.create(CAPTURING_SPAN_EXPORTER);
        if (Strings.hasLength(endpoint) == false || Strings.hasLength(token) == false) {
            return processor;
        }

        final OtlpGrpcSpanExporter exporter = AccessController.doPrivileged(
            (PrivilegedAction<OtlpGrpcSpanExporter>) () -> OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", "Bearer " + token)
                .build()
        );
        return SpanProcessor.composite(
            processor,
            AccessController.doPrivileged(
                (PrivilegedAction<BatchSpanProcessor>) () -> BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                    .build()
            )
        );
    }

    public static class CapturingSpanExporter implements SpanExporter {

        private final Queue<SpanData> capturedSpans = ConcurrentCollections.newQueue();

        public void clear() {
            capturedSpans.clear();
        }

        public List<SpanData> getCapturedSpans() {
            return List.copyOf(capturedSpans);
        }

        public Stream<SpanData> findSpan(Predicate<SpanData> predicate) {
            return getCapturedSpans().stream().filter(predicate);
        }

        public Stream<SpanData> findSpanByName(String name) {
            return findSpan(span -> Objects.equals(span.getName(), name));
        }

        public Stream<SpanData> findSpanBySpanId(String spanId) {
            return findSpan(span -> Objects.equals(span.getSpanId(), spanId));
        }

        public Stream<SpanData> findSpanByParentSpanId(String parentSpanId) {
            return findSpan(span -> Objects.equals(span.getParentSpanId(), parentSpanId));
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            capturedSpans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static class MapKeyGetter implements TextMapGetter<Map<String, String>> {

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet().stream().filter(APMTracer::isSupportedContextKey).collect(Collectors.toSet());
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    }

    private static boolean isSupportedContextKey(String key) {
        return TRACE_HEADERS.contains(key);
    }

    private static void logGraphviz(Span span) {
        final String spanStr = span.toString();

        int i = spanStr.indexOf("spanId=");
        int j = spanStr.indexOf(",", i);
        String spanId = spanStr.substring(i + 7, j);

        String parentSpanId = null;
        i = spanStr.indexOf("spanId=", j);
        if (i > -1) {
            j = spanStr.indexOf(",", i);
            parentSpanId = spanStr.substring(i + 7, j);
        }

        i = spanStr.indexOf("name=", j);
        j = spanStr.indexOf(",", i);
        String spanName = spanStr.substring(i + 5, j);

        if (CACHE.add(spanId)) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("label", spanName);
            if (spanName.startsWith("internal:")) {
                attrs.put("style", "filled");
                attrs.put("fillcolor", "pink");
            }
            final String attrsString = attrs.entrySet()
                .stream()
                .map(each -> each.getKey() + "=\"" + each.getValue() + "\"")
                .collect(Collectors.joining(","));
            LOGGER.warn("BADGER: __{} [{}]", spanId, attrsString);
        }

        if (parentSpanId != null) {
            LOGGER.warn("BADGER: __{} -> __{}", spanId, parentSpanId);
        }

    }
}
