#!/usr/bin/env python3
"""Checks that the database updates (migrations in AppDatabase.kt) lead from the first version to exactly
the structure Room expects now, and keep the data. Runs in CI after the build.

  check_migrations.py <schema json written by Room, e.g. app/schemas/ua.tarilka.data.AppDatabase/7.json>

Version 1 is the current structure minus what the migrations add (tables and columns), so it needs no
hand-kept copy. Each migration's SQL is read from AppDatabase.kt and run in SQLite, from 1 up to the
current version, with a row in every table; the result is compared with Room's schema the way Room
checks it when the app opens (columns, types, NOT NULL, primary key, defaults, indices).
"""
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/ua/tarilka/data/AppDatabase.kt"


def migrations():
    """{from_version: [sql, ...]} in the order they appear in each Migration object."""
    src = KT.read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r"Migration\((\d+),\s*(\d+)\)\s*\{(.*?)\n        \}\n", src, re.S):
        a, b, body = int(m.group(1)), int(m.group(2)), m.group(3)
        assert b == a + 1, f"migration {a}->{b} skips a version"
        sqls = []
        for call in re.finditer(r"execSQL\((.*?)\)\s*\n", body, re.S):
            parts = re.findall(r'"((?:[^"\\]|\\.)*)"', call.group(1))
            sqls.append("".join(parts))
        out[a] = sqls
    return out


def column_def(f, pk):
    s = f"`{f['columnName']}` {f['affinity']}"
    if pk["autoGenerate"] and pk["columnNames"] == [f["columnName"]]:
        s += " PRIMARY KEY AUTOINCREMENT"
    if f.get("notNull"):
        s += " NOT NULL"
    if "defaultValue" in f:
        s += f" DEFAULT {f['defaultValue']}"
    return s


def create_sql(e, drop_cols):
    pk = e["primaryKey"]
    cols = [column_def(f, pk) for f in e["fields"] if f["columnName"] not in drop_cols]
    if not pk["autoGenerate"]:
        cols.append("PRIMARY KEY(" + ", ".join(f"`{c}`" for c in pk["columnNames"]) + ")")
    return f"CREATE TABLE `{e['tableName']}` ({', '.join(cols)})"


def sample_value(f):
    return {"INTEGER": 1, "REAL": 1.5, "TEXT": "x", "BLOB": b"x"}.get(f["affinity"], 1)


def check(schema, steps, first_has):
    """Builds version 1 (with the optional tables in [first_has]), migrates and compares. Returns problems."""
    entities = {e["tableName"]: e for e in schema["database"]["entities"]}
    all_sql = [s for v in sorted(steps) for s in steps[v]]
    created = {m.group(1).strip("`") for s in all_sql for m in [re.search(r"CREATE TABLE IF NOT EXISTS\s+(\S+)", s)] if m}
    added = {}
    for s in all_sql:
        m = re.search(r"ALTER TABLE\s+(\S+)\s+ADD COLUMN\s+(\S+)", s)
        if m:
            added.setdefault(m.group(1).strip("`"), set()).add(m.group(2).strip("`"))

    db = sqlite3.connect(":memory:")
    rows = {}
    for name, e in entities.items():
        if name in created and name not in first_has:
            continue
        db.execute(create_sql(e, added.get(name, set())))
        for i in e.get("indices", []):
            db.execute(i["createSql"].replace("${TABLE_NAME}", name))
        fields = [f for f in e["fields"] if f["columnName"] not in added.get(name, set())]
        db.execute(
            f"INSERT INTO `{name}` ({', '.join('`' + f['columnName'] + '`' for f in fields)}) VALUES ({', '.join('?' for _ in fields)})",
            [sample_value(f) for f in fields],
        )
        rows[name] = 1

    problems = []
    for v in sorted(steps):
        for s in steps[v]:
            try:
                db.execute(s)
            except sqlite3.Error as ex:
                problems.append(f"migration {v}->{v + 1}: {ex} in: {s}")
    for name, e in entities.items():
        info = {r[1]: r for r in db.execute(f"PRAGMA table_info(`{name}`)")}
        if not info:
            problems.append(f"{name}: table missing after the migrations")
            continue
        want = {f["columnName"]: f for f in e["fields"]}
        for c in sorted(set(want) ^ set(info)):
            problems.append(f"{name}.{c}: column {'missing' if c in want else 'not expected'}")
        pk = e["primaryKey"]["columnNames"]
        for c, f in want.items():
            if c not in info:
                continue
            _, _, typ, notnull, dflt, pkpos = info[c]
            if typ.upper() != f["affinity"]:
                problems.append(f"{name}.{c}: type {typ}, Room expects {f['affinity']}")
            if bool(notnull) != bool(f.get("notNull")):
                problems.append(f"{name}.{c}: NOT NULL is {bool(notnull)}, Room expects {bool(f.get('notNull'))}")
            want_pk = pk.index(c) + 1 if c in pk else 0
            if pkpos != want_pk:
                problems.append(f"{name}.{c}: primary key position {pkpos}, Room expects {want_pk}")
            if "defaultValue" in f and str(dflt).strip("'") != str(f["defaultValue"]).strip("'"):
                problems.append(f"{name}.{c}: default {dflt}, Room expects {f['defaultValue']}")
        have_idx = {}
        for _, iname, unique, *_ in db.execute(f"PRAGMA index_list(`{name}`)"):
            if iname.startswith("sqlite_autoindex"):
                continue
            have_idx[iname] = (bool(unique), [r[2] for r in db.execute(f"PRAGMA index_info(`{iname}`)")])
        for i in e.get("indices", []):
            got = have_idx.get(i["name"])
            if got != (bool(i["unique"]), i["columnNames"]):
                problems.append(f"{name}: index {i['name']} is {got}, Room expects {(bool(i['unique']), i['columnNames'])}")
        if name in rows and db.execute(f"SELECT COUNT(*) FROM `{name}`").fetchone()[0] != rows[name]:
            problems.append(f"{name}: rows were lost")
    return problems


def main() -> int:
    schema = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    version = schema["database"]["version"]
    steps = migrations()
    if sorted(steps) != list(range(1, version)):
        print(f"PROBLEM: migrations found for {sorted(steps)}, expected 1..{version - 1}")
        return 1
    # The very first builds had no fluids table yet (see MIGRATION_1_2): check both.
    problems = check(schema, steps, first_has={"fluids"}) + check(schema, steps, first_has=set())
    print(f"Database: {len(steps)} migrations, 1 -> {version} checked against Room's schema")
    for p in sorted(set(problems)):
        print("PROBLEM:", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
