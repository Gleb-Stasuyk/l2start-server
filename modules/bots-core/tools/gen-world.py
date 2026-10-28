#!/usr/bin/env python3
"""Generate the presence director's world catalogue from the stock Mobius data (run from the repo root).

    python3 modules/bots-core/tools/gen-world.py

Writes modules/bots-core/data/world.xml (towns + hunting grounds of the whole world) and
modules/bots-core/data/gear.xml (armour sets and weapons by grade). Deterministic: the same stock data gives the
same files. Re-run after a spawn/zone/item update (tools/verify-upstream).

Towns: stock TownZone polygons (zones/custom_town.xml); anchors = NPC spawns inside the polygon by NPC type
(Teleporter = gk, Merchant/Adventurer = shop, Warehouse = wh, Trainer/VillageMaster* = trainer) and the stock
restart points (mapregion respawnPoint, not chaotic) inside it.
Hunting grounds: stock monster spawn territories (type Monster, no raid bosses, no quest/event/instance files)
clustered per spawn file by distance and level; level range = 10th..90th percentile of the monster levels
(weighted by count); spots = territory centres.
"""
import glob, math, os, re, sys
import xml.etree.ElementTree as ET

DATA = "core/dist/game/data"
OUT = "modules/bots-core/data"

SKIP_FILES = re.compile(r"(NPCs|Quest|FakePlayers|TreasureBoxes|MonsterRace|QueenAntGuards|AntharasLair|ImperialTomb|"
                        r"FourSepulchers|Castles/|Catacombs/|SevenSigns/|ChapelGuards|DaimonsAltar|Gremlins|YetisTable)")
INDOOR_FILES = re.compile(r"(TowerOfInsolence|GiantsCave|CryptsOfDiscrace|FrozenLabyrinth|PavelArchaic|IvoryTower|"
                          r"PaganAltar|ForgeOfGods|StakatoNest|DenOfEvil|MithrilMines|BeeHive|DevilsIsle)")
MERGE = 2600          # territories this close (centre to centre) can be one ground ...
LEVEL_GAP = 6         # ... when their mid levels differ at most this much
MAX_RADIUS = 3600     # a ground is at most this big (else a new one starts)
MIN_MONSTERS = 6
# A smaller cluster (a lone territory of a few monsters, e.g. the west end of Alligator Beach) still becomes a ground:
# a player hunting there must find it in the catalogue, else the presence director has nothing true to go by.
# Such grounds are numbered after the regular ones of their file, so the ids of the regular grounds stay as they were.
MIN_SMALL = 2
TOWN_LEVELS = {"talking_island": (1, 18), "elven_village": (1, 18), "dark_elven_village": (1, 18), "orc_village": (1, 18),
               "dwarven_village": (1, 20), "gludin_village": (15, 30), "gludio_castle_town": (18, 35),
               "town_of_schuttgart": (40, 75), "rune_castle_town": (50, 78)}
ANCHOR_TYPES = {"Teleporter": "gk", "Merchant": "shop", "Adventurer": "shop", "Warehouse": "wh", "Trainer": "trainer"}

def npc_table():
    npcs = {}
    for f in glob.glob(DATA + "/stats/npcs/*.xml"):
        for n in ET.parse(f).getroot().iter("npc"):
            ai = n.find("ai")
            npcs[int(n.get("id"))] = dict(level=int(n.get("level", "0")), type=n.get("type"), name=n.get("name"),
                                         aggro=ai is not None and int(ai.get("aggroRange", "0")) > 0 and ai.get("isAggressive") != "false")
    return npcs

def inside(poly, x, y):
    c = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]; xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-9) + xi):
            c = not c
        j = i
    return c

def spawn_files():
    for f in sorted(glob.glob(DATA + "/spawns/**/*.xml", recursive=True)):
        rel = os.path.relpath(f, DATA + "/spawns").replace(os.sep, "/")
        root = ET.parse(f).getroot()
        if root.get("enabled") == "false":
            continue
        yield rel, root

def towns(npcs):
    out = []
    root = ET.parse(DATA + "/zones/custom_town.xml").getroot()
    for z in root.iter("zone"):
        if z.get("type") != "TownZone" or "Primeval" in z.get("name"):
            continue
        nodes = [(int(n.get("X")), int(n.get("Y"))) for n in z.iter("node")]
        if z.get("shape") == "Cuboid" and len(nodes) == 2:
            (x1, y1), (x2, y2) = nodes
            nodes = [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]
        if len(nodes) < 3:
            continue
        out.append(dict(name=z.get("name"), id=int(z.get("id")), poly=nodes, minZ=int(z.get("minZ")), maxZ=int(z.get("maxZ")), anchors=[]))
    for rel, root in spawn_files():
        for sp in root.iter("spawn"):
            for n in sp.findall("npc"):
                if n.get("x") is None:
                    continue
                t = npcs.get(int(n.get("id")))
                if t is None:
                    continue
                kind = ANCHOR_TYPES.get(t["type"]) or ("trainer" if t["type"].startswith("VillageMaster") else None)
                if kind is None:
                    continue
                x, y, zz = int(n.get("x")), int(n.get("y")), int(n.get("z"))
                for tw in out:
                    if tw["minZ"] - 300 <= zz <= tw["maxZ"] + 300 and inside(tw["poly"], x, y):
                        tw["anchors"].append((kind, x, y, zz, t["name"]))
    for f in sorted(glob.glob(DATA + "/mapregion/*.xml")):
        for r in ET.parse(f).getroot().iter("region"):
            for p in r.iter("respawnPoint"):
                if p.get("isChaotic") == "true":
                    continue
                x, y, zz = int(p.get("X")), int(p.get("Y")), int(p.get("Z"))
                for tw in out:
                    if tw["minZ"] - 300 <= zz <= tw["maxZ"] + 300 and inside(tw["poly"], x, y):
                        tw["anchors"].append(("restart", x, y, zz, ""))
    for tw in out:
        seen = set(); uniq = []
        for a in tw["anchors"]:
            k = (a[0], a[1] // 50, a[2] // 50)
            if k not in seen:
                seen.add(k); uniq.append(a)
        tw["anchors"] = uniq
        xs = [p[0] for p in tw["poly"]]; ys = [p[1] for p in tw["poly"]]
        cx = (min(xs) + max(xs)) // 2; cy = (min(ys) + max(ys)) // 2
        tw["x"], tw["y"] = cx, cy
        tw["r"] = int(max(math.dist((cx, cy), p) for p in tw["poly"]))
        zs = [a[3] for a in tw["anchors"]]
        tw["z"] = sorted(zs)[len(zs) // 2] if zs else (tw["minZ"] + tw["maxZ"]) // 2
    return [t for t in out if any(a[0] == "gk" for a in t["anchors"])]

def pct(values, q):
    # values: list of (level, weight)
    values = sorted(values)
    total = sum(w for _, w in values); acc = 0
    for v, w in values:
        acc += w
        if acc >= total * q:
            return v
    return values[-1][0]

def grounds(npcs):
    terrs = []
    for rel, root in spawn_files():
        if SKIP_FILES.search(rel):
            continue
        indoor = bool(INDOOR_FILES.search(rel))
        stem = os.path.splitext(os.path.basename(rel))[0]
        for sp in root.iter("spawn"):
            t = sp.find("territory")
            if t is None:
                continue
            nodes = [(int(n.get("x")), int(n.get("y"))) for n in t.iter("node")]
            if len(nodes) < 3:
                continue
            mons = []
            for n in sp.findall("npc"):
                info = npcs.get(int(n.get("id")))
                if info is None or info["type"] != "Monster" or not (1 <= info["level"] <= 85):
                    continue
                mons.append((int(n.get("id")), info, int(n.get("count", "1"))))
            if not mons:
                continue
            cx = sum(p[0] for p in nodes) / len(nodes); cy = sum(p[1] for p in nodes) / len(nodes)
            z = (int(t.get("minZ")) + int(t.get("maxZ"))) // 2
            r = max(math.dist((cx, cy), p) for p in nodes)
            lv = [(m[1]["level"], m[2]) for m in mons]
            terrs.append(dict(file=rel, stem=stem, indoor=indoor, x=cx, y=cy, z=z, r=r,
                              lv=lv, mid=pct(lv, 0.5), n=sum(m[2] for m in mons),
                              aggro=sum(m[2] for m in mons if m[1]["aggro"]), ids={m[0] for m in mons}))
    gs = []
    for t in terrs:
        best = None
        for g in gs:
            if g["file"] != t["file"] or abs(g["mid"] - t["mid"]) > LEVEL_GAP:
                continue
            if not any(math.dist((m["x"], m["y"]), (t["x"], t["y"])) < MERGE for m in g["m"]):
                continue
            ms = g["m"] + [t]
            w = sum(m["n"] for m in ms)
            gx = sum(m["x"] * m["n"] for m in ms) / w; gy = sum(m["y"] * m["n"] for m in ms) / w
            if max(math.dist((gx, gy), (m["x"], m["y"])) + min(m["r"], 1500) for m in ms) > MAX_RADIUS:
                continue
            best = g; break
        if best is None:
            gs.append(dict(file=t["file"], stem=t["stem"], indoor=t["indoor"], mid=t["mid"], m=[t]))
        else:
            best["m"].append(t)
            best["mid"] = pct([lv for m in best["m"] for lv in m["lv"]], 0.5)
    out = []
    for g in gs:
        m = g["m"]; n = sum(t["n"] for t in m)
        if n < MIN_SMALL:
            continue
        x = sum(t["x"] * t["n"] for t in m) / n; y = sum(t["y"] * t["n"] for t in m) / n
        z = round(sum(t["z"] * t["n"] for t in m) / n)
        r = min(MAX_RADIUS, int(max(math.dist((x, y), (t["x"], t["y"])) + min(t["r"], 1500) for t in m)))
        lv = [l for t in m for l in t["lv"]]
        out.append(dict(stem=g["stem"], file=g["file"], indoor=g["indoor"], x=int(x), y=int(y), z=z, r=max(r, 600),
                        lo=pct(lv, 0.1), hi=pct(lv, 0.9), n=n, aggro=round(sum(t["aggro"] for t in m) / n, 2),
                        spots=[(int(t["x"]), int(t["y"]), t["z"]) for t in sorted(m, key=lambda t: (t["x"], t["y"]))], small=n < MIN_MONSTERS))
    return out

def nearest_town(tws, x, y):
    return min(tws, key=lambda t: math.dist((t["x"], t["y"]), (x, y)))

def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")

# Display names of the grounds (by id base): shouts and chat use them, never the id. A base without one fails the run.
GROUND_NAMES = {
    "alligatorbeach": ("Пляж Аллигаторов", "Alligator Beach"),
    "alligatorisland": ("Остров Аллигаторов", "Alligator Island"),
    "beehive": ("Улей", "Beehive"),
    "blazingswamp": ("Пылающее Болото", "Blazing Swamp"),
    "brekasstronghold": ("Крепость Брек", "Breka's Stronghold"),
    "cemetery": ("Кладбище", "Cemetery"),
    "crumamarshlands": ("Болота Крумы", "Cruma Marshlands"),
    "darkelfstarting": ("Лес Темных Эльфов", "Dark Elven Forest"),
    "deathpass": ("Перевал Смерти", "Death Pass"),
    "devilsisle": ("Остров Дьявола", "Devil's Isle"),
    "dragonvalleymonsters": ("Долина Драконов", "Dragon Valley"),
    "dwarvenstarting": ("Земли Гномов", "Dwarven Lands"),
    "elvenstarting": ("Эльфийский Лес", "Elven Forest"),
    "enchantedvalley": ("Зачарованная Долина", "Enchanted Valley"),
    "executiongrounds": ("Место Казни", "Execution Grounds"),
    "fieldofsilence": ("Поле Безмолвия", "Field of Silence"),
    "fieldofwhispers": ("Поле Шепота", "Field of Whispers"),
    "fieldsofmassacre": ("Поля Резни", "Fields of Massacre"),
    "forgeofgods": ("Кузница Богов", "Forge of the Gods"),
    "gardenofbeasts": ("Сад Зверей", "Garden of Beasts"),
    "gardenofeva": ("Сад Евы", "Garden of Eva"),
    "giantscave": ("Пещера Гигантов", "Giant's Cave"),
    "gorgonflowergarden": ("Сад Горгоны", "Gorgon Flower Garden"),
    "hardinsacademy": ("Академия Хардина", "Hardin's Academy"),
    "hotsprings": ("Горячие Источники", "Hot Springs"),
    "ivorytower": ("Башня Слоновой Кости", "Ivory Tower"),
    "ketraorcoutpost": ("Аванпост Кетра", "Ketra Orc Outpost"),
    "near_heine": ("окрестности Гейна", "near Heine"),
    "orcstarting": ("Земли Орков", "Orc Lands"),
    "outlawforest": ("Лес Разбойников", "Outlaw Forest"),
    "plainsofdion": ("Равнины Диона", "Plains of Dion"),
    "plainsofglory": ("Равнины Славы", "Plains of Glory"),
    "plainsofthelizardmen": ("Равнины Ящеров", "Plains of the Lizardmen"),
    "primevalisle": ("Первобытный Остров", "Primeval Isle"),
    "seaofspores": ("Море Спор", "Sea of Spores"),
    "silentvalley": ("Долина Тишины", "Silent Valley"),
    "skyshadowmeadow": ("Луг Небесной Тени", "Sky Shadow Meadow"),
    "swampofscreams": ("Болото Криков", "Swamp of Screams"),
    "talkingislandmonsters": ("Говорящий Остров", "Talking Island"),
    "tanorcanyon": ("Каньон Танор", "Tanor Canyon"),
    "theforbiddengateway": ("Запретные Врата", "Forbidden Gateway"),
    "timakoutpost": ("Аванпост Тимак", "Timak Outpost"),
    "towerofinsolence": ("Башня Дерзости", "Tower of Insolence"),
    "turekorcs": ("Лагерь Турек", "Turek Orc Camp"),
    "valleyofsaints": ("Долина Святых", "Valley of Saints"),
    "varkaslenosoutpost": ("Аванпост Варка", "Varka Silenos Outpost"),
    "wallofargos": ("Стена Аргоса", "Wall of Argos"),
    "wartornplains": ("Разоренные Равнины", "War-Torn Plains"),
    "wasteland": ("Пустоши", "Wasteland"),
}

def ground_name(gid):
    base = re.sub(r"_\d+$", "", gid)
    if base.startswith("near_") and base not in GROUND_NAMES:
        raise SystemExit("no display name for ground base %s (add it to GROUND_NAMES)" % base)
    if base not in GROUND_NAMES:
        raise SystemExit("no display name for ground base %s (add it to GROUND_NAMES)" % base)
    return GROUND_NAMES[base]

def main():
    npcs = npc_table()
    tws = towns(npcs)
    gds = grounds(npcs)
    gds.sort(key=lambda g: (g["file"], g["small"], g["lo"], g["x"], g["y"]))
    # names: named spawn files keep their name, region files (Others/17_20.xml) take the nearest town
    count = {}
    for g in gds:
        base = g["stem"] if not re.match(r"^\d+_\d+", g["stem"]) else "near_" + slug(nearest_town(tws, g["x"], g["y"])["name"])
        base = slug(base)
        count[base] = count.get(base, 0) + 1
        g["id"] = "%s_%d" % (base, count[base])
        g["town"] = slug(nearest_town(tws, g["x"], g["y"])["name"])
    # town level band: the grounds whose nearest town it is (by monsters), 15th..85th percentile of their mid level;
    # a few towns are set by hand (starting villages, and towns whose stock spawns sit far away)
    for t in tws:
        t["id"] = slug(t["name"])
        near = [((g["lo"] + g["hi"]) // 2, g["n"]) for g in gds if g["town"] == t["id"] and not g["small"]]
        t["lo"], t["hi"] = (pct(near, 0.15), pct(near, 0.85)) if near else (1, 20)
        if t["id"] in TOWN_LEVELS:
            t["lo"], t["hi"] = TOWN_LEVELS[t["id"]]
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<!-- Generated by modules/bots-core/tools/gen-world.py from the stock spawns, zones and map regions; do not edit by hand. -->',
           '<!-- town: TownZone polygon centre/radius, level band of the grounds around it, anchors (gk/shop/wh/trainer/restart). -->',
           '<!-- ground: monster territories of one spawn file clustered; level 10..90th percentile; spots = territory centres. -->',
           '<world>']
    for t in sorted(tws, key=lambda t: t["id"]):
        out.append('\t<town id="%s" name="%s" x="%d" y="%d" z="%d" radius="%d" minLevel="%d" maxLevel="%d">' % (t["id"], t["name"], t["x"], t["y"], t["z"], t["r"], t["lo"], t["hi"]))
        out.append('\t\t<poly>%s</poly>' % " ".join("%d,%d" % p for p in t["poly"]))
        for a in sorted(t["anchors"]):
            out.append('\t\t<anchor kind="%s" x="%d" y="%d" z="%d" />%s' % (a[0], a[1], a[2], a[3], (" <!-- %s -->" % a[4].replace("--", "-")) if a[4] else ""))
        out.append('\t</town>')
    for g in gds:
        nm = ground_name(g["id"])
        out.append('\t<ground id="%s" name="%s" nameEn="%s" town="%s" x="%d" y="%d" z="%d" radius="%d" minLevel="%d" maxLevel="%d" monsters="%d" aggressive="%.2f" indoor="%s">'
                   % (g["id"], nm[0], nm[1], g["town"], g["x"], g["y"], g["z"], g["r"], g["lo"], g["hi"], g["n"], g["aggro"], str(g["indoor"]).lower()))
        out.append('\t\t<spots>%s</spots>' % " ".join("%d,%d,%d" % s for s in g["spots"]))
        out.append('\t</ground>')
    out.append('</world>')
    with open(OUT + "/world.xml", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("towns: %d (anchors %d), grounds: %d (monsters %d)" % (len(tws), sum(len(t["anchors"]) for t in tws), len(gds), sum(g["n"] for g in gds)), file=sys.stderr)
    gear()

# ---------------------------------------------------------------- gear
GRADES = ["NONE", "D", "C", "B", "A", "S"]
BAD_NAME = re.compile(r"( - |Shadow|Test|For NPC|\(|Event|Common|Rental|Tomb Savant|Nephilim|Lord of|Valakas|Zariche|Akamanah|Demon Splinter|Heavens Divider|Sealed|Infinity|Dynasty)")

def items():
    res = {}
    for f in glob.glob(DATA + "/stats/items/*.xml"):
        for it in ET.parse(f).getroot().iter("item"):
            s = {x.get("name"): x.get("val") for x in it.findall("set")}
            res[int(it.get("id"))] = dict(type=it.get("type"), name=it.get("name"), s=s)
    return res

def gear():
    its = items()
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<!-- Generated by modules/bots-core/tools/gen-world.py from stats/armorsets and stats/items; do not edit by hand. -->',
           '<!-- set: stock armour set (armour type of its chest); weapon: kind = weapon type + hands + magic, cheapest first. -->',
           '<gear>']
    sets = []
    for f in sorted(glob.glob(DATA + "/stats/armorsets/*.xml")):
        if "clan" in f:
            continue
        for st in ET.parse(f).getroot().iter("set"):
            parts = {}
            for slot in ("chest", "legs", "head", "gloves", "feet", "shield"):
                e = st.find(slot)
                if e is not None:
                    parts[slot] = int(e.get("id"))
            ch = its.get(parts.get("chest"))
            if ch is None or BAD_NAME.search(ch["name"]):
                continue
            grade = ch["s"].get("crystal_type", "NONE")
            atype = ch["s"].get("armor_type", "NONE")
            if atype not in ("HEAVY", "LIGHT", "MAGIC"):
                continue
            price = sum(int(its.get(i, {"s": {}})["s"].get("price", "0") or 0) for i in parts.values())
            sets.append((GRADES.index(grade), atype, price, int(st.get("id")), parts, ch["name"]))
    for g, atype, price, sid, parts, name in sorted(sets):
        out.append('\t<set id="%d" grade="%s" armor="%s" price="%d" %s/> <!-- %s -->' % (sid, GRADES[g], atype, price,
                   "".join('%s="%d" ' % (k, v) for k, v in sorted(parts.items())), name.replace("--", "-")))
    weps = []
    for iid, it in its.items():
        s = it["s"]
        if it["type"] != "Weapon" or BAD_NAME.search(it["name"]) or s.get("for_npc") == "true" or s.get("is_tradable") == "false":
            continue
        price = int(s.get("price", "0") or 0)
        wt = s.get("weapon_type"); body = s.get("bodypart"); grade = s.get("crystal_type", "NONE")
        if price <= 0 or wt in (None, "ETC", "FISHINGROD"):
            continue
        magic = s.get("is_magic_weapon") == "true"
        if magic:
            kind = "MAGIC"
        elif wt in ("SWORD", "BLUNT"):
            kind = wt + ("2H" if body == "lrhand" else "1H")
        else:
            kind = wt
        weps.append((GRADES.index(grade), kind, price, iid, it["name"]))
    for g, kind, price, iid, name in sorted(weps):
        out.append('\t<weapon id="%d" grade="%s" kind="%s" price="%d" /> <!-- %s -->' % (iid, GRADES[g], kind, price, name.replace("--", "-")))
    out.append('</gear>')
    with open(OUT + "/gear.xml", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("armour sets: %d, weapons: %d" % (len(sets), len(weps)), file=sys.stderr)

if __name__ == "__main__":
    main()
