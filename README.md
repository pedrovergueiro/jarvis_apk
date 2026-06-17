# Jarvis — Assistente Android

Tentei construir um assistente pessoal pra Android inspirado no Jarvis do Tony Stark. Integrei IA via API da Groq pra responder perguntas, executar comandos de voz e automatizar algumas tarefas básicas no celular.

Foi o projeto mais difícil que já encarei até então. Kotlin e Android foram completamente novos pra mim. Errei muito com ciclo de vida de Activities, com permissões do Android 12+ e com a latência das chamadas de API em tempo real. O `.github` que está aqui guarda as tentativas de CI que configurei enquanto aprendia.

Não está perfeito — longe disso — mas funciona, e aprendi mais nesse projeto do que em todos os cursos que fiz antes.

## Requisitos
- Android Studio Hedgehog (2023.1.1) ou superior
- Java 17+
- Android SDK 34
- Android 8.0+ (API 26)
- Conta Groq (gratuita em console.groq.com)

## Stack
Kotlin · Android SDK · Groq API
