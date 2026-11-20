# Windows 构建链（与手机上那套 arm64 自制链等价，只是换成 x86_64 官方产物）：
#   aapt2 compile/link（资源） -> javac -> d8 -> pack.py（4 字节对齐打包） -> zipalign -> apksigner
#
# 必须从 X: 盘（subst 映射的 ASCII 路径）运行：aapt2 处理含中文的绝对路径会失败。
param(
    [string]$Out = "apk/ChatNative-2.0.apk",
    [switch]$SkipDex
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if ($here -notmatch '^[A-Za-z]:') { throw "请从 subst 映射的盘符运行（见 build.cmd）" }

$sdk    = 'X:\_sdk'
$bt     = Join-Path $sdk 'android-14'
$jar    = Join-Path $sdk 'plat\android-34\android.jar'
$jdk    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin'
$python = 'C:\Users\CatHe\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'

New-Item -ItemType Directory -Force -Path apk, gen, compiled | Out-Null

Write-Host '[1/6] aapt2 compile 资源'
Remove-Item compiled, gen -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path compiled, gen | Out-Null
& "$bt\aapt2.exe" compile --dir res -o compiled.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败: $LASTEXITCODE" }

Write-Host '[2/6] aapt2 link + 生成 R.java'
Remove-Item apk/base.apk -ErrorAction SilentlyContinue
& "$bt\aapt2.exe" link -o apk/base.apk --manifest AndroidManifest.xml -I $jar --java gen --auto-add-overlay compiled.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败: $LASTEXITCODE" }

Write-Host '[3/6] javac 编译 Java'
Remove-Item classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path classes | Out-Null
$sources = @(Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Recurse -Path gen -Filter *.java | ForEach-Object { $_.FullName })
$argFile = Join-Path $here 'apk\sources.txt'
$sources | ForEach-Object { $_ -replace '\\', '/' } | Set-Content -Path $argFile -Encoding ASCII
& "$jdk\javac.exe" -encoding UTF-8 -nowarn -Xlint:none -source 8 -target 8 -classpath $jar -d classes "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac 失败: $LASTEXITCODE" }

Write-Host '[4/6] d8 生成 classes.dex'
if (-not $SkipDex) {
    # build-tools 34 自带的 d8(8.2.2-dev) 在解析 javac 21 产出的匿名类时会内部 NPE，
    # 换成 Maven 上的 r8 8.3.37（同样是 D8 的入口）。
    Remove-Item apk/classes.dex -ErrorAction SilentlyContinue
    $classFiles = @(Get-ChildItem -Recurse -Path classes -Filter *.class | ForEach-Object { $_.FullName })
    & "$jdk\java.exe" -cp "$sdk\r8-8.3.37.jar" com.android.tools.r8.D8 --lib $jar --min-api 26 --release --output apk @classFiles
    if ($LASTEXITCODE -ne 0) { throw "d8 失败: $LASTEXITCODE" }
}

Write-Host '[5/6] pack.py 打包 + zipalign'
& $python pack.py apk/app-unsigned.apk
if ($LASTEXITCODE -ne 0) { throw "pack.py 失败: $LASTEXITCODE" }
Remove-Item apk/aligned.apk -ErrorAction SilentlyContinue
& "$bt\zipalign.exe" -f -p 4 apk/app-unsigned.apk apk/aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败: $LASTEXITCODE" }

Write-Host '[6/6] apksigner 签名'
Remove-Item $Out -ErrorAction SilentlyContinue
& "$bt\apksigner.bat" sign --ks signing-keystore.jks --ks-pass pass:android `
    --ks-key-alias androiddebugkey --key-pass pass:android `
    --min-sdk-version 26 --out $Out apk/aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败: $LASTEXITCODE" }

Write-Host ''
Write-Host ("完成: {0}  {1:N0} KB" -f $Out, ((Get-Item $Out).Length / 1KB))
