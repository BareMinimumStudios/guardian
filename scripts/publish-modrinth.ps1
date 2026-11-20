[CmdletBinding()]
param(
    [string]$Release = '0.4.0-alpha.101',
    [ValidateSet('all','1.21.1','1.21.4','1.21.5','1.21.6','1.21.7','1.21.8','1.21.9','1.21.10','1.21.11','26.1.2','26.2')]
    [string]$Minecraft = 'all',
    [ValidateSet('all','fabric','neoforge')][string]$Loader = 'all',
    [string]$Ref = 'main',
    [string]$ProjectId = '',
    [switch]$Upload
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'Install GitHub CLI and sign in, or run Modrinth upload from GitHub Actions.'
}
if ($Release -notmatch '^\d+\.\d+\.\d+-alpha\.\d+$') { throw 'Expected an alpha release number.' }
$dryRun = if ($Upload) { 'false' } else { 'true' }
$publishArguments = @('workflow','run','modrinth.yml','--repo','BareMinimumStudios/guardian','--ref',$Ref,
    '-f',"release=$Release",'-f',"minecraft=$Minecraft",'-f',"loader=$Loader",'-f',"dry_run=$dryRun")
if ($ProjectId) { $publishArguments += @('-f',"project_id=$ProjectId") }
& gh @publishArguments
if ($LASTEXITCODE -ne 0) { throw 'GitHub did not accept the workflow dispatch.' }
Write-Host "Dispatched Modrinth upload (dry run: $dryRun). See https://github.com/BareMinimumStudios/guardian/actions/workflows/modrinth.yml"
