#!/usr/bin/env python3
"""Cross-checks every USDA row of foods.csv against the official USDA FoodData Central
SR Legacy dataset (public domain).

Usage: verify_usda.py <path to SR Legacy CSV folder or zip>
Download: https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip

Prints a Markdown report (also saved to the usda-report branch by CI). Exit code is always 0.
"""
import csv
import difflib
import io
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FOODS = ROOT / "app/src/main/assets/foods.csv"
NUTRIENTS = {"1008": "kcal", "1003": "protein", "1004": "fat", "1005": "carbs", "1079": "fiber"}


def open_tables(src: Path):
    if src.suffix == ".zip":
        z = zipfile.ZipFile(src)
        names = {Path(n).name: n for n in z.namelist()}
        return lambda name: io.TextIOWrapper(z.open(names[name]), encoding="utf-8")
    files = {p.name: p for p in src.rglob("*.csv")}
    return lambda name: files[name].open(encoding="utf-8")


def main() -> int:
    opener = open_tables(Path(sys.argv[1]))
    desc_to_id = {}
    with opener("food.csv") as f:
        for r in csv.DictReader(f):
            desc_to_id[r["description"].strip().lower()] = r["fdc_id"]
    values = {}
    with opener("food_nutrient.csv") as f:
        for r in csv.DictReader(f):
            key = NUTRIENTS.get(r["nutrient_id"])
            if key:
                values.setdefault(r["fdc_id"], {})[key] = float(r["amount"] or 0)

    lines = [l for l in FOODS.open(encoding="utf-8") if l.strip() and not l.startswith("#")]
    rows = list(csv.DictReader(lines, delimiter=";"))
    out = ["| code | food | match | field | ours | USDA |", "|---|---|---|---|---|---|"]
    ok = flagged = missing = 0
    all_desc = list(desc_to_id)
    for r in rows:
        if r["source"] != "USDA":
            continue
        ref = r["ref"].strip().lower()
        fid = desc_to_id.get(ref)
        match = "exact"
        if fid is None:
            close = difflib.get_close_matches(ref, all_desc, n=1, cutoff=0.6)
            if not close:
                missing += 1
                out.append(f"| {r['code']} | {r['name_en']} | NOT FOUND | | | |")
                continue
            fid = desc_to_id[close[0]]
            match = f"closest: {close[0]}"
        usda = values.get(fid, {})
        diffs = []
        for k in ("kcal", "protein", "fat", "carbs", "fiber"):
            ours = float(r[k])
            theirs = usda.get(k)
            if theirs is None:
                continue
            tol = max(0.15 * abs(theirs), 1.0 if k != "kcal" else 5.0) if k == "fiber" else max(0.05 * abs(theirs), 0.6 if k != "kcal" else 4.0)
            if abs(ours - theirs) > tol:
                diffs.append((k, ours, theirs))
        if diffs or match != "exact":
            flagged += 1
            if not diffs:
                out.append(f"| {r['code']} | {r['name_en']} | {match} | ok | | |")
            for k, o, t in diffs:
                out.append(f"| {r['code']} | {r['name_en']} | {match} | {k} | {o} | {t} |")
        else:
            ok += 1
    print(f"## USDA cross-check\n\n{ok} rows match exactly, {flagged} need a look, {missing} not found.\n")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
