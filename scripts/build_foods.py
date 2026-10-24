#!/usr/bin/env python3
"""Builds app/src/main/assets/foods.csv from scripts/food_sources/.

  usda.csv    foods whose numbers are copied from USDA FoodData Central (SR Legacy)
              by their exact description ("ref"), so no value is typed by hand
  label.csv   typical label values of Ukrainian products, typed by hand
  dishes.json dishes calculated from ingredients of the two lists above

Usage: build_foods.py <SR Legacy CSV zip or folder> [--report report.md]
Download: https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
"""
import csv
import io
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts/food_sources"
OUT = ROOT / "app/src/main/assets/foods.csv"
COLUMNS = ["code", "category", "name_uk", "name_en", "kcal", "protein", "fat", "carbs",
           "fiber", "alcohol", "portion_g", "portion_uk", "portion_en", "source", "ref"]
NUTRIENTS = {"1008": "kcal", "1003": "protein", "1004": "fat", "1005": "carbs", "1079": "fiber", "1018": "alcohol"}
CATEGORY_ORDER = ["grains", "bread", "veg", "fruit", "meat", "fish", "dairy", "eggs", "legumes",
                  "nuts", "fats", "sweets", "drinks", "sauces", "dishes", "fastfood"]


def read_csv(path):
    lines = [l for l in path.open(encoding="utf-8") if l.strip() and not l.startswith("#")]
    return list(csv.DictReader(lines, delimiter=";"))


def open_sr(src: Path):
    if src.suffix == ".zip":
        z = zipfile.ZipFile(src)
        names = {Path(n).name: n for n in z.namelist()}
        return lambda name: io.TextIOWrapper(z.open(names[name]), encoding="utf-8")
    files = {p.name: p for p in src.rglob("*.csv")}
    return lambda name: files[name].open(encoding="utf-8")


def code_key(r):
    m = re.match(r"([a-z]+)(\d+)", r["code"])
    cat = CATEGORY_ORDER.index(r["category"]) if r["category"] in CATEGORY_ORDER else 99
    return (cat, m.group(1), int(m.group(2))) if m else (cat, r["code"], 0)


def fmt(x, digits):
    s = f"{x:.{digits}f}"
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def main() -> int:
    opener = open_sr(Path(sys.argv[1]))
    report_path = Path(sys.argv[sys.argv.index("--report") + 1]) if "--report" in sys.argv else None

    desc = {}
    with opener("food.csv") as f:
        for r in csv.DictReader(f):
            desc[r["description"].strip().lower()] = r["fdc_id"]
    values = {}
    with opener("food_nutrient.csv") as f:
        for r in csv.DictReader(f):
            k = NUTRIENTS.get(r["nutrient_id"])
            if k:
                values.setdefault(r["fdc_id"], {})[k] = float(r["amount"] or 0)
    all_desc = list(desc)

    report = ["## Food database build", ""]
    missing = []
    out = []

    for r in read_csv(SRC / "usda.csv"):
        ref = r["ref"].strip().lower()
        fid = desc.get(ref)
        if fid is None:
            pref = [d for d in all_desc if d.startswith(ref)]
            if pref:
                chosen = min(pref, key=len)
                fid = desc[chosen]
                if chosen != ref:
                    report.append(f"- {r['code']} {r['name_en']}: matched by prefix → `{chosen}`")
        if fid is None:
            words = set(re.sub(r"[,()]", " ", ref).split()) - {"and", "or", "with", "without", "raw", "cooked"}
            cands = sorted((d for d in all_desc if words and words <= set(re.sub(r"[,()]", " ", d).split())), key=len)[:5]
            missing.append(f"- **{r['code']} {r['name_en']}**: not found `{r['ref']}`" +
                           "".join(f"\n  - candidate: `{c}`" for c in cands))
            continue
        v = values.get(fid, {})
        if "kcal" not in v:
            missing.append(f"- **{r['code']} {r['name_en']}**: no energy value for fdc {fid}")
            continue
        out.append({
            "code": r["code"], "category": r["category"], "name_uk": r["name_uk"], "name_en": r["name_en"],
            "kcal": v.get("kcal", 0), "protein": v.get("protein", 0), "fat": v.get("fat", 0),
            "carbs": v.get("carbs", 0), "fiber": v.get("fiber", 0), "alcohol": v.get("alcohol", 0),
            "portion_g": r["portion_g"], "portion_uk": r["portion_uk"], "portion_en": r["portion_en"],
            "source": "USDA", "ref": r["ref"],
        })

    for r in read_csv(SRC / "label.csv"):
        row = dict(r)
        for k in ("kcal", "protein", "fat", "carbs", "fiber", "alcohol"):
            row[k] = float(row[k] or 0)
        out.append(row)

    base = {r["code"]: r for r in out}
    dishes = json.loads((SRC / "dishes.json").read_text(encoding="utf-8"))["dishes"]
    for d in dishes:
        absent = [c for c, _ in d["items"] if c not in base]
        if absent:
            missing.append(f"- **{d['code']} {d['en']}**: dish skipped, missing ingredients {', '.join(absent)}")
            continue
        y = float(d["yield"])
        tot = {k: sum(base[c][k] * g / 100 for c, g in d["items"]) / y * 100
               for k in ("kcal", "protein", "fat", "carbs", "fiber", "alcohol")}
        p = d.get("portion") or ["", "", ""]
        out.append({
            "code": d["code"], "category": d["category"], "name_uk": d["uk"], "name_en": d["en"],
            **tot, "portion_g": str(p[0]), "portion_uk": p[1], "portion_en": p[2],
            "source": "RECIPE", "ref": "",
        })

    codes = [r["code"] for r in out]
    dups = sorted({c for c in codes if codes.count(c) > 1})
    if dups:
        missing.append("- duplicate codes: " + ", ".join(dups))

    out.sort(key=code_key)
    with OUT.open("w", encoding="utf-8") as f:
        f.write("# Tarilka food database. Values per 100 g edible part. GENERATED by scripts/build_foods.py, do not edit by hand.\n")
        f.write("# source: USDA = USDA FoodData Central (SR Legacy, public domain), copied by exact description in ref;\n")
        f.write("# LABEL = typical label values of Ukrainian products; RECIPE = calculated from ingredients (scripts/food_sources/dishes.json).\n")
        f.write(";".join(COLUMNS) + "\n")
        for r in out:
            row = dict(r)
            row["kcal"] = fmt(round(r["kcal"]), 0)
            for k in ("protein", "fat", "carbs", "fiber", "alcohol"):
                row[k] = fmt(r[k], 2)
            f.write(";".join(str(row[k]) for k in COLUMNS) + "\n")

    by_src = {s: sum(1 for r in out if r["source"] == s) for s in ("USDA", "LABEL", "RECIPE")}
    report.append(f"Written {len(out)} foods: {by_src['USDA']} USDA, {by_src['LABEL']} label, {by_src['RECIPE']} recipes.")
    report.append("")
    report.append(f"### Problems ({len(missing)})" if missing else "No problems.")
    report.extend(missing)
    text = "\n".join(report)
    print(text)
    if report_path:
        report_path.write_text(text, encoding="utf-8")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
