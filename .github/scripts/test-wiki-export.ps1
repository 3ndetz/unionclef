$ErrorActionPreference = 'Stop'
$exporter = Join-Path $PSScriptRoot 'export-wiki.ps1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ('unionclef-wiki-export-test-' + [Guid]::NewGuid().ToString('N'))
function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}
function Checked-Git {
    & git @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $LASTEXITCODE" }
}
try {
    $source = Join-Path $fixture 'source'
    $pages = Join-Path $fixture 'pages'
    $wiki = Join-Path $fixture 'wiki'
    New-Item -ItemType Directory -Path (Join-Path $source 'docs/ai/archive') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $source 'README.md') -Value '# Repository'
    Set-Content -LiteralPath (Join-Path $source 'docs/README.md') -Value @'
# Wiki
[Progress](ai/progress.md#plan)
[Repository](../README.md)
'@
    Set-Content -LiteralPath (Join-Path $source 'docs/ai/progress.md') -Value @'
# Progress
[Home](../README.md)
[Archive](archive/old.md#investigate)
[Local anchor](#plan)
[External](https://example.com)
`[Literal](archive/old.md)`
```markdown
[Literal](archive/old.md)
```
'@
    Set-Content -LiteralPath (Join-Path $source 'docs/ai/archive/old.md') -Value '# Progress'
    Set-Content -LiteralPath (Join-Path $source 'docs/----.md') -Value '# A leading dash'
    & $exporter -SourceRoot $source -OutputDirectory $pages
    Assert ((Get-ChildItem -LiteralPath $pages -File).Count -eq 4) 'Duplicate headings lost a page.'
    $progress = Get-Content -LiteralPath (Join-Path $pages 'ai__progress.md') -Raw
    $homePageContent = Get-Content -LiteralPath (Join-Path $pages 'Home.md') -Raw
    Assert ($progress.Contains('[Home](Home)')) 'Root README link does not target Home.'
    Assert ($progress.Contains('[Archive](ai__archive__old#investigate)')) 'Archive path/fragment lost.'
    Assert ($homePageContent.Contains('[Progress](ai__progress#plan)')) 'Nested page link was not converted.'
    Assert ($homePageContent.Contains('/blob/main/README.md')) 'Outside-docs link did not target source.'
    Assert ($progress.Contains('/blob/main/docs/ai/progress.md')) 'Source link lost docs prefix.'
    Assert ($progress.Contains('[Local anchor](#plan)')) 'Page-local anchor changed.'
    Assert ($progress.Contains('[External](https://example.com)')) 'External URL changed.'
    Assert ($progress.Contains('`[Literal](archive/old.md)`')) 'Inline code was rewritten.'
    Assert ([regex]::IsMatch($progress, '(?m)^```markdown\r?\n\[Literal\]\(archive/old.md\)')) 'Fenced code was rewritten.'
    Assert (Test-Path -LiteralPath (Join-Path $pages '----.md')) 'Leading-dash page was lost.'

    New-Item -ItemType Directory -Path $wiki | Out-Null
    Checked-Git -C $wiki init --quiet
    Checked-Git -C $wiki config user.name '3ndetz'
    Checked-Git -C $wiki config user.email 'jayrawrr3@gmail.com'
    Set-Content -LiteralPath (Join-Path $wiki '----.md') -Value 'Old generated page'
    Checked-Git -C $wiki add --all
    Checked-Git -C $wiki commit --quiet -m 'test: initial wiki'
    Checked-Git -C $wiki rm -rf --ignore-unmatch -- .
    Get-ChildItem -LiteralPath $pages -File | Copy-Item -Destination $wiki
    Checked-Git -C $wiki add --all
    Checked-Git -C $wiki commit --quiet -m 'test: exported wiki'
    & $exporter -SourceRoot $source -OutputDirectory $pages
    Get-ChildItem -LiteralPath $pages -File | Copy-Item -Destination $wiki
    & git -C $wiki diff --quiet
    Assert ($LASTEXITCODE -eq 0) 'Second export was not idempotent.'

    # Fail before writing when different relative paths flatten to the same page.
    New-Item -ItemType Directory -Path (Join-Path $source 'docs/a') | Out-Null
    Set-Content -LiteralPath (Join-Path $source 'docs/a/b.md') -Value '# One'
    Set-Content -LiteralPath (Join-Path $source 'docs/a__b.md') -Value '# Two'
    $collisionCaught = $false
    try { & $exporter -SourceRoot $source -OutputDirectory (Join-Path $fixture 'bad-output') }
    catch { $collisionCaught = $_.Exception.Message.Contains('Wiki page collision:') }
    Assert $collisionCaught 'Page-name collision was not rejected.'
    Assert (-not (Test-Path -LiteralPath (Join-Path $fixture 'bad-output'))) 'Collision wrote partial output.'
    Write-Output 'PASS: repeated headings, links, code, leading-dash Git cleanup, no-op export and collision rejection.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ($resolved.StartsWith($tempRoot) -and (Split-Path -Leaf $resolved) -match '^unionclef-wiki-export-test-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    } else { throw 'Refusing fixture cleanup outside its allocated temporary directory.' }
}
