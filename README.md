# Naive RAG System

A containerized Retrieval-Augmented Generation (RAG) system built with Quarkus, Qdrant, and Llama.cpp for intelligent document-based Q&A.

## Architecture Overview

![RAG Architecture](docs/minimal-rag.png)

- **Quarkus** for the backend orchestration API
- **Qdrant** as the vector database
- **Llama.cpp** for local inference and embeddings
- **OpenWebUI** as the chat frontend
- **Apache Tika** for document parsing

This setup runs entirely inside a Docker network and supports:

- Local LLM inference
- Local embedding generation
- Semantic document search
- Streaming chat responses (SSE)
- Offline/batch document ingestion

---

# Architecture Overview

## Runtime Query Flow

```text
User
  │
  ▼
OpenWebUI (Port 3000)
  │
  ▼
Quarkus Backend API (Port 8085)
  │
  ├── Generate Embedding (llama.cpp)
  ├── Search Qdrant
  ├── Build Context
  ├── Run LLM Inference (llama.cpp)
  │
  ▼
Stream Response via SSE
  │
  ▼
OpenWebUI