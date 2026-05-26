$files = Get-ChildItem -Path "src\main\java" -Recurse -Filter "*.java"
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    
    # Replace SoundEvents.SOMETHING with SoundEvents.SOMETHING.value()
    # Negative lookahead (?!\.value\(\)) ensures we don't double append
    $newContent = [regex]::Replace($content, "(SoundEvents\.[A-Z0-9_]+)(?!\.value\(\))", "`$1.value()")
    
    if ($content -cne $newContent) {
        [System.IO.File]::WriteAllText($file.FullName, $newContent, [System.Text.Encoding]::UTF8)
        Write-Host "Fixed playSound in $($file.Name)"
    }
}

$langFiles = Get-ChildItem -Path "src\main\resources\assets\customblocks\lang" -Filter "*.json"
foreach ($file in $langFiles) {
    $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $newContent = $content -replace 'Â§', '§' -replace 'â€”', '—' -replace 'âœ”', '✔' -replace 'â†’', '→' -replace 'âœ˜', '✘' -replace 'â€¦', '…' -replace 'â€œ', '“' -replace 'â€', '”'
    
    if ($content -cne $newContent) {
        [System.IO.File]::WriteAllText($file.FullName, $newContent, [System.Text.Encoding]::UTF8)
        Write-Host "Fixed mojibake in $($file.Name)"
    }
}
Write-Host "Done!"
