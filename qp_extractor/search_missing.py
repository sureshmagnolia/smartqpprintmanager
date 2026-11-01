import os

downloads = r"C:\Users\sures\Downloads"
mus_files = []
fin_files = []
all_pdfs = 0

for root, dirs, files in os.walk(downloads):
    for f in files:
        if f.lower().endswith(".pdf"):
            all_pdfs += 1
            fl = f.lower()
            if any(k in fl for k in ['mus', 'krithi', 'samudaya']):
                mus_files.append(os.path.join(root, f))
            if any(k in fl for k in ['financial', 'market', '152088', '152089']):
                fin_files.append(os.path.join(root, f))

print(f"Total PDFs in Downloads: {all_pdfs}")
print(f"Music files found: {len(mus_files)}")
for f in mus_files:
    print(" -", f)
print(f"Financial files found: {len(fin_files)}")
for f in fin_files:
    print(" -", f)

