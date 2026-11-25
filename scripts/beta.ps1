# One Keyd beta on Windows, the same flow as beta.sh: bump, test, build, sign, then commit and tag. Nothing is pushed.
#
#   scripts\beta.ps1              the next beta of the version being worked on
#   scripts\beta.ps1 -Version 0.5.0   the first beta of 0.5.0
#
# It asks for the keystore password here, hidden, so it is never in the command line, the history or a file. The
# keystore is ~\folio-release.jks, alias "folio" (set FOLIO_RELEASE_STORE_FILE or FOLIO_RELEASE_KEY_ALIAS to use
# others). The build uses Git's own bash: plain "bash" in PowerShell is WSL's, which can't see these folders. The phone
# is not touched unless -Install is given.
param(
    [string]$Version = '',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw "Git for Windows' bash was not found at $bash." }
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    # JDK 17, the one CI builds with.
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 17.' }
    $env:JAVA_HOME = $jdk.FullName
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:FOLIO_RELEASE_STORE_FILE) { $env:FOLIO_RELEASE_STORE_FILE = Join-Path $HOME 'folio-release.jks' }
if (-not $env:FOLIO_RELEASE_KEY_ALIAS) { $env:FOLIO_RELEASE_KEY_ALIAS = 'folio' }
if (-not $Install) { $env:KEYD_BETA_NO_INSTALL = '1' }

$secure = Read-Host 'Keystore password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:FOLIO_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:FOLIO_RELEASE_KEY_PASSWORD = $env:FOLIO_RELEASE_STORE_PASSWORD
    $arguments = @('scripts/beta.sh')
    if ($Version) { $arguments += $Version }
    & $bash @arguments
    if ($LASTEXITCODE -ne 0) { throw "beta.sh stopped with exit code $LASTEXITCODE." }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Item Env:FOLIO_RELEASE_STORE_PASSWORD, Env:FOLIO_RELEASE_KEY_PASSWORD, Env:KEYD_BETA_NO_INSTALL -ErrorAction SilentlyContinue
}
