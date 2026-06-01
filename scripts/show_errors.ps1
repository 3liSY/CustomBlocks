$lines = Get-Content 'C:/Users/66664/OneDrive/Desktop/Coding/CustomBlockss/build_output.txt'
$lines | Select-String -Pattern 'error:|\.java:|Failed|symbol|cannot find|incompatible|reason' | Select-Object -First 120
