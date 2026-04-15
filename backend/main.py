"""
Jarvis Backend - FastAPI
Gateway para modelos de IA e Whisper STT.
Opcional: usar quando quiser rodar modelos locais no servidor.
"""

from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, List
import httpx
import json
import asyncio
import os
import tempfile

app = FastAPI(title="Jarvis Backend", version="1.0.0")

# Configuração
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_BASE_URL = "https://api.groq.com/openai/v1"

SYSTEM_PROMPT = """Você é Jarvis, um assistente pessoal de IA avançado.
Seja direto, eficiente e responda sempre em português brasileiro.
Seja conciso em respostas simples (máximo 2-3 frases)."""

# ─── Modelos ──────────────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    message: str
    history: Optional[List[dict]] = []
    model: Optional[str] = "llama-3.1-8b-instant"
    stream: Optional[bool] = False

class ChatResponse(BaseModel):
    response: str
    model_used: str
    latency_ms: int

# ─── dNaty: Seleção Dinâmica de Modelo ────────────────────────────────────────

model_stats = {
    "llama-3.1-8b-instant": {"calls": 0, "success": 0, "total_latency": 0},
    "llama-3.3-70b-versatile": {"calls": 0, "success": 0, "total_latency": 0},
    "qwen-qwq-32b": {"calls": 0, "success": 0, "total_latency": 0},
}

def estimate_complexity(query: str) -> float:
    """Estima complexidade da query (0.0 a 1.0) - algoritmo dNaty"""
    words = len(query.split())
    complex_keywords = ["explica", "analisa", "compara", "resume", "como funciona", "por que", "diferença"]
    simple_keywords = ["abre", "fecha", "hora", "data", "volume", "brilho"]
    
    if any(k in query.lower() for k in simple_keywords):
        return 0.1
    if any(k in query.lower() for k in complex_keywords):
        return 0.8
    if words > 20:
        return 0.7
    if words > 10:
        return 0.5
    return 0.3

def select_model_dnaty(query: str, requested_model: str = "auto") -> str:
    """
    dNaty: Seleção adaptativa de modelo baseada em:
    - Complexidade da query
    - Performance histórica (taxa de sucesso, latência)
    - Custo/benefício
    """
    if requested_model != "auto":
        return requested_model
    
    complexity = estimate_complexity(query)
    
    fast_stats = model_stats["llama-3.1-8b-instant"]
    fast_success_rate = fast_stats["success"] / max(fast_stats["calls"], 1)
    
    if complexity < 0.3 and fast_success_rate > 0.8:
        return "llama-3.1-8b-instant"
    elif complexity > 0.7:
        return "qwen-qwq-32b"
    else:
        return "llama-3.3-70b-versatile"

# ─── Endpoints ────────────────────────────────────────────────────────────────

@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """Chat com seleção dinâmica de modelo (dNaty)"""
    import time
    
    model = select_model_dnaty(request.message, request.model)
    start = time.time()
    
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(request.history[-10:])  # Últimas 10 mensagens
    messages.append({"role": "user", "content": request.message})
    
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(
                f"{GROQ_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                json={
                    "model": model,
                    "messages": messages,
                    "max_tokens": 512,
                    "temperature": 0.7,
                }
            )
            response.raise_for_status()
            data = response.json()
            text = data["choices"][0]["message"]["content"].strip()
            
            latency = int((time.time() - start) * 1000)
            
            # Atualizar stats dNaty
            model_stats[model]["calls"] += 1
            model_stats[model]["success"] += 1
            model_stats[model]["total_latency"] += latency
            
            return ChatResponse(response=text, model_used=model, latency_ms=latency)
    
    except Exception as e:
        model_stats[model]["calls"] += 1
        # Fallback para modelo mais simples
        if model != "llama-3.1-8b-instant":
            request.model = "llama-3.1-8b-instant"
            return await chat(request)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat/stream")
async def chat_stream(request: ChatRequest):
    """Chat com streaming de resposta"""
    model = select_model_dnaty(request.message, request.model)
    
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(request.history[-10:])
    messages.append({"role": "user", "content": request.message})
    
    async def generate():
        async with httpx.AsyncClient(timeout=60) as client:
            async with client.stream(
                "POST",
                f"{GROQ_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                json={
                    "model": model,
                    "messages": messages,
                    "max_tokens": 512,
                    "temperature": 0.7,
                    "stream": True,
                }
            ) as response:
                async for line in response.aiter_lines():
                    if line.startswith("data: ") and line != "data: [DONE]":
                        try:
                            chunk = json.loads(line[6:])
                            content = chunk["choices"][0]["delta"].get("content", "")
                            if content:
                                yield f"data: {json.dumps({'text': content})}\n\n"
                        except Exception:
                            pass
        yield "data: [DONE]\n\n"
    
    return StreamingResponse(generate(), media_type="text/event-stream")

@app.post("/transcribe")
async def transcribe(audio: UploadFile = File(...)):
    """Transcrição de áudio via Whisper Large V3 Turbo"""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        content = await audio.read()
        tmp.write(content)
        tmp_path = tmp.name
    
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            with open(tmp_path, "rb") as f:
                response = await client.post(
                    f"{GROQ_BASE_URL}/audio/transcriptions",
                    headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                    files={"file": (audio.filename, f, "audio/wav")},
                    data={
                        "model": "whisper-large-v3-turbo",
                        "language": "pt",
                        "response_format": "json"
                    }
                )
                response.raise_for_status()
                return response.json()
    finally:
        os.unlink(tmp_path)

@app.get("/stats")
async def get_stats():
    """Estatísticas dos modelos (dNaty monitoring)"""
    stats = {}
    for model, data in model_stats.items():
        calls = data["calls"]
        stats[model] = {
            "calls": calls,
            "success_rate": data["success"] / max(calls, 1),
            "avg_latency_ms": data["total_latency"] / max(data["success"], 1)
        }
    return stats

@app.get("/health")
async def health():
    return {"status": "ok", "version": "1.0.0"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
