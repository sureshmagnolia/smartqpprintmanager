$cp = Get-Content cp.txt
$sources = Get-ChildItem -Recurse src/main/java/*.java | ForEach-Object { $_.FullName }
$final = @()
$final += "-cp"
$final += ".;target/classes;$cp"
$final += "-d"
$final += "target/classes"
foreach ($s in $sources) {
    $final += $s
}
$final | Out-File -Encoding ASCII compile_argfile
