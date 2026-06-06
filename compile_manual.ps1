$cp = (Get-Content cp.txt -Raw).Trim()
$files = Get-Content java_files.txt
$filesStr = $files -join " "
$cmd = "javac -cp `"$cp`" -d target\classes $filesStr"
Invoke-Expression $cmd
Copy-Item -Path "src\main\resources\*" -Destination "target\classes\" -Recurse -Force
