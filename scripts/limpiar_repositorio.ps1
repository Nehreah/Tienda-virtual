# Ordena los archivos sueltos de la raiz (Windows). Es seguro repetirlo.
# Uso:  powershell -ExecutionPolicy Bypass -File scripts\limpiar_repositorio.ps1
Set-Location (Join-Path $PSScriptRoot "..")

function Mover($origen, $destino) {
    if (-not (Test-Path $origen)) { return }
    New-Item -ItemType Directory -Force -Path (Split-Path $destino) | Out-Null
    $rastreado = $false
    if (Get-Command git -ErrorAction SilentlyContinue) {
        git ls-files --error-unmatch $origen *> $null
        if ($LASTEXITCODE -eq 0) { $rastreado = $true }
    }
    if ($rastreado) { git mv $origen $destino } else { Move-Item $origen $destino }
    Write-Host "  $origen -> $destino"
}

Write-Host "Moviendo archivos..."
Mover "DIAGRAMA_DE_CLASES_v14.md" "docs\DIAGRAMA_DE_CLASES.md"
Mover "DIAGRAMA_DE_CLASES_v13.md" "docs\historico\DIAGRAMA_DE_CLASES_v13.md"
Mover "MANUAL_DE_CAMBIOS_v13.md" "docs\historico\MANUAL_DE_CAMBIOS_v13.md"
Mover "MANUAL_DE_CAMBIOS_v14.md" "docs\historico\MANUAL_DE_CAMBIOS_v14.md"
Mover "Plan_Maestro_Automata_Comercial_Multicanal_v5.3.pdf" "docs\Plan_Maestro_Automata_Comercial_Multicanal_v5.3.pdf"
Mover "infra\NEGOCIO.xlsx" "docs\plantilla\NEGOCIO.xlsx"
if (Test-Path "correciones") {
    Get-ChildItem "correciones" | ForEach-Object { Mover $_.FullName ("docs\historico\" + $_.Name) }
    Remove-Item "correciones" -ErrorAction SilentlyContinue
}
Write-Host "Listo. Revisa con 'git status'. Verifica que docs\plantilla\NEGOCIO.xlsx no tenga datos reales."
