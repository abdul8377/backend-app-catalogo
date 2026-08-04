param(
    [string]$OutputDirectory = "D:\AppCatalogoBackups",
    [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "Configura DB_USERNAME y DB_PASSWORD antes de ejecutar el respaldo."
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sqlFile = Join-Path $resolvedOutput "app-catalogo-$stamp.sql"
$zipFile = "$sqlFile.zip"

$previousMysqlPwd = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:DB_PASSWORD
    & mysqldump --host=127.0.0.1 --single-transaction --routines --triggers --user=$env:DB_USERNAME app_catalogo --result-file=$sqlFile
    if ($LASTEXITCODE -ne 0) { throw "mysqldump terminó con código $LASTEXITCODE" }
    Compress-Archive -LiteralPath $sqlFile -DestinationPath $zipFile -CompressionLevel Optimal
    Remove-Item -LiteralPath $sqlFile
} finally {
    $env:MYSQL_PWD = $previousMysqlPwd
}

$cutoff = (Get-Date).AddDays(-$RetentionDays)
Get-ChildItem -LiteralPath $resolvedOutput -Filter "app-catalogo-*.sql.zip" -File |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    Remove-Item

Write-Output $zipFile
