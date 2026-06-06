$cp = (Get-Content cp.txt -Raw).Trim()
$env:CLASSPATH = ".;target\classes;$cp"
java com.printmanager.Launcher
