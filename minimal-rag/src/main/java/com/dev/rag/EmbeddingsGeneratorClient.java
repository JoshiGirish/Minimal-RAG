import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class EmbeddingsGeneratorClient {

    public float[] generateEmbedding(String input) {
        try {
            // 1. Prepare the JSON payload using Jettison
            JSONObject jsonPayload = new JSONObject();
            jsonPayload.put("input", input);
            jsonPayload.put("model", "nomic-embed-text");

            // 2. Create connection
            URL url = new URL("http://localhost:8081/v1/embeddings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // 3. Configure connection for POST
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // 4. Write JSON payload
            try (OutputStream os = conn.getOutputStream()) {
                byte[] req = jsonPayload.toString().getBytes("utf-8");
                os.write(req, 0, req.length);
            }

            // 5. Get response code
            int responseCode = conn.getResponseCode();
            System.out.println("Response Code: " + responseCode);

            // 6. Read response (success case)
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    
                    System.out.println("Response:");
                    System.out.println(response.toString());
                    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    if(jsonResponse.has("data")){
                        JSONArray dataArray =  jsonResponse.getJSONArray("data");
                        for(int i=0; i<dataArray.length(); i++){
                            JSONObject jsonObj = dataArray.getJSONObject(i);
                            if(jsonObj.has("embedding")){
                                JSONArray embeddingArray = jsonObj.getJSONArray("embedding");
                                float[] vector = new float[embeddingArray.length()];
                                for (int j = 0; j < embeddingArray.length(); j++) {
                                    vector[j] = (float) embeddingArray.getDouble(j);   // important: getDouble() → cast to float
                                }
                                return vector;
                            }
                        }
                    }
                }
            } 
            else {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                    StringBuilder error = new StringBuilder();
                    String errorLine;
                    while ((errorLine = br.readLine()) != null) {
                        error.append(errorLine.trim());
                    }
                    System.out.println("Error response: " + error);
                }
            }

            conn.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}