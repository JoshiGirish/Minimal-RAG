package com.dev.rag;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EmbeddingVectorIngestor {

	private static String collectionName = "docDB";
	private static EmbeddingVectorIngestor ingestorClient;

	@Inject
	EmbeddingsGeneratorClient embeddingGeneratorClient;

	/**
	 * Represents a chunk of document content with metadata.
	 */
	public static class ChunkedContent {

		private final String content;
		private final int wordCount;

		public ChunkedContent(
				String content,
				int wordCount) {
			this.content = content;
			this.wordCount = wordCount;
		}

		public String getContent() {
			return content;
		}

		public int getWordCount() {
			return wordCount;
		}
	}

	private static long toLongId(String input) {
		return Math.abs(input.hashCode());
	}

	public void ingestDocuments() {

		try {

			// ----------------------------------------
			// Parse documents
			// ----------------------------------------

			TikaDocumentParser parser = new TikaDocumentParser();

			List<TikaDocumentParser.DocumentParseResult> docs = parser.parseDirectory(
					"/deployments/ingest");

			// ----------------------------------------
			// Create Qdrant client ONCE
			// ----------------------------------------

			try (QdrantClient vectorDBClient = new QdrantClient(
					QdrantGrpcClient.newBuilder(
							"qdrant",
							6334,
							false).build())) {

				// ----------------------------------------
				// Ensure collection exists
				// ----------------------------------------

				ensureCollection(vectorDBClient);

				// ----------------------------------------
				// Process documents
				// ----------------------------------------

				for (TikaDocumentParser.DocumentParseResult doc : docs) {

					System.out.println(
							"Processing: "
									+ doc.getFileName());

					List<ChunkedContent> chunks = chunkContentWithMetadata(
							doc.getContent(),
							50);

					List<PointStruct> points = new ArrayList<>();

					int chunkIndex = 0;

					for (ChunkedContent chunk : chunks) {

						// ----------------------------------------
						// Generate embedding
						// ----------------------------------------

						float[] vectorEmbedding = embeddingGeneratorClient
								.generateEmbedding(
										chunk.getContent());

						// ----------------------------------------
						// Deterministic chunk ID
						// ----------------------------------------

						String chunkId = doc.getFileName()
								+ "::"
								+ chunkIndex;

						// ----------------------------------------
						// Build point
						// ----------------------------------------

						PointStruct point = PointStruct.newBuilder()
								.setId(
										id(
												toLongId(chunkId)))
								.setVectors(
										vectors(vectorEmbedding))
								.putAllPayload(Map.of(

										"text",
										value(chunk.getContent()),

										"fileName",
										value(doc.getFileName()),

										"mimeType",
										value(doc.getMimeType()),

										"chunkIndex",
										value(chunkIndex),

										"chunkId",
										value(chunkId),

										"model",
										value("nomic-embed-text"),

										"timestamp",
										value(
												System.currentTimeMillis())))
								.build();

						points.add(point);

						chunkIndex++;
					}

					// ----------------------------------------
					// Batch upsert
					// ----------------------------------------

					if (!points.isEmpty()) {

						UpdateResult result = vectorDBClient
								.upsertAsync(
										collectionName,
										points)
								.get();

						System.out.println(
								"Inserted "
										+ points.size()
										+ " chunks from "
										+ doc.getFileName());
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Chunks content while preserving metadata about each chunk.
	 *
	 * @param content   The full document content
	 * @param chunkSize Maximum size of each chunk in characters
	 * @return List of ChunkedContent objects with metadata
	 */
	public static List<ChunkedContent> chunkContentWithMetadata(
			String content,
			int minWords) {

		List<ChunkedContent> chunks = new ArrayList<>();

		if (content == null || content.isBlank()) {
			return chunks;
		}

		// ----------------------------------------
		// Split into sentences
		// ----------------------------------------

		BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);

		iterator.setText(content);

		List<String> sentences = new ArrayList<>();

		int start = iterator.first();

		for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {

			String sentence = content.substring(start, end).trim();

			if (!sentence.isBlank()) {
				sentences.add(sentence);
			}
		}

		// ----------------------------------------
		// Build chunks
		// ----------------------------------------

		StringBuilder currentChunk = new StringBuilder();

		int currentWordCount = 0;

		for (String sentence : sentences) {

			int sentenceWords = sentence.split("\\s+").length;

			// Add sentence
			currentChunk
					.append(sentence)
					.append(" ");

			currentWordCount += sentenceWords;

			// Emit chunk once minimum reached
			if (currentWordCount >= minWords) {

				chunks.add(
						new ChunkedContent(
								currentChunk.toString().trim(),
								currentWordCount));

				// Reset
				currentChunk = new StringBuilder();
				currentWordCount = 0;
			}
		}

		// ----------------------------------------
		// Remaining content
		// ----------------------------------------

		if (!currentChunk.isEmpty()) {

			chunks.add(
					new ChunkedContent(
							currentChunk.toString().trim(),
							currentWordCount));
		}

		return chunks;
	}

	private static void ensureCollection(
			QdrantClient client) throws Exception {

		try {

			client.getCollectionInfoAsync(
					collectionName).get();

			System.out.println(
					"Collection exists.");

		} catch (Exception e) {

			System.out.println(
					"Creating collection...");

			QdrantClientHelper.createCollectionAsync(
					client,
					collectionName,
					768,
					Distance.Cosine);

			System.out.println(
					"Collection created.");
		}
	}
}
