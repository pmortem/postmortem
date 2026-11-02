"""Measures the board coordinate labels in a smoke screenshot of the start position.

Usage: measure-coordinates.py <png> [light|dark] [flipped]
Prints the bounding box of the label pixels on a1 (its file letter), h8 and h6 (their rank digits), as fractions of a square from the square's top-left corner. Targets (Lichess app): a file letter starts at
x 0.06 with its bottom at y 0.89; a rank digit ends at x 0.93 with its baseline at y 0.21. Screenshots are the smoke's
`result-light.png` / `result-dark.png` (and `flipped-*.png`).
"""
import struct, sys, zlib

THEMES = {
    "light": ((0xEB, 0xDC, 0xC0), (0x8C, 0xA3, 0x8F)),
    "dark": ((0xB5, 0xB0, 0x9F), (0x4F, 0x63, 0x54)),
}
LIGHT, DARK = THEMES["light"]

def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, w = 8, b"", None
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype in (2, 6), (depth, ctype)
            bpp = 3 if ctype == 2 else 4
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows


def px(rows, bpp, x, y):
    r = rows[y]; return tuple(r[x * bpp:x * bpp + 3])


def close(c, t, d):
    return sum((a - b) ** 2 for a, b in zip(c, t)) <= d * d


def main(path, theme="light", flipped=False):
    global LIGHT, DARK
    LIGHT, DARK = THEMES[theme]
    w, h, bpp, rows = read_png(path)
    # The board: the bounding box of exact square colours in the top half of the screen.
    xs, ys = [], []
    for y in range(0, h // 2, 2):
        for x in range(0, w, 2):
            c = px(rows, bpp, x, y)
            if c == LIGHT or c == DARK:
                xs.append(x); ys.append(y)
    left, right, top = min(xs), max(xs) + 1, min(ys)
    sq = (right - left) / 8
    print("board x %d..%d, top %d, square %.1f px" % (left, right, top, sq))

    def label_box(row, col, kind):
        light = (row + col) % 2 == 0
        label = DARK if light else LIGHT
        x0, y0 = left + col * sq, top + row * sq
        # A rank digit lives in the top-right of its square, a file letter in the bottom-left.
        fx, fy = ((0.6, 1.0), (0.0, 0.3)) if kind == "rank" else ((0.0, 0.3), (0.7, 1.0))
        pts = []
        for y in range(int(y0 + fy[0] * sq), int(y0 + fy[1] * sq)):
            for x in range(int(x0 + fx[0] * sq), int(x0 + fx[1] * sq)):
                if close(px(rows, bpp, x, y), label, 10):
                    pts.append(((x - x0) / sq, (y - y0) / sq))
        if not pts:
            return None
        return (min(p[0] for p in pts), max(p[0] for p in pts), min(p[1] for p in pts), max(p[1] for p in pts), len(pts))

    a1 = 0
    cases = [
        ("bottom-left letter (col %d)" % a1, 7, a1, "file"),
        ("top-right digit (row 0)", 0, 7, "rank"),
        ("right digit (row 2)", 2, 7, "rank"),
    ]
    for name, row, col, kind in cases:
        b = label_box(row, col, kind)
        if b is None:
            print("%-28s not found" % name)
        else:
            print("%-28s x %.3f-%.3f  y %.3f-%.3f  height %.3f  (%d px)" % (name, b[0], b[1], b[2], b[3], b[3] - b[2], b[4]))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "light", "flipped" in sys.argv[3:])
