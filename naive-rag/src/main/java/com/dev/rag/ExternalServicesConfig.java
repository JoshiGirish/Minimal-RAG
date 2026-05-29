package com.dev.rag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Centralized configuration service for external services.
 * Provides a single source of truth for all external service URLs.
 * Supports environment variable overrides and feature flags.
 */
@ApplicationScoped
public class ExternalServicesConfig {

    // ============================================================
    // QDRANT CONFIGURATION
    // ============================================================
    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.qdrant.url")
    private String qdrantUrl;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.qdrant.port")
    private int qdrantPort;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.qdrant.use-tls", defaultValue = "false")
    private boolean qdrantUseTls;

    // ============================================================
    // EMBEDDING SERVICE CONFIGURATION
    // ============================================================
    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.embedding.url")
    private String embeddingUrl;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.embedding.port")
    private int embeddingPort;

    // ============================================================
    // LLM SERVICE CONFIGURATION
    // ============================================================
    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.llm.url")
    private String llmUrl;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.llm.port")
    private int llmPort;

    // ============================================================
    // FEATURE FLAGS
    // ============================================================
    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.qdrant.enabled", defaultValue = "true")
    private boolean qdrantEnabled;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.embedding.enabled", defaultValue = "true")
    private boolean embeddingEnabled;

    @Inject
    @ConfigProperty(name = "quarkus.http.external-services.llm.enabled", defaultValue = "true")
    private boolean llmEnabled;

    // ============================================================
    // UTILITY METHODS
    // ============================================================
    public String getQdrantUrl() {
        return qdrantUrl;
    }

    public int getQdrantPort() {
        return qdrantPort;
    }

    public boolean isQdrantUseTls() {
        return qdrantUseTls;
    }

    public String getEmbeddingUrl() {
        return embeddingUrl;
    }

    public int getEmbeddingPort() {
        return embeddingPort;
    }

    public String getLlmUrl() {
        return llmUrl;
    }

    public int getLlmPort() {
        return llmPort;
    }

    public boolean isQdrantEnabled() {
        return qdrantEnabled;
    }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    // ============================================================
    // CONVENIENCE BUILDERS
    // ============================================================
    public String buildQdrantUrl() {
        return qdrantUrl + ":" + qdrantPort;
    }

    public String buildEmbeddingUrl() {
        return embeddingUrl + ":" + embeddingPort;
    }

    public String buildLlmUrl() {
        return llmUrl + ":" + llmPort;
    }
}