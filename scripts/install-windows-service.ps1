param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$ServiceName = "AppCatalogoBackend",
    [string]$JavaExecutable = "java.exe"
)

$ErrorActionPreference = "Stop"
$resolvedJar = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) { throw "No existe el JAR indicado." }
if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)
    -or [string]::IsNullOrWhiteSpace($env:ADMIN_USERNAME) -or [string]::IsNullOrWhiteSpace($env:ADMIN_PASSWORD)) {
    throw "Configura DB_USERNAME, DB_PASSWORD, ADMIN_USERNAME y ADMIN_PASSWORD como variables de sistema antes de instalar."
}

$binaryPath = '"' + $JavaExecutable + '" -jar "' + $resolvedJar + '"'
& sc.exe create $ServiceName binPath= $binaryPath start= auto DisplayName= "App Catálogo Backend"
if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el servicio. Ejecuta PowerShell como administrador." }
& sc.exe description $ServiceName "Servidor local y sincronización offline-first de App Catálogo"
& sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/15000/restart/60000
& sc.exe start $ServiceName
Write-Output "Servicio $ServiceName instalado e iniciado."
