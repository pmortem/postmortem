#!/usr/bin/env python3
"""Converts the cburnett piece SVGs (Colin M.L. Burnett, GPLv2+, as shipped by Lichess) to Android VectorDrawables.

Usage: svg2vector.py <input-dir-with-wK.svg...> <output-dir>
Handles what those files use: nested <g> with inherited presentation attributes, <path> and <circle>.
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
    if tag in ("g", "svg"):
        for child in node:
            yield from shapes(child, style)
    elif tag == "path":
        yield node.attrib["d"], style
    elif tag == "circle":
        cx, cy, r = (float(node.attrib[k]) for k in ("cx", "cy", "r"))
        d = "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0z" % (fmt(cx - r), fmt(cy), fmt(r), fmt(r), fmt(2 * r), fmt(r), fmt(r), fmt(-2 * r))
        yield d, style


def convert(svg_path):
    root = ET.parse(svg_path).getroot()
    size = root.attrib["viewBox"].split()[2]
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="%sdp"' % size,
           '    android:height="%sdp"' % size,
           '    android:viewportWidth="%s"' % size,
           '    android:viewportHeight="%s">' % size]
    for d, style in shapes(root, {"fill": "#000"}):
        attrs = ['android:pathData="%s"' % d]
        if style["fill"] != "none":
            attrs.append('android:fillColor="%s"' % color(style["fill"]))
        if style.get("fill-rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
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
