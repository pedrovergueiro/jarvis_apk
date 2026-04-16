"""
Natiele Backend — FastAPI
Bridge entre Android e Desktop PC.
Compartilha memória, contexto e comandos entre dispositivos.
"""

from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, List
import httpx
import json
import asyncio
import os
import time
import uuid
from datetime import datetime

app = FastAPI(title="Natiele Backend", version="2.0.0")

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_BASE_URL = "https://api.groq.com/openai/v1"
MODEL = "llama-3.3-70b-versatile"
MODEL_FALLBACK = "llama-3.1-8b-instant"

SYSTEM_PROMPT = """Você é Natiele, assistente pessoal de IA.
Direta, inteligente, confiante. Respostas curtas (máximo 2 frases) exceto quando pedido detalhe.
Nunca começa com "Claro!" ou "Certamente!". Vai direto ao ponto.
Você existe no celular Android E no PC do usuário simultaneamente.
Você tem memória compartilhada entre os dois dispositivos.
Idioma: sempre português brasileiro."""

# ─── Memória compartilhada em memória (substituir por Redis/DB em produção) ───
shared_memory: dict = {}          # key → value
conversation_history: list = []   # histórico global
device_status: dict = {           # status dos dispositivos
    "android": {"online": False, "last_seen": None, "battery": None},
    "desktop": {"online": False, "last_seen": None}
}

# ─── Modelos ──────────────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    message: str
    history: Optional[List[dict]] = []
    device: Optional[str] = "android"  # "android" ou "desktop"
    user_context: Optional[str] = ""

class ChatResponse(BaseModel):
    response: str
    model_used: str
    latency_ms: int

class MemoryItem(BaseModel):
    key: str
    value: str
    category: Optional[str] = "general"

class DeviceStatus(BaseModel):
    device: str
    battery: Optional[int] = None
    location: Optional[str] = None
    active_app: Optional[str] = None

# ─── Chat ─────────────────────────────────────────────────────────────────────

@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    start = time.time()

    # Adicionar contexto de memória compartilhada
    memory_context = "\n".join([f"{k}: {v}" for k, v in list(shared_memory.items())[-10:]])
    device_ctx = f"Dispositivo: {request.device}"

    system = f"{SYSTEM_PROMPT}\n\nMemória:\n{memory_context}\n{device_ctx}"
    if request.user_context:
        system += f"\n{request.user_context}"

    messages = [{"role": "system", "content": system}]

    # Histórico global compartilhado (últimas 6 trocas)
    for item in conversation_history[-12:]:
        messages.append(item)

    messages.append({"role": "user", "content": request.message})

    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(
                f"{GROQ_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                json={"model": MODEL, "messages": messages, "max_tokens": 200, "temperature": 0.7}
            )
            response.raise_for_status()
            data = response.json()
            text = data["choices"][0]["message"]["content"].strip()

            # Salvar no histórico compartilhado
            conversation_history.append({"role": "user", "content": request.message})
            conversation_history.append({"role": "assistant", "content": text})
            if len(conversation_history) > 100:
                conversation_history.pop(0)
                conversation_history.pop(0)

            latency = int((time.time() - start) * 1000)
            return ChatResponse(response=text, model_used=MODEL, latency_ms=latency)

    except Exception as e:
        # Fallback
        try:
            async with httpx.AsyncClient(timeout=20) as client:
                response = await client.post(
                    f"{GROQ_BASE_URL}/chat/completions",
                    headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                    json={"model": MODEL_FALLBACK, "messages": messages, "max_tokens": 200}
                )
                data = response.json()
                text = data["choices"][0]["message"]["content"].strip()
                latency = int((time.time() - start) * 1000)
                return ChatResponse(response=text, model_used=MODEL_FALLBACK, latency_ms=latency)
        except Exception as e2:
            raise HTTPException(status_code=500, detail=str(e2))

@app.post("/transcribe")
async def transcribe(audio: UploadFile = File(...)):
    """Whisper Large V3 Turbo para transcrição de áudio"""
    import tempfile
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
                    data={"model": "whisper-large-v3-turbo", "language": "pt", "response_format": "json"}
                )
                response.raise_for_status()
                return response.json()
    finally:
        os.unlink(tmp_path)

# ─── Memória Compartilhada ────────────────────────────────────────────────────

@app.post("/memory")
async def save_memory(item: MemoryItem):
    """Salva item na memória compartilhada (acessível por Android e Desktop)"""
    shared_memory[item.key] = item.value
    return {"status": "saved", "key": item.key}

@app.get("/memory")
async def get_memory():
    """Retorna toda a memória compartilhada"""
    return shared_memory

@app.get("/memory/{key}")
async def get_memory_item(key: str):
    if key not in shared_memory:
        raise HTTPException(status_code=404, detail="Chave não encontrada")
    return {"key": key, "value": shared_memory[key]}

@app.delete("/memory/{key}")
async def delete_memory(key: str):
    shared_memory.pop(key, None)
    return {"status": "deleted"}

# ─── Status dos Dispositivos ──────────────────────────────────────────────────

@app.post("/device/status")
async def update_device_status(status: DeviceStatus):
    """Android e Desktop reportam seu status aqui"""
    device_status[status.device] = {
        "online": True,
        "last_seen": datetime.now().isoformat(),
        "battery": status.battery,
        "active_app": status.active_app,
        "location": status.location
    }
    return {"status": "updated"}

@app.get("/device/status")
async def get_all_status():
    """Retorna status de todos os dispositivos — usado pelo Desktop para saber o que o celular está fazendo"""
    return device_status

@app.get("/device/status/{device}")
async def get_device_status(device: str):
    return device_status.get(device, {"online": False})

# ─── Histórico ────────────────────────────────────────────────────────────────

@app.get("/history")
async def get_history(limit: int = 20):
    """Histórico de conversa compartilhado entre dispositivos"""
    return conversation_history[-limit:]

@app.delete("/history")
async def clear_history():
    conversation_history.clear()
    return {"status": "cleared"}

# ─── Health ───────────────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "version": "2.0.0",
        "name": "Natiele",
        "devices_online": [k for k, v in device_status.items() if v.get("online")]
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
