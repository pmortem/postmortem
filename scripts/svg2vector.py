#!/usr/bin/env python3
"""Converts a Lichess piece set (wK.svg ... bP.svg) to Android VectorDrawables. Used for merida (Armando Hernandez
Marroquin, GPLv2+) and before it cburnett (Colin M.L. Burnett, GPLv2+), both as shipped by Lichess.

Usage: svg2vector.py <input-dir-with-wK.svg...> <output-dir>
Handles what those sets use: nested <g> with inherited presentation attributes, <path>, <rect> (rounded or not), <circle>,
linear gradient fills and the per-element `opacity`.
Writes piece_white_king.xml ... piece_black_pawn.xml.
"""
import os
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"
COLORS = {"w": "white", "b": "black"}
KINDS = {"K": "king", "Q": "queen", "R": "rook", "B": "bishop", "N": "knight", "P": "pawn"}
INHERITED = ["fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule"]


def color(value):
    value = value.lstrip("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    return "#FF" + value.upper()


def fmt(x):
    return ("%g" % x)


def shapes(node, inherited):
    style = dict(inherited)
    for key in INHERITED:
        if key in node.attrib:
            style[key] = node.attrib[key]
    tag = node.tag.replace(NS, "")
    if "opacity" in node.attrib:
        style["opacity"] = node.attrib["opacity"]
    else:
        style.pop("opacity", None)  # opacity belongs to the element, it is not inherited
    if tag in ("g", "svg"):
        for child in node:
            yield from shapes(child, style)
    elif tag == "path":
        yield node.attrib["d"], style
    elif tag == "rect":
        x, y, w, h = (float(node.attrib[k]) for k in ("x", "y", "width", "height"))
        rx = float(node.attrib.get("rx", node.attrib.get("ry", 0)))
        ry = float(node.attrib.get("ry", node.attrib.get("rx", 0)))
        if rx == 0 and ry == 0:
            d = "M%s,%sh%sv%sh%sz" % (fmt(x), fmt(y), fmt(w), fmt(h), fmt(-w))
        else:
            d = "M%s,%sh%sa%s,%s 0 0 1 %s,%sv%sa%s,%s 0 0 1 %s,%sh%sa%s,%s 0 0 1 %s,%sv%sa%s,%s 0 0 1 %s,%sz" % (
                fmt(x + rx), fmt(y), fmt(w - 2 * rx), fmt(rx), fmt(ry), fmt(rx), fmt(ry), fmt(h - 2 * ry),
                fmt(rx), fmt(ry), fmt(-rx), fmt(ry), fmt(-(w - 2 * rx)), fmt(rx), fmt(ry), fmt(-rx), fmt(-ry),
                fmt(-(h - 2 * ry)), fmt(rx), fmt(ry), fmt(rx), fmt(-ry))
        yield d, style
    elif tag == "circle":
        cx, cy, r = (float(node.attrib[k]) for k in ("cx", "cy", "r"))
        d = "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0z" % (fmt(cx - r), fmt(cy), fmt(r), fmt(r), fmt(2 * r), fmt(r), fmt(r), fmt(-2 * r))
        yield d, style


def gradients(root):
    """The <linearGradient> definitions by id, as (x1, y1, x2, y2, [(offset, ARGB colour)]); user-space coordinates only."""
    found = {}
    for node in root.iter(NS + "linearGradient"):
        stops = []
        for stop in node.findall(NS + "stop"):
            alpha = int(round(255 * float(stop.attrib.get("stop-opacity", "1"))))
            stops.append((stop.attrib["offset"], "#%02X%s" % (alpha, color(stop.attrib["stop-color"])[3:])))
        found[node.attrib["id"]] = tuple(float(node.attrib[k]) for k in ("x1", "y1", "x2", "y2")) + (stops,)
    return found


def gradient_path(d, gradient):
    x1, y1, x2, y2, stops = gradient
    items = "".join('\n            <item android:offset="%s" android:color="%s" />' % stop for stop in stops)
    return (
        '    <path\n        android:pathData="%s">\n        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s">'
        "%s\n            </gradient>\n        </aapt:attr>\n    </path>" % (d, fmt(x1), fmt(y1), fmt(x2), fmt(y2), items)
    )


def convert(svg_path):
    root = ET.parse(svg_path).getroot()
    defs = gradients(root)
    size = root.attrib["viewBox"].split()[2]
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"' +
           ('\n    xmlns:aapt="http://schemas.android.com/aapt"' if defs else ""),
           '    android:width="%sdp"' % size,
           '    android:height="%sdp"' % size,
           '    android:viewportWidth="%s"' % size,
           '    android:viewportHeight="%s">' % size]
    for d, style in shapes(root, {"fill": "#000"}):
        if style["fill"].startswith("url(#"):
            out.append(gradient_path(d, defs[style["fill"][5:-1]]))
            continue
        attrs = ['android:pathData="%s"' % d]
        if style["fill"] != "none":
            attrs.append('android:fillColor="%s"' % color(style["fill"]))
        if style.get("fill-rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        if "opacity" in style:
            attrs.append('android:fillAlpha="%s"' % style["opacity"])
            attrs.append('android:strokeAlpha="%s"' % style["opacity"])
        if style.get("stroke", "none") != "none":
            attrs.append('android:strokeColor="%s"' % color(style["stroke"]))
            attrs.append('android:strokeWidth="%s"' % style.get("stroke-width", "1"))
            attrs.append('android:strokeLineCap="%s"' % style.get("stroke-linecap", "butt"))
            attrs.append('android:strokeLineJoin="%s"' % style.get("stroke-linejoin", "miter"))
        out.append("    <path\n        " + "\n        ".join(attrs) + " />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def main(src, dst):
    os.makedirs(dst, exist_ok=True)
    for side, side_name in COLORS.items():
        for letter, kind in KINDS.items():
            with open(os.path.join(dst, "piece_%s_%s.xml" % (side_name, kind)), "w") as f:
                f.write(convert(os.path.join(src, side + letter + ".svg")))


if __name__ == "__main__":
    main(*sys.argv[1:3])
