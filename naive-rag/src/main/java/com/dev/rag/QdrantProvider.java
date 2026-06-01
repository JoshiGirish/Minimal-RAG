package com.dev.rag;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.dev.rag.ExternalServicesConfig;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class QdrantProvider {

    @Inject
    ExternalServicesConfig config;

    private QdrantClient client;

    private static final Logger logger = LoggerFactory.getLogger(QdrantProvider.class);
    
    /** Default timeout in seconds */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    @PostConstruct
    void init() {

        URI uri = URI.create(config.buildQdrantUrl());

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(uri.getHost(), uri.getPort())
                .usePlaintext() // switch if TLS enabled
                .build();

        client = new QdrantClient(
                QdrantGrpcClient.newBuilder(
                        channel,
                        config.isQdrantUseTls())
                        .build());
    }

    public QdrantClient getQdrantClient() {
        return client;
    }

    /**
     * Creates a collection with retry logic and proper error handling.
     *
     * @param client Qdrant client instance
     * @param collectionName Name of the collection to create
     * @param vectorSize Dimension of vectors
     * @param distance Distance metric for similarity search
     * @throws RuntimeException if collection creation fails after retries
     */
    public void createCollectionAsync(
            String collectionName,
            int vectorSize,
            Distance distance) {
        
        logger.info("Creating collection: {} with {} dimensions, distance: {}",
                   collectionName, vectorSize, distance);
        
        VectorParams.Builder params = VectorParams.newBuilder()
                .setDistance(distance)
                .setSize(vectorSize);
        
        int maxRetries = 3;
        int retryDelayMs = 500;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                client.createCollectionAsync(collectionName, params.build()).get(
                        DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                logger.info("Collection '{}' created successfully", collectionName);
                return;
            } catch (TimeoutException e) {
                logger.warn("Timeout waiting for collection '{}' creation (attempt {})", 
                           collectionName, attempt);
            } catch (Exception e) {
                logger.warn("Attempt {} failed to create collection '{}': {}", 
                           attempt, collectionName, e.getMessage());
            }
            
            if (attempt < maxRetries) {
                logger.info("Retrying collection creation in {}ms...", retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted while waiting for retry");
                    break;
                }
            }
        }
        
        logger.error("Failed to create collection '{}' after {} attempts", 
                   collectionName, maxRetries);
        throw new RuntimeException("Collection creation failed after multiple retries", 
                                  new IllegalStateException("Collection creation exhausted all retries"));
    }
}