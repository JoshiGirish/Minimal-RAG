package com.dev.rag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import static io.qdrant.client.QueryFactory.nearest;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/v1")
@ApplicationScoped
public class EmbeddingVectorSearch {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EmbeddingVectorIngestor client;

    @Inject
    private ExternalServicesConfig config;

    @Inject
    EmbeddingsGeneratorClient emb;

    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamChat(
            Map<String, Object> request,
            @Context SseEventSink sink,
            @Context Sse sse) {
        System.out.println("HIT CHAT ENDPOINT");
        try (sink) {

            // -----------------------------
            // 1. Extract user input
            // -----------------------------
            String userInput = extractUserInput(request);

            // -----------------------------
            // 2. RAG pipeline (clean separation)
            // -----------------------------
            String context = buildContext(userInput);

            String prompt = buildPrompt(context, userInput);
            System.out.println(prompt);

            // -----------------------------
            // 3. Call llama.cpp (streaming) - NOW CONFIGURED
            // -----------------------------
            String llmUrl = config.buildLlmUrl();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(llmUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(prompt))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            // -----------------------------
            // 4. Stream SSE properly
            // -----------------------------
            streamSse(response.body(), sink, sse);

            // -----------------------------
            // 5. Done
            // -----------------------------
            sink.send(sse.newEvent("[DONE]"));

        } catch (Exception e) {
            try {
                sink.send(sse.newEvent(
                        "{\"error\":\"" + e.getMessage() + "\"}"));
            } catch (Exception ignored) {
            }
        }
    }

    private void streamSse(java.io.InputStream input, SseEventSink sink, Sse sse)
            throws Exception {

        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(input))) {

            String line;

            while ((line = reader.readLine()) != null) {

                line = line.trim();

                if (line.isEmpty())
                    continue;

                if (!line.startsWith("data:"))
                    continue;

                String data = line.substring(5).trim();

                if ("[DONE]".equals(data)) {
                    break;
                }

                sink.send(sse.newEvent(data));
            }
        }
    }

    private String buildContext(String input) throws Exception {

        float[] vector = emb.generateEmbedding(input);

        // Build Qdrant URL from configuration
        boolean useTls = config.isQdrantUseTls();

        URI uri = URI.create(config.buildQdrantUrl());
        ManagedChannel channel = ManagedChannelBuilder
        .forAddress(uri.getHost(), uri.getPort())
        .usePlaintext()
        .build();

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(channel, useTls).build());

        List<ScoredPoint> results = client.queryAsync(
                QueryPoints.newBuilder()
                        .setCollectionName("docDB")
                        .setLimit(5)
                        .setQuery(nearest(vector))
                        .setWithPayload(WithPayloadSelectorFactory.enable(true))
                        .build())
                .get();

        StringBuilder context = new StringBuilder();

        context.append("""
                You are a helpful assistant. Use ONLY the context below.

                CONTEXT:
                """);

        for (ScoredPoint p : results) {
            context.append(
                    p.getPayloadMap()
                            .get("text")
                            .getStringValue())
                    .append("\n");
        }

        return context.toString();
    }

    private String buildPrompt(
            String context,
            String userInput) throws Exception {

        Map<String, Object> payload = Map.of(
                "model", "rag-qdrant-model",
                "stream", true,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", context),
                        Map.of(
                                "role", "user",
                                "content", userInput)));

        return MAPPER.writeValueAsString(payload);
    }

    private String extractUserInput(Map<String, Object> request) {
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");

        return messages.get(messages.size() - 1).get("content");
    }

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> listModels() {

        return Map.of(
                "object", "list",
                "data", List.of(
                        Map.of(
                                "id", "rag-qdrant-model",
                                "object", "model",
                                "created", 0,
                                "owned_by", "openai")));
    }

    @GET
    @Path("/ingest")
    @Produces(MediaType.APPLICATION_JSON)
    public void ingestData() {

        client.ingestDocuments();

    }
}
