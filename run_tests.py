#!/usr/bin/env python3
"""
Ejecuta las pruebas REALES del proyecto con Gradle y devuelve su código de salida.

(Versiones anteriores de este script solo imprimían una lista fija de "✓" y
"TODOS LOS TEST PASARON" sin ejecutar nada. Eso era engañoso y se eliminó.)

Uso:  python run_tests.py            -> todas las pruebas
      python run_tests.py :app:test  -> solo las del módulo app (o cualquier tarea de Gradle)
"""
import os
import subprocess
import sys

root = os.path.dirname(os.path.abspath(__file__))
gradlew = os.path.join(root, "gradlew.bat" if os.name == "nt" else "gradlew")
tasks = sys.argv[1:] or ["test"]

if not os.path.exists(gradlew):
    print(f"No se encontró {gradlew}. Ejecuta este script desde la raíz del proyecto.")
    sys.exit(2)

print("=== Ejecutando pruebas con Gradle:", " ".join(tasks), "===")
result = subprocess.run([gradlew, *tasks, "--console=plain"], cwd=root)

if result.returncode == 0:
    print("\n=== Gradle terminó SIN errores ===")
else:
    print(f"\n=== Gradle terminó con errores (código {result.returncode}). Revisa la salida de arriba. ===")
sys.exit(result.returncode)
