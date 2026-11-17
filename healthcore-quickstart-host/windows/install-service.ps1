# =============================================================================
# Opes HealthCore SDK — Windows Server Service Installer
# Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
# Website            : https://www.opeshealthsystems.com | Douala, Cameroon
# =============================================================================
param(
    [string]$WinSwExe = ".\winsw-x64.exe",
    [string]$DbUrl = "jdbc:sqlserver://localhost:1433;databaseName=healthcore;encrypt=true;trustServerCertificate=true"
)

Write-Host "Installing Opes HealthCore Windows Service (Java 25 + MSSQL)..." -ForegroundColor Cyan
[System.Environment]::SetEnvironmentVariable("HEALTHCORE_DB_URL", $DbUrl, "Machine")
& $WinSwExe install healthcore-service.xml
& $WinSwExe start healthcore-service.xml
Write-Host "Opes HealthCore Service is running on port 3000." -ForegroundColor Green
