"""Generates the mod's PNG textures (item icon, GUI background, mod icon) without external deps."""
import os, struct, zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "src")

def write_png(path, w, h, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + bytes(c for x in range(w) for c in px[y][x]) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

def hexc(s, a=255):
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)

def canvas(w, h, c=(0, 0, 0, 0)):
    return [[c for _ in range(w)] for _ in range(h)]

def rect(px, x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            px[yy][xx] = c

# ---- item: a handheld "ificator" ray device ----
item = canvas(16, 16)
art = [
    "................",
    "...........cc...",
    "..........cCCc..",
    ".........cCWCc..",
    "........iiCCc...",
    ".......iIIic....",
    "......iIIIi.....",
    ".....iIIIi......",
    "....iIIIi.......",
    "...gIIIi........",
    "..wwgIi.........",
    ".wWWwg..........",
    ".wWWw...........",
    "..ww............",
    "................",
    "................",
]
pal = {"c": hexc("1f8a8a"), "C": hexc("4ff0e8"), "W": hexc("e8fffe"), "i": hexc("3b3f45"),
       "I": hexc("a9b0b8"), "g": hexc("d9a531"), "w": hexc("4a2f1b"), "W2": hexc("7a5230")}
pal["W"] = hexc("e8fffe")
for y, row in enumerate(art):
    for x, ch in enumerate(row):
        if ch == ".":
            continue
        if ch == "W" and y > 9:
            item[y][x] = hexc("7a5230")
        else:
            item[y][x] = pal[ch]
write_png(os.path.join(ROOT, "main/resources/assets/realistic_ificator/textures/item/realistic_ificator.png"), 16, 16, item)

# ---- item: physics gun (Gmod-style: dark body, glowing cyan prongs) ----
gun = canvas(16, 16)
gun_art = [
    "................",
    "............c...",
    "..........cCCc..",
    ".........cCWWc..",
    "........kCWCc...",
    ".......kKKkc....",
    "......kKBBKk....",
    ".....kKBBKk.....",
    "....kKKKKk......",
    "...gkKKkk.......",
    "..wwgkk.........",
    ".wWWwg..........",
    ".wWWw...........",
    "..ww............",
    "................",
    "................",
]
gun_pal = {"c": hexc("1aa6c7"), "C": hexc("5fe3ff"), "W": hexc("e6fbff"), "k": hexc("1c1f24"),
           "K": hexc("3a404a"), "B": hexc("2fd0ff"), "g": hexc("c9a227"), "w": hexc("2b2b2b")}
for y, row in enumerate(gun_art):
    for x, ch in enumerate(row):
        if ch == ".":
            continue
        gun[y][x] = hexc("555b66") if (ch == "W" and y > 9) else gun_pal[ch]
write_png(os.path.join(ROOT, "main/resources/assets/realistic_ificator/textures/item/physics_gun.png"), 16, 16, gun)

# ---- item: structure wand (blaze-rod shaft, amethyst tip with a selection-box glint) ----
wand = canvas(16, 16)
wand_art = [
    "................",
    "...........aAa..",
    "..........aAWAa.",
    "..........AWWWA.",
    "..........aAWAa.",
    ".........r.aAa..",
    "........rR......",
    ".......rRr......",
    "......rRr.......",
    ".....rRr........",
    "....rRr.........",
    "...rRr..........",
    "..dRr...........",
    ".ddr............",
    ".dd.............",
    "................",
]
wand_pal = {"a": hexc("8a5fc2"), "A": hexc("c79bff"), "W": hexc("f6ecff"), "r": hexc("b5651d"),
            "R": hexc("f2b33d"), "d": hexc("5a3a1a")}
for y, row in enumerate(wand_art):
    for x, ch in enumerate(row):
        if ch != ".":
            wand[y][x] = wand_pal[ch]
write_png(os.path.join(ROOT, "main/resources/assets/realistic_ificator/textures/item/structure_wand.png"), 16, 16, wand)

# ---- GUI: smithing-table style panel, 176x166 on a 256x256 sheet ----
W, H = 176, 166
gui = canvas(256, 256)
BG, LIGHT, DARK, BLACK = hexc("c6c6c6"), hexc("ffffff"), hexc("555555"), hexc("000000")
rect(gui, 0, 0, W, H, BG)
# outer bevel like vanilla containers
rect(gui, 2, 0, W - 4, 1, BLACK); rect(gui, 2, H - 1, W - 4, 1, BLACK)
rect(gui, 0, 2, 1, H - 4, BLACK); rect(gui, W - 1, 2, 1, H - 4, BLACK)
gui[1][1] = gui[1][W - 2] = gui[H - 2][1] = gui[H - 2][W - 2] = BLACK
rect(gui, 2, 1, W - 4, 2, LIGHT); rect(gui, 1, 2, 2, H - 4, LIGHT)
rect(gui, 2, H - 3, W - 4, 2, DARK); rect(gui, W - 3, 2, 2, H - 4, DARK)
gui[2][2] = LIGHT; gui[H - 3][W - 3] = DARK

def slot(x, y, size=18):
    rect(gui, x, y, size, size, hexc("8b8b8b"))
    rect(gui, x, y, size - 1, 1, hexc("373737")); rect(gui, x, y, 1, size - 1, hexc("373737"))
    rect(gui, x + 1, y + size - 1, size - 1, 1, LIGHT); rect(gui, x + size - 1, y + 1, 1, size - 1, LIGHT)

# input / result (item positions 44,47 and 116,47; slot frame is offset by -1, result is a big 26px frame)
slot(43, 46)
slot(111, 42, 26)
# arrow between them
arrow = hexc("8b8b8b")
rect(gui, 68, 54, 22, 4, arrow)
for i in range(8):
    rect(gui, 90 + i, 48 + i, 1, 16 - 2 * i, arrow)
# decorative "physics" cube outline hinting at the transformation
for xx in range(8):
    for yy in range(5):
        pass
# player inventory
for r in range(3):
    for c in range(9):
        slot(7 + c * 18, 83 + r * 18)
for c in range(9):
    slot(7 + c * 18, 141)
write_png(os.path.join(ROOT, "main/resources/assets/realistic_ificator/textures/gui/container/realistic_ificator.png"), 256, 256, gui)

# ---- mod icon: the item scaled 8x ----
icon = canvas(128, 128)
for y in range(16):
    for x in range(16):
        rect(icon, x * 8, y * 8, 8, 8, item[y][x])
write_png(os.path.join(ROOT, "main/resources/assets/realistic_ificator/icon.png"), 128, 128, icon)
print("textures written")
