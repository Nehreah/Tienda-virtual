#!/usr/bin/env bash
# Ordena los archivos sueltos de la raíz. Es seguro repetirlo. Usa "git mv" si hay git (conserva el historial).
# Uso:  bash scripts/limpiar_repositorio.sh
set -e
cd "$(dirname "$0")/.."

mover() {  # mover ORIGEN DESTINO
    [ -e "$1" ] || return 0
    mkdir -p "$(dirname "$2")"
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && git ls-files --error-unmatch "$1" >/dev/null 2>&1; then
        git mv "$1" "$2"
    else
        mv "$1" "$2"
    fi
    echo "  $1 -> $2"
}

echo "Moviendo archivos..."
mover DIAGRAMA_DE_CLASES_v14.md docs/DIAGRAMA_DE_CLASES.md
mover DIAGRAMA_DE_CLASES_v13.md docs/historico/DIAGRAMA_DE_CLASES_v13.md
mover MANUAL_DE_CAMBIOS_v13.md docs/historico/MANUAL_DE_CAMBIOS_v13.md
mover MANUAL_DE_CAMBIOS_v14.md docs/historico/MANUAL_DE_CAMBIOS_v14.md
mover Plan_Maestro_Automata_Comercial_Multicanal_v5.3.pdf docs/Plan_Maestro_Automata_Comercial_Multicanal_v5.3.pdf
mover infra/NEGOCIO.xlsx docs/plantilla/NEGOCIO.xlsx
if [ -d correciones ]; then
    for f in correciones/*; do mover "$f" "docs/historico/$(basename "$f")"; done
    rmdir correciones 2>/dev/null || true
fi
echo "Listo. Revisa con 'git status' y confirma. Revisa que docs/plantilla/NEGOCIO.xlsx no tenga datos reales."
