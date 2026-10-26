#!/usr/bin/env python3
"""Suggests a food group for every food in foods.csv by plain rules (category and words in the name).
The result, scripts/food_sources/groups.csv, is reviewed by a person and committed; build_foods.py copies it
to assets/food_groups.csv. Run again after adding foods; existing lines are kept as they are."""
import csv, re
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
FOODS = ROOT / "app/src/main/assets/foods.csv"
OUT = ROOT / "scripts/food_sources/groups.csv"
SUGARY_DRINKS = {"dr03","dr04","dr05","dr10","dr12","dr17","dr18","dr20","dr21","dr26","dr27","dr29"}
NOT_SUGARY = {"c12","c13","c19","c31","c32","c34","c43","c44"}
STARCHY = {"v01","v02","v03","v04","v05","v32","v33"}

def rule(f):
    code, cat, uk, en = f["code"], f["category"], f["name_uk"].lower(), f["name_en"].lower()
    has = lambda *w: any(re.search(x, uk) or re.search(x, en) for x in w)
    if cat == "fastfood": return "FASTFOOD"
    if code in SUGARY_DRINKS: return "SUGARY_DRINK"
    if cat == "meat":
        if has("ковбас", "сосиск", "сардельк", "шинк", "бекон", "салямі", "сервелат", "кабанос", "мисливськ", "паштет", "буженин", "балик", "тушонк", "консерв", "sausage", "ham", "bacon", "salami", "frank"): return "PROCESSED_MEAT"
        if has("кур", "індич", "качк", "гуск", "chicken", "turkey", "duck"): return "POULTRY"
        if has("сало", "lard"): return "SFA_RICH"
        if has("яловичин", "свинин", "баранин", "телятин", "фарш", "печінк", "кролик", "beef", "pork", "lamb", "veal", "liver"): return "RED_MEAT"
        return None
    if cat == "fish":
        if has("лосос", "сьомг", "скумбрі", "оселед", "сардин", "шпрот", "форел", "кільк", "мойв", "salmon", "mackerel", "herring", "sardine", "sprat", "trout"): return "OILY_FISH"
        return "FISH"
    if cat == "legumes": return "LEGUMES"
    if cat == "nuts": return "NUTS"
    if cat in ("veg", "fruit") and code not in STARCHY and not has("сок", "juice", "сушен", "чипс"): return "VEG_FRUIT"
    if cat == "sweets":
        if code in NOT_SUGARY: return None
        return "SWEETS"
    if cat in ("grains", "bread"):
        if has("гречк", "вівсян", "цільнозерн", "бур", "пшон", "перлов", "ячн", "булгур", "кіноа", "житн", "висівк", "мюслі", "граноль", "whole", "oat", "buckwheat", "rye", "bran", "millet", "barley", "quinoa"): return "WHOLE_GRAIN"
        if has("круасан", "croissant"): return "SFA_RICH"
        if has("біл", "батон", "багет", "лаваш", "піта", "макарон", "спагет", "рис", "манн", "бублик", "сухар", "тортиль", "булочк", "white", "pasta", "rice", "semolina", "bagel", "roll"): return "REFINED_GRAIN"
        return None
    if cat == "fats":
        if has("вершков", "сало", "маргарин", "смалец", "butter", "lard", "margarine", "ghee"): return "SFA_RICH"
        return None
    if cat == "sauces" and has("майонез", "mayonnaise"): return "SFA_RICH"
    if cat == "dairy" and has("вершк", "cream") and not has("сир", "cheese", "морозив"): return "SFA_RICH"
    if cat == "dishes":
        if has("смажен", "фрі", "fried", "чебурек", "пиріж"): return "SFA_RICH"
        if has("вареник з кар", "пельмен", "сосиск", "ковбас"): return "PROCESSED_MEAT" if has("сосиск", "ковбас") else None
    return None

rows = [l for l in FOODS.open(encoding="utf-8") if l.strip() and not l.startswith("#")]
foods = list(csv.DictReader(rows, delimiter=";"))
old = {}
if OUT.exists():
    for l in OUT.read_text(encoding="utf-8").splitlines():
        if l and not l.startswith("#") and not l.startswith("code;"):
            c, g = (l.split(";") + [""])[:2]
            old[c] = g
lines = ["# Food group of each built-in food, for advice (see data/Advice.kt). Empty = no group. Reviewed by hand.", "code;group;name"]
for f in foods:
    g = old[f["code"]] if f["code"] in old else (rule(f) or "")
    lines.append(f'{f["code"]};{g};{f["name_uk"]}')
OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(len(foods), "foods;", sum(1 for l in lines[2:] if l.split(";")[1]), "with a group")
