$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $projectRoot 'backend/functions/sheets-sync'
$destinationDir = Join-Path $projectRoot 'supabase/functions/sheets-sync'
New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceDir 'index.ts'), (Join-Path $sourceDir 'sync-core.mjs') -Destination $destinationDir -Force
Write-Host 'Prepared the standard Supabase function directory. No cloud deployment was performed.'
