package com.dev.rag;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper class for creating and managing Qdrant client connections securely.
 * Follows security best practices including TLS, proper error handling,
 * and resource management.
 */
public class QdrantClientHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(QdrantClientHelper.class);
    
    /** Default Qdrant gRPC port */
    private static final int DEFAULT_PORT = 6334;
    
    /** Default timeout in seconds */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    
    /** Default timeout for client creation */
    private static final long CLIENT_CREATION_TIMEOUT = 10;
    
    /**
     * Creates a Qdrant client with secure configuration.
     *
     * @param host Qdrant server hostname or IP address
     * @param port gRPC port (default: 6334)
     * @param useTls Enable TLS encryption (recommended for production)
     * @return QdrantClient instance
     */
    public static QdrantClient createClient(String host, int port, boolean useTls) {
        if (!isValidHost(host)) {
            throw new IllegalArgumentException("Invalid host: " + host);
        }
        
        logger.info("Creating Qdrant client - Host: {}, Port: {}, TLS: {}", 
                   host, port, useTls);
        
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls);
        
        try {
            QdrantGrpcClient client = builder.build();
            logger.debug("Qdrant gRPC client created successfully");
            return new QdrantClient(client);
        } catch (Exception e) {
            logger.error("Failed to create Qdrant client: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create Qdrant client", e);
        }
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
    public static void createCollectionAsync(
            QdrantClient client,
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
    
    /**
     * Closes the Qdrant client and releases resources.
     *
     * @param client Qdrant client instance to close
     */
    public static void closeClient(QdrantClient client) {
        if (client != null) {
            try {
                client.close();
                logger.debug("Qdrant client closed successfully");
            } catch (Exception e) {
                logger.error("Error closing Qdrant client: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Main entry point for testing the client.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java com.dev.ws.qdrant.QdrantClientHelper <host> [port] [development]");
            System.out.println("  host: Qdrant server address (default: localhost)");
            System.out.println("  port: gRPC port (default: 6334)");
            System.out.println("  development: skip TLS for local testing (default: false)");
            return;
        }
        
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        boolean development = args.length > 2 && args[2].equalsIgnoreCase("development");
        
        QdrantClient client;
        try {
            client = createClient(host, port, !development);
            
            String collectionName = "sectorDB";
            int vectorSize = 768;
            Distance distance = Distance.Cosine;
            
            logger.info("Creating collection: {} with {} dimensions", collectionName, vectorSize);
            createCollectionAsync(client, collectionName, vectorSize, distance);
            logger.info("Collection created successfully");
            
            // Keep client alive until interrupted
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received, closing Qdrant client");
                closeClient(client);
            }));
            
        } catch (Exception e) {
            logger.error("Error occurred: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Validates host address format.
     *
     * @param host Host address to validate
     * @return true if valid, false otherwise
     */
    static boolean isValidHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = host.trim();
        
        // Accept localhost variants
        if (trimmed.equalsIgnoreCase("localhost") || 
            trimmed.equalsIgnoreCase("127.0.0.1") ||
            trimmed.equals("0.0.0.0")) {
            return true;
        }
        
        // Accept standard hostnames and IP addresses
        if (trimmed.matches("^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$")) {
            return true;
        }
        
        // Accept IPv6 addresses
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return true;
        }
        
        logger.warn("Invalid host format: {}", host);
        return false;
    }
}

