#!/bin/bash
# Setup do backend Python

set -e

echo "=== Jarvis Backend Setup ==="

cd "$(dirname "$0")/../backend"

# Criar ambiente virtual
python3 -m venv venv
source venv/bin/activate

# Instalar dependências
pip install -r requirements.txt

echo "✓ Backend configurado!"
echo ""
echo "Para iniciar o backend:"
echo "  cd backend && source venv/bin/activate && python main.py"
echo ""
echo "API disponível em: http://localhost:8000"
echo "Docs: http://localhost:8000/docs"
