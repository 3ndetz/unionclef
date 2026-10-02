param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$DocsDirectory = 'docs',
    [string]$Repository = '3ndetz/unionclef',
    [string]$Branch = 'main'
)

$ErrorActionPreference = 'Stop'
$sourcePath = [IO.Path]::GetFullPath($SourceRoot)
$docsPath = [IO.Path]::GetFullPath((Join-Path $sourcePath $DocsDirectory))
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $docsPath -PathType Container)) {
    throw "Documentation directory does not exist: $docsPath"
}
if ($outputPath -eq $sourcePath -or $outputPath -eq $docsPath -or
        $outputPath.StartsWith($docsPath + [IO.Path]::DirectorySeparatorChar)) {
    throw 'Output must be separate from the source documentation.'
}

# Page identity follows the relative file path, never its first heading.
# Archived progress files intentionally have the same heading as the live file.
$files = @(Get-ChildItem -LiteralPath $docsPath -Recurse -File -Filter '*.md' | Sort-Object FullName)
$sourceToPage = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
$pageToSource = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($file in $files) {
    $relative = [IO.Path]::GetRelativePath($docsPath, $file.FullName).Replace('\', '/')
    $page = $relative.Replace('/', '__')
    if ($relative -ieq 'README.md') { $page = 'Home.md' }
    if ($relative -ieq '_sidebar.md') { $page = '_Sidebar.md' }
    if ($relative -ieq '_footer.md') { $page = '_Footer.md' }
    if ($pageToSource.ContainsKey($page)) {
        throw "Wiki page collision: $relative and $($pageToSource[$page]) -> $page"
    }
    $pageToSource.Add($page, $relative)
    $sourceToPage.Add($file.FullName, $page)
}

function Convert-DocLink([string]$link, [string]$sourceFile) {
    if ($link.StartsWith('#') -or $link -match '^[A-Za-z][A-Za-z0-9+.-]*:' -or $link.StartsWith('//')) {
        return $link
    }
    # Preserve any title following a Markdown link target.
    $title = ''
    if ($link -match '^(.*?)\s+("[^"]*"|''[^'']*'')$') {
        $link = $Matches[1]
        $title = ' ' + $Matches[2]
    }
    $link = $link.Trim('<', '>')
    $fragment = ''
    $hashAt = $link.IndexOf('#')
    if ($hashAt -ge 0) {
        $fragment = $link.Substring($hashAt)
        $link = $link.Substring(0, $hashAt)
    }
    $target = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $sourceFile) ([Uri]::UnescapeDataString($link))))
    if ($sourceToPage.ContainsKey($target)) {
        $page = [IO.Path]::GetFileNameWithoutExtension($sourceToPage[$target])
        return [Uri]::EscapeDataString($page) + $fragment + $title
    }
    $relative = [IO.Path]::GetRelativePath($sourcePath, $target).Replace('\', '/')
    if ($relative -eq '..' -or $relative.StartsWith('../')) {
        throw "Link outside repository in $sourceFile : $link"
    }
    $escaped = ($relative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
    return "https://github.com/$Repository/blob/$Branch/$escaped$fragment$title"
}

New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
foreach ($file in $files) {
    $page = $sourceToPage[$file.FullName]
    $relative = [IO.Path]::GetRelativePath($sourcePath, $file.FullName).Replace('\', '/')
    $lines = [Collections.Generic.List[string]]::new()
    if ($page -notin @('_Sidebar.md', '_Footer.md')) {
        $escaped = ($relative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
        $url = "https://github.com/$Repository/blob/$Branch/$escaped"
        $lines.Add("> Source: [$relative]($url)")
        $lines.Add('')
    }
    $fence = $null
    foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
        if ($line -match '^\s{0,3}(`{3,}|~{3,})') {
            $marker = $Matches[1]
            if ($null -eq $fence) { $fence = $marker }
            elseif ($marker[0] -eq $fence[0] -and $marker.Length -ge $fence.Length) { $fence = $null }
            $lines.Add($line)
            continue
        }
        if ($null -ne $fence) { $lines.Add($line); continue }
        # As in the previous exporter, rewrite ordinary inline Markdown links.
        # Leave code spans alone, including literal link syntax in documentation.
        $parts = [regex]::Split($line, '(`+[^`]*`+)')
        for ($i = 0; $i -lt $parts.Length; $i += 2) {
            $parts[$i] = [regex]::Replace($parts[$i], '\[([^\]]+)\]\(([^\)]+)\)', {
                param($match)
                $converted = Convert-DocLink $match.Groups[2].Value $file.FullName
                return '[' + $match.Groups[1].Value + '](' + $converted + ')'
            })
        }
        $lines.Add($parts -join '')
    }
    [IO.File]::WriteAllLines((Join-Path $outputPath $page), $lines, [Text.UTF8Encoding]::new($false))
}
Write-Output "Exported $($files.Count) documentation pages with unique file-based names."
