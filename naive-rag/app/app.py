from typing import TypedDict

from fastapi import FastAPI, Request
from langgraph.graph import StateGraph, END
from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI

# FastAPI application instance
app = FastAPI()

# LLM configuration - connects to a local LLM server
llm = ChatOpenAI(
    model="Qwen3.5-9B.Q4_K_M.gguf",
    base_url="http://localhost:8080/v1",
    temperature=0
)

# State schema - defines input/output for the graph

class AgentState(TypedDict):
    input: str
    output: str


def call_model(state: AgentState):
    """LLM node - processes the input message and returns the response."""
    response = llm.invoke([
        HumanMessage(content=state["input"])
    ])

    return {
        "output": response.content
    }


# Graph builder - defines the workflow
builder = StateGraph(AgentState)

# Add the LLM node to the graph
builder.add_node("llm", call_model)

# Set the entry point where execution starts
builder.set_entry_point("llm")

# Connect the LLM node to the END marker
builder.add_edge("llm", END)

# Compile the graph into an executable object
graph = builder.compile()


@app.get("/")
def home():
    """Health check endpoint."""
    return {"status": "running"}


@app.post("/chat")
async def chat(request: Request):
    """Process a chat message through the LangGraph workflow."""
    msg = await request.body()
    msg = msg.decode("utf-8")

    result = graph.invoke({
        "input": msg
    })

    return result["output"]
