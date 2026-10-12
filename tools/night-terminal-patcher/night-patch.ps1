[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('list', 'patch', 'restore')]
    [string]$Command,

    [Parameter(Position = 1)]
    [string]$Image,

    [Parameter(Position = 2)]
    [string]$Kmi,

    [Parameter(Position = 3)]
    [string]$Output
)

$ErrorActionPreference = 'Stop'
$SupportedKmis = @(
    'android12-5.10', 'android13-5.10', 'android13-5.15', 'android14-5.15',
    'android14-6.1', 'android15-6.6', 'android16-6.12', 'android17-6.18'
)
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Assert-AssetHash([string]$RelativePath) {
    $manifest = Join-Path $Root 'SHA256.txt'
    $asset = Join-Path $Root ($RelativePath -replace '/', '\\')
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "缺少完整性清单：$manifest" }
    if (-not (Test-Path -LiteralPath $asset -PathType Leaf)) { throw "工具包文件缺失：$asset" }
    $expected = $null
    foreach ($line in Get-Content -LiteralPath $manifest) {
        if ($line -match '^([0-9a-fA-F]{64})\s+(.+)$' -and (($Matches[2].TrimStart('*') -replace '\\', '/') -eq $RelativePath)) {
            $expected = $Matches[1].ToLowerInvariant()
            break
        }
    }
    if (-not $expected) { throw "SHA-256 清单没有此文件：$RelativePath" }
    $actual = (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "SHA-256 校验失败：$RelativePath" }
}

if ($Command -eq 'list') {
    $SupportedKmis
    exit 0
}

if (-not $Image) { throw '必须指定 boot.img 或 init_boot.img。' }
$Image = [IO.Path]::GetFullPath($Image)
if (-not (Test-Path -LiteralPath $Image -PathType Leaf)) { throw "镜像不存在：$Image" }

$stream = [IO.File]::OpenRead($Image)
try {
    $header = New-Object byte[] 8
    if ($stream.Read($header, 0, 8) -ne 8 -or [Text.Encoding]::ASCII.GetString($header) -ne 'ANDROID!') {
        throw '不是未压缩的 Android boot/init_boot 镜像（缺少 ANDROID! 文件头）。'
    }
} finally {
    $stream.Dispose()
}

$directory = Split-Path -Parent $Image
$stem = [IO.Path]::GetFileNameWithoutExtension($Image)
$engine = Join-Path $Root 'bin\windows\ksud.exe'
if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) { throw "缺少修补引擎：$engine" }
Assert-AssetHash 'bin/windows/ksud.exe'

if ($Command -eq 'patch') {
    if ($SupportedKmis -notcontains $Kmi) { throw "不支持的 KMI：$Kmi。请运行 list 查看支持列表。" }
    $module = Join-Path $Root "kmi\${Kmi}_kernelsu.ko"
    if (-not (Test-Path -LiteralPath $module -PathType Leaf)) { throw "缺少 KMI：$module" }
    Assert-AssetHash "kmi/${Kmi}_kernelsu.ko"
    if (-not $Output) { $Output = Join-Path $directory "${stem}_Night_patched.img" }
} else {
    if (-not $Output) { $Output = Join-Path $directory "${stem}_Night_restored.img" }
}

$Output = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $Output) { throw "输出文件已存在，拒绝覆盖：$Output" }
$outputDirectory = Split-Path -Parent $Output
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

Write-Host "输入：$Image"
Write-Host "输入 SHA-256：$((Get-FileHash -LiteralPath $Image -Algorithm SHA256).Hash.ToLowerInvariant())"
if ($Command -eq 'patch') {
    Write-Host "KMI：$Kmi"
    & $engine boot-patch --boot $Image --module $module --kmi $Kmi --out $outputDirectory --out-name ([IO.Path]::GetFileName($Output))
} else {
    & $engine boot-restore --boot $Image --out $outputDirectory --out-name ([IO.Path]::GetFileName($Output))
}
if ($LASTEXITCODE -ne 0) { throw "修补引擎退出代码：$LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $Output -PathType Leaf)) { throw '修补引擎未生成输出镜像。' }

$report = @(
    'Night Terminal Patcher v3'
    "Operation: $Command"
    "Input: $Image"
    "Input SHA-256: $((Get-FileHash -LiteralPath $Image -Algorithm SHA256).Hash.ToLowerInvariant())"
)
if ($Command -eq 'patch') { $report += "KMI: $Kmi" }
$report += "Output: $Output"
$report += "Output SHA-256: $((Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash.ToLowerInvariant())"
$report | Set-Content -LiteralPath "$Output.SHA256.txt" -Encoding UTF8
Write-Host "完成：$Output"
Write-Host "校验报告：$Output.SHA256.txt"
