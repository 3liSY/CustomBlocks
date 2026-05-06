$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'C:/Users/66664/OneDrive/Desktop/Coding/CustomBlockss'
& ./gradlew.bat compileJava --console=plain
