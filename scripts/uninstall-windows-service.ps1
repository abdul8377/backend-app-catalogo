param(
    [string]$ServiceName = "AppCatalogoBackend",
    [ValidateRange(1, 65535)][int]$Port = 8081
)

$ErrorActionPreference = "Stop"
$firewallRuleNames = @(
    "App Catálogo Backend - TCP $Port entrada",
    "App Catálogo Backend - mDNS UDP 5353 entrada",
    "App Catálogo Backend - mDNS UDP 5353 salida"
)

& sc.exe query $ServiceName | Out-Null
if ($LASTEXITCODE -eq 0) {
    & sc.exe stop $ServiceName | Out-Null
    & sc.exe delete $ServiceName | Out-Null
}

foreach ($name in $firewallRuleNames) {
    Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}

Write-Output "Servicio $ServiceName y reglas privadas de App Catálogo retirados."
