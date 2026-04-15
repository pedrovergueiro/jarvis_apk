#!/bin/bash
# Script de build do APK Jarvis
# Requer: Android SDK, Java 17+

set -e

echo "=== Jarvis APK Builder ==="

# Verificar dependências
if ! command -v java &> /dev/null; then
    echo "ERRO: Java não encontrado. Instale Java 17+"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "ERRO: Java 17+ necessário. Versão atual: $JAVA_VERSION"
    exit 1
fi

# Navegar para raiz do projeto Android
cd "$(dirname "$0")/.."

echo "1. Limpando build anterior..."
./gradlew clean

echo "2. Compilando APK debug..."
./gradlew assembleDebug

echo "3. Localizando APK..."
APK_PATH=$(find . -name "*.apk" -path "*/debug/*" | head -1)

if [ -f "$APK_PATH" ]; then
    echo "✓ APK gerado: $APK_PATH"
    cp "$APK_PATH" ./jarvis-debug.apk
    echo "✓ Copiado para: ./jarvis-debug.apk"
    
    # Instalar no dispositivo conectado (se disponível)
    if command -v adb &> /dev/null; then
        DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
        if [ "$DEVICES" -gt 0 ]; then
            echo "4. Instalando no dispositivo..."
            adb install -r ./jarvis-debug.apk
            echo "✓ Instalado com sucesso!"
            echo "5. Iniciando app..."
            adb shell am start -n com.jarvis.assistant/.ui.MainActivity
        else
            echo "Nenhum dispositivo conectado. Instale manualmente: ./jarvis-debug.apk"
        fi
    fi
else
    echo "ERRO: APK não encontrado"
    exit 1
fi

echo ""
echo "=== Build concluído ==="
