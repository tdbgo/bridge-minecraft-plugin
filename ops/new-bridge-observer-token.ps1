# Part of Bridge; see ../LICENSE.md.
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string[]]$ReadIdentity = @(),

    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$target = [System.IO.Path]::GetFullPath($OutputPath)
$directory = [System.IO.Path]::GetDirectoryName($target)

if ([System.IO.File]::Exists($target) -and -not $Force) {
    throw "Observer token file already exists. Use -Force only during a planned rotation."
}
if (-not $PSCmdlet.ShouldProcess($target, 'Create a Bridge observer token file with restricted ACLs')) {
    return
}

[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$randomBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($randomBytes)
$random.Dispose()
$token = [Convert]::ToBase64String($randomBytes)
$temporary = Join-Path $directory ('.metrics.token.' + [Guid]::NewGuid().ToString('N') + '.tmp')
$backup = Join-Path $directory ('.metrics.token.' + [Guid]::NewGuid().ToString('N') + '.bak')

try {
    [System.IO.File]::WriteAllText($temporary, $token, [System.Text.UTF8Encoding]::new($false))

    $acl = [System.Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $fullControl = [System.Security.AccessControl.FileSystemRights]::FullControl
    $read = [System.Security.AccessControl.FileSystemRights]::Read
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    $currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

    foreach ($identity in @($currentIdentity, 'NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
        $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity, $fullControl, $allow))
    }
    foreach ($identity in $ReadIdentity) {
        if (-not [string]::IsNullOrWhiteSpace($identity)) {
            $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity, $read, $allow))
        }
    }
    [System.IO.File]::SetAccessControl($temporary, $acl)
    if ([System.IO.File]::Exists($target)) {
        [System.IO.File]::Replace($temporary, $target, $backup)
        [System.IO.File]::Delete($backup)
    } else {
        [System.IO.File]::Move($temporary, $target)
    }
} finally {
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    $token = $null
    if ([System.IO.File]::Exists($temporary)) {
        [System.IO.File]::Delete($temporary)
    }
    if ([System.IO.File]::Exists($backup)) {
        [System.IO.File]::Delete($backup)
    }
}

Write-Output "Created Bridge observer token file at $target. Token contents were not printed."
