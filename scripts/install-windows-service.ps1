param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$ServiceName = "AppCatalogoBackend",
    [string]$JavaExecutable = "java.exe",
    [ValidateRange(1, 65535)][int]$Port = 8081
)

$ErrorActionPreference = "Stop"
$resolvedJar = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) { throw "No existe el JAR indicado." }

$firewallRules = @(
    @{ Name = "App Catálogo Backend - TCP $Port entrada"; Direction = "Inbound"; Protocol = "TCP"; Port = $Port },
    @{ Name = "App Catálogo Backend - mDNS UDP 5353 entrada"; Direction = "Inbound"; Protocol = "UDP"; Port = 5353 },
    @{ Name = "App Catálogo Backend - mDNS UDP 5353 salida"; Direction = "Outbound"; Protocol = "UDP"; Port = 5353 }
)

function Remove-AppCatalogoFirewallRules {
    foreach ($rule in $firewallRules) {
        Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    }
}

$serviceCreated = $false
try {
    $binaryPath = '"' + $JavaExecutable + '" -jar "' + $resolvedJar + '" --server.port=' + $Port + ' --app.server.public-port=' + $Port
    & sc.exe create $ServiceName binPath= $binaryPath start= auto DisplayName= "App Catálogo Backend"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el servicio. Ejecuta PowerShell como administrador." }
    $serviceCreated = $true
    & sc.exe description $ServiceName "Servidor local y sincronización offline-first de App Catálogo"
    & sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/15000/restart/60000

    Remove-AppCatalogoFirewallRules
    foreach ($rule in $firewallRules) {
        $parameters = @{
            DisplayName = $rule.Name
            Group = "App Catálogo Backend"
            Profile = "Private"
            Direction = $rule.Direction
            Action = "Allow"
            Protocol = $rule.Protocol
        }
        if ($rule.Direction -eq "Outbound") { $parameters.RemotePort = $rule.Port }
        else { $parameters.LocalPort = $rule.Port }
        New-NetFirewallRule @parameters | Out-Null
    }

    & sc.exe start $ServiceName
    if ($LASTEXITCODE -ne 0) { throw "El servicio fue creado, pero no pudo iniciarse." }
    Write-Output "Servicio $ServiceName instalado en TCP $Port; reglas privadas TCP $Port y UDP 5353 aplicadas."
} catch {
    Remove-AppCatalogoFirewallRules
    if ($serviceCreated) { & sc.exe delete $ServiceName | Out-Null }
    throw
}
