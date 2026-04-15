# Jarvis Android Assistant

Assistente pessoal de IA estilo JARVIS para Android, com voz, automação e IA adaptativa.

---

## Pré-requisitos

- Android Studio Hedgehog (2023.1.1) ou superior
- Java 17+
- Android SDK 34
- Dispositivo Android 8.0+ (API 26+)
- Conta Groq (gratuita): https://console.groq.com

---

## Instalação Rápida (Android Studio)

### 1. Abrir o projeto
```
File → Open → selecionar pasta jarvis-android/
```

### 2. Sincronizar Gradle
```
File → Sync Project with Gradle Files
```

### 3. Configurar chave Porcupine (wake word)
- Criar conta gratuita em: https://console.picovoice.ai
- Copiar Access Key
- Substituir `PORCUPINE_ACCESS_KEY_HERE` em `WakeWordDetector.kt`

### 4. Build e instalar
```
Build → Build APK(s)
# ou pressionar Shift+F10 com dispositivo conectado
```

---

## Build via linha de comando

```bash
# Linux/Mac
chmod +x scripts/build_apk.sh
./scripts/build_apk.sh

# Windows
gradlew.bat assembleDebug
```

O APK será gerado em: `app/build/outputs/apk/debug/app-debug.apk`

---

## Configuração pós-instalação

Após instalar o APK, conceder as seguintes permissões:

### Obrigatórias (solicitadas automaticamente)
- Microfone
- Contatos
- Notificações

### Manuais (via Configurações do app)
1. **Acesso a Notificações**: Configurações → Apps → Acesso especial → Acesso a notificações → Jarvis
2. **Acessibilidade**: Configurações → Acessibilidade → Jarvis → Ativar
3. **Alterar configurações do sistema**: Configurações → Apps → Jarvis → Permissões especiais

---

## Backend (Opcional)

O app funciona standalone via Groq API. O backend é opcional para uso com modelos locais.

```bash
cd backend
pip install -r requirements.txt
python main.py
# API em http://localhost:8000
```

---

## Comandos de Voz Suportados

| Comando | Ação |
|---------|------|
| "Jarvis, abre o WhatsApp" | Abre o app |
| "Manda mensagem pro João no WhatsApp" | Abre chat |
| "Lê minhas notificações" | Lê notificações em voz |
| "Aumenta o volume" | Controla volume |
| "Cria lembrete de reunião às 15h" | Cria alarme |
| "Busca receita de bolo" | Abre Google |
| "Modo foco" | Silencia notificações |
| "Resumo do dia" | Resume atividades |
| "Liga para Maria" | Faz ligação |
| "Liga a lanterna" | Controla flash |
| "Me explica machine learning" | Resposta detalhada via IA |

---

## Arquitetura

```
STT (Whisper V3 Turbo via Groq)
    ↓
WakeWord (Porcupine offline)
    ↓
IntentRouter (regex offline)
    ↓ (se não reconhecer)
AIEngine (dNaty: seleção dinâmica)
    ├── llama-3.1-8b-instant  (rápido)
    ├── llama-3.3-70b-versatile (inteligente)
    └── qwen-qwq-32b (ultra)
    ↓
CommandExecutor (ações Android)
    ↓
TTS (Android nativo + neural)
```

### Algoritmo dNaty
Seleção adaptativa de modelo baseada em:
- Complexidade estimada da query
- Taxa de sucesso histórica por modelo
- Latência média observada
- Disponibilidade de rede

---

## Modo Fone de Ouvido

O app funciona automaticamente com fones Bluetooth:
- Wake word detectada via microfone do fone
- Respostas direcionadas ao fone
- Baixo consumo: wake word usa ~2% CPU (Porcupine)

---

## Solução de Problemas

**App não inicia após reinicialização**
→ Verificar se "Iniciar com o dispositivo" está ativo nas configurações

**Wake word não funciona**
→ Verificar chave Porcupine em `WakeWordDetector.kt`
→ Usar botão manual como alternativa

**Respostas lentas**
→ Verificar conexão com internet
→ Mudar modelo para "Rápido" nas configurações

**Não lê notificações**
→ Conceder permissão de Acesso a Notificações manualmente
