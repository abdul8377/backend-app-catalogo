param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [switch]$ConfirmRestore
)

$ErrorActionPreference = "Stop"
if (-not $ConfirmRestore) { throw "La restauración reemplaza datos. Vuelve a ejecutar con -ConfirmRestore." }
if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "Configura DB_USERNAME y DB_PASSWORD antes de restaurar."
}

$resolvedBackup = [System.IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf)) { throw "No existe el respaldo indicado." }
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("app-catalogo-restore-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$previousMysqlPwd = $env:MYSQL_PWD
try {
    Expand-Archive -LiteralPath $resolvedBackup -DestinationPath $temporaryRoot
    $sql = Get-ChildItem -LiteralPath $temporaryRoot -Filter "*.sql" -File | Select-Object -First 1
    if ($null -eq $sql) { throw "El ZIP no contiene un archivo SQL." }
    $env:MYSQL_PWD = $env:DB_PASSWORD
    Get-Content -LiteralPath $sql.FullName -Raw | & mysql --host=127.0.0.1 --user=$env:DB_USERNAME app_catalogo
    if ($LASTEXITCODE -ne 0) { throw "mysql terminó con código $LASTEXITCODE" }
} finally {
    $env:MYSQL_PWD = $previousMysqlPwd
    if (Test-Path -LiteralPath $temporaryRoot) { Remove-Item -LiteralPath $temporaryRoot -Recurse }
}

Write-Output "Restauración finalizada. Inicia el backend y verifica /actuator/health."
