import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import jakarta.enterprise.context.ApplicationScoped;
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

    // =========================================================
    // STREAMING CHAT ENDPOINT
    // =========================================================
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
            // 3. Call llama.cpp (streaming)
            // -----------------------------
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/v1/chat/completions"))
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

    // =========================================================
    // SSE STREAMING FIX (IMPORTANT PART)
    // =========================================================
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

    // =========================================================
    // RAG PIPELINE (Qdrant)
    // =========================================================
    private String buildContext(String input) throws Exception {

        EmbeddingsGeneratorClient emb = new EmbeddingsGeneratorClient();
        float[] vector = emb.generateEmbedding(input);

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build());

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

    // =========================================================
    // PROMPT BUILDER (IMPORTANT FOR RAG QUALITY)
    // =========================================================
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

    // =========================================================
    // HELPERS
    // =========================================================
    private String extractUserInput(Map<String, Object> request) {
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");

        return messages.get(messages.size() - 1).get("content");
    }

    // =========================================================
    // MODELS (unchanged)
    // =========================================================
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
}