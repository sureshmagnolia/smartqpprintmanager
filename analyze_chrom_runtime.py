import os, sys
sys.stdout.reconfigure(encoding='utf-8')
from pathlib import Path

app_dir = Path(r"D:\smartqpprintmanager\output\Smart QP Print Manager")

print("=== CHROMIUM DIRECTORY CONTENTS ===")
chrom_dir = app_dir / "bin" / "chromium"
if chrom_dir.exists():
    chrom_items = []
    for f in chrom_dir.iterdir():
        if f.is_dir():
            s = sum(x.stat().st_size for x in f.rglob('*') if x.is_file())
            chrom_items.append((f.name, "DIR", s / (1024*1024)))
        else:
            s = f.stat().st_size
            chrom_items.append((f.name, "FILE", s / (1024*1024)))
    for name, kind, mb in sorted(chrom_items, key=lambda x: x[2], reverse=True):
        print(f"  {name:<35} [{kind:<4}] : {mb:>7.2f} MB")

print("\n=== RUNTIME DIRECTORY CONTENTS ===")
rt_dir = app_dir / "runtime"
if rt_dir.exists():
    modules_file = rt_dir / "lib" / "modules"
    if modules_file.exists():
        print(f"  lib/modules size: {modules_file.stat().st_size / (1024*1024):.2f} MB")
    
    # Check java version in release file
    rel_file = rt_dir / "release"
    if rel_file.exists():
        with open(rel_file, encoding='utf-8') as f:
            print("  Release file content:")
            for l in f:
                if any(k in l for k in ["JAVA_VERSION", "MODULES", "OS_NAME"]):
                    print(f"    {l.strip()}")

