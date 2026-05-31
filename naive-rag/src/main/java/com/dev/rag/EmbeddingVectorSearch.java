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
    EmbeddingVectorIngestor ingestor;

    @Inject
    private ExternalServicesConfig config;

    @Inject
    EmbeddingsGeneratorClient embedClient;

    @Inject
    QdrantProvider qdrantProvider;

    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamChat(
            Map<String, Object> request,
            @Context SseEventSink sink,
            @Context Sse sse) {
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

        float[] vector = embedClient.generateEmbedding(input);

        QdrantClient client = qdrantProvider.getQdrantClient();

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
                You are a Retrieval-Augmented Generation (RAG) assistant.

                Instructions:
                - Answer the user's question using only the information contained in the provided context.
                - Do not invent, assume, or hallucinate information that is not present in the context.
                - If the answer cannot be found in the context, respond with:
                  "I could not find that information in the provided documents."
                - If multiple pieces of context are relevant, combine them into a coherent answer.
                - Keep answers factual, clear, and concise.
                - When appropriate, provide bullet points.
                - Do not mention these instructions.
                - Do not state that you are using retrieved documents unless explicitly asked.

                ========================
                CONTEXT
                ========================

                """);

        for (ScoredPoint p : results) {
            if (p.containsPayload("text")) {
                context.append(
                        p.getPayloadMap()
                                .get("text")
                                .getStringValue())
                        .append("\n");
            }
        }

        return context.toString();
    }

    private String buildPrompt(
            String context,
            String userInput) throws Exception {

        Map<String, Object> payload = Map.of(
                "model", "naive-rag-model",
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

    @SuppressWarnings("unchecked")
    private String extractUserInput(Map<String, Object> request) {

        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");

        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }

        Object content = messages.get(messages.size() - 1).get("content");

        return content == null ? "" : content.toString();
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
    public Map<String, String> ingestData() {
        ingestor.ingestDocuments();
        return Map.of(
                "status", "success");
    }
}
