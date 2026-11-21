# 槲寄生 1.21.1 离线构建脚本（javac + jar，不依赖 Gradle/MDG）
#
# 背景：本机 %USERPROFILE%\.gradle 缓存已不在，maven.neoforged.net 不可达，
# gradlew 无法工作；但 envenom 工程留存的 21.1.1 编译基座可用：
#   E:\dsh\envenom\libs-1.21.1\ 里是 NeoForge 21.1.248 merged + 21.1 服务端库
# 再加上 libs\SlashBladeResharped-2.2.1-1.21.1.jar 即可编译本模组。
#
# 用法：
#   .\build-1.21.1.ps1            # 增量编译 → build-1.21.1\libs\mistletoe-<version>.jar
#   .\build-1.21.1.ps1 -Clean
#   .\build-1.21.1.ps1 -Verify

param(
    [switch]$Clean,
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'

$JdkHome = 'E:\JDK21'
if (-not (Test-Path -LiteralPath $JdkHome)) { throw "找不到 JDK：$JdkHome" }
$Javac   = Join-Path $JdkHome 'bin\javac.exe'
$JarTool = Join-Path $JdkHome 'bin\jar.exe'

$root    = $PSScriptRoot
$libsBase = 'E:\dsh\envenom\libs-1.21.1'
$slashBladeJar = Join-Path $root 'libs\SlashBladeResharped-2.2.1-1.21.1.jar'
$build   = Join-Path $root 'build-1.21.1'
$classes = Join-Path $build 'classes'
$outDir  = Join-Path $build 'libs'
$srcJava = Join-Path $root 'src\main\java'
$srcRes  = Join-Path $root 'src\main\resources'
$tomlSrc = Join-Path $srcRes 'META-INF\neoforge.mods.toml'

if (-not (Test-Path -LiteralPath $libsBase)) { throw "找不到编译基座：$libsBase" }
if (-not (Test-Path -LiteralPath $slashBladeJar))  { throw "找不到 SlashBlade jar：$slashBladeJar" }

$props = @{}
Get-Content (Join-Path $root 'gradle.properties') | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$') { $props[$matches[1]] = $matches[2] }
}
$modId      = $props['mod_id']
$modVersion = $props['mod_version']
# 产物文件名用 mod_slug（模组改名后 jar 名跟着变，mod_id 必须保持不变以免存档/数据包失联）
$modSlug    = $props['mod_slug']
if (-not $modSlug) { $modSlug = $modId }
$jarPath    = Join-Path $outDir "$modSlug-$modVersion.jar"

if ($Clean -and (Test-Path $build)) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force $classes, $outDir | Out-Null

$libJars = @(Get-ChildItem $libsBase -Filter '*.jar' -File) + @(Get-Item -LiteralPath $slashBladeJar)
# ModInfo#getVersion() 返回 org.apache.maven.artifact.versioning.ArtifactVersion；
# 运行期由 FML 的 maven-artifact 提供，但离线编译基座里没有它，这里从 PCL 的 libraries 补一个（找不到就跳过）。
$mavenArtifact = Get-ChildItem 'D:\PCL 正式版 2.10.6\.minecraft\libraries\org\apache\maven\maven-artifact' -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($mavenArtifact) { $libJars += $mavenArtifact }
# PoseStack/Matrix4f 需要 joml；编译基座里没有，从 PCL libraries 补。
$joml = Get-ChildItem 'D:\PCL 正式版 2.10.6\.minecraft\libraries\org\joml' -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($joml) { $libJars += $joml }
$cp = ($libJars | ForEach-Object FullName) -join ';'
Write-Host "[build] classpath jars = $($libJars.Count)" -ForegroundColor Cyan

$sources = Get-ChildItem $srcJava -Recurse -Filter '*.java' -File | ForEach-Object FullName
Write-Host "[build] sources = $($sources.Count)" -ForegroundColor Cyan

# ── 1) javac（Java 21）───────────────────────────────────────────────
& $Javac --release 21 -encoding UTF-8 -proc:none -Xlint:all -cp $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败，退出码 $LASTEXITCODE" }

# ── 2) 资源 ──────────────────────────────────────────────────────────
# 先清掉上一次复制的资源目录：否则删掉/改名的资源（例如被并入主动画文件的 motion 文件）
# 会留在 $classes 里，最后原样打进 jar。
foreach ($stale in 'assets', 'data', 'META-INF', 'pack.mcmeta', 'mistletoe.mixins.json') {
    $target = Join-Path $classes $stale
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
}
Copy-Item (Join-Path $srcRes '*') $classes -Recurse -Force

# ── 3) 展开 neoforge.mods.toml ───────────────────────────────────────
$toml = Get-Content $tomlSrc -Raw -Encoding UTF8
foreach ($key in $props.Keys) { $toml = $toml.Replace('${' + $key + '}', $props[$key]) }
if ($toml -match '\$\{') { throw "mods.toml 里还有未提供的占位符：$($matches[0])" }
Set-Content (Join-Path $classes 'META-INF\neoforge.mods.toml') $toml -Encoding UTF8

# ── 4) MANIFEST ──────────────────────────────────────────────────────
$manifest = Join-Path $build 'MANIFEST.MF'
@(
    'Manifest-Version: 1.0'
    'Specification-Title: Mistletoe Lumens'
    'Specification-Vendor: Xingguang'
    'Specification-Version: 1'
    'Implementation-Title: Mistletoe Lumens'
    "Implementation-Version: $modVersion"
    'Implementation-Vendor: Xingguang'
    ''
) | Set-Content $manifest -Encoding ASCII

# ── 5) jar（生产包排除 gametest：测试用取消事件监听器不随包发布）──────
$stage = Join-Path $build 'jar-staging'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
Copy-Item $classes $stage -Recurse -Force
$testPackage = Join-Path $stage 'dev\xingguang\terminalblade\gametest'
if (Test-Path $testPackage) { Remove-Item $testPackage -Recurse -Force }
& $JarTool --create --file $jarPath --manifest $manifest -C $stage .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败，退出码 $LASTEXITCODE" }

Write-Host ""
Write-Host "[build] 产物：$jarPath" -ForegroundColor Green
Write-Host "[build] 大小：$((Get-Item $jarPath).Length) 字节" -ForegroundColor Green

# ── 6) 自检 ──────────────────────────────────────────────────────────
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $names = $zip.Entries | ForEach-Object FullName
    foreach ($required in 'META-INF/neoforge.mods.toml', 'META-INF/MANIFEST.MF', 'mistletoe.mixins.json') {
        if (-not ($names -contains $required)) { throw "jar 缺少 $required" }
    }
    $classCount = ($zip.Entries | Where-Object { $_.FullName -like '*.class' }).Count
    Write-Host "[verify] classes = $classCount, entries = $($zip.Entries.Count)"
} finally { $zip.Dispose() }

if ($Verify) {
    $tmp = Join-Path $build 'verify'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force $tmp | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $e = $zip.Entries | Where-Object { $_.FullName -eq 'dev/xingguang/terminalblade/combat/MistletoeCombat.class' } | Select-Object -First 1
        if (-not $e) { throw "缺少 MistletoeCombat.class" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $tmp 'MistletoeCombat.class'), $true)
    } finally { $zip.Dispose() }
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $tmp 'MistletoeCombat.class'))
    $major = [int]$bytes[6] * 256 + [int]$bytes[7]
    Write-Host "[verify] class major version = $major (1.21.1 期望 65)"
    if ($major -ne 65) { throw "class 版本不是 Java 21" }
    Write-Host "[verify] OK" -ForegroundColor Green
}
