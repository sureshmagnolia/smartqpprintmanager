import os, sys
sys.stdout.reconfigure(encoding='utf-8')
from pathlib import Path

app_dir = Path(r"D:\smartqpprintmanager\output\Smart QP Print Manager")
print("==================================================================")
print("ANALYSIS OF APP IMAGE: D:\\smartqpprintmanager\\output\\Smart QP Print Manager")
print("==================================================================")

if not app_dir.exists():
    print("Directory does not exist!")
else:
    total_size = 0
    items = []
    for item in sorted(app_dir.iterdir()):
        if item.is_dir():
            dir_size = sum(f.stat().st_size for f in item.rglob('*') if f.is_file())
            total_size += dir_size
            items.append((item.name, "DIR", dir_size / (1024*1024)))
        else:
            f_size = item.stat().st_size
            total_size += f_size
            items.append((item.name, "FILE", f_size / (1024*1024)))

    for name, kind, size_mb in sorted(items, key=lambda x: x[2], reverse=True):
        print(f"{name:<35} [{kind:<4}] : {size_mb:>8.2f} MB")
    print("-" * 66)
    print(f"{'TOTAL APP IMAGE SIZE':<42} : {total_size / (1024*1024):>8.2f} MB")

# Deep dive into bin/ and runtime/ and app/
print("\n" + "=" * 66)
print("SUBDIRECTORY BREAKDOWN")
print("=" * 66)

for sub in ["app", "bin", "runtime"]:
    sub_path = app_dir / sub
    if sub_path.exists():
        sub_size = sum(f.stat().st_size for f in sub_path.rglob('*') if f.is_file())
        print(f"\n📁 {sub}/ ({sub_size / (1024*1024):.2f} MB):")
        for item in sorted(sub_path.iterdir()):
            if item.is_dir():
                s = sum(f.stat().st_size for f in item.rglob('*') if f.is_file())
                print(f"   📂 {item.name:<30} : {s / (1024*1024):>7.2f} MB")
            else:
                s = item.stat().st_size
                print(f"   📄 {item.name:<30} : {s / (1024*1024):>7.2f} MB")
