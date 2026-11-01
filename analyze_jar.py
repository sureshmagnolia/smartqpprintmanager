import zipfile
from pathlib import Path
from collections import defaultdict

jar_path = Path(r"D:\smartqpprintmanager\target\java-print-manager-7.18.jar")
if not jar_path.exists():
    print("Jar not found!")
else:
    print(f"Inspecting JAR: {jar_path.name} ({jar_path.stat().st_size / (1024*1024):.2f} MB)")
    pkg_sizes = defaultdict(int)
    total = 0
    with zipfile.ZipFile(jar_path, 'r') as z:
        for info in z.infolist():
            pkg = info.filename.split('/')[0] if '/' in info.filename else info.filename
            pkg_sizes[pkg] += info.file_size
            total += info.file_size

    print("-" * 55)
    for pkg, sz in sorted(pkg_sizes.items(), key=lambda x: x[1], reverse=True)[:20]:
        print(f"  {pkg:<35} : {sz / (1024*1024):>6.2f} MB")
    print("-" * 55)
    print(f"  {'Total Uncompressed':<35} : {total / (1024*1024):>6.2f} MB")

