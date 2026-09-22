#!/usr/bin/env python3
"""Render the SHIPPING widget layout XML at true device size.

Unlike tools/widget_redesign_render.py (which draws candidate designs from
hand-coded coordinates), this reads res/layout/widget_3x1.xml itself and lays it
out with a small subset of RelativeLayout/LinearLayout rules. Use it to catch
transcription slips between an approved mockup and the real XML - a preview that
is generated from the mockup code proves nothing about what ships.

Supported rules: layout_margin{Start,End,Top}, layout_centerVertical,
layout_alignParentEnd, orientation=vertical, wrap/fixed sizes, textSize,
textStyle, textColor, background (shape solid color), shape="oval".

Usage:  python3 tools/widget_xml_render.py [out.png] [--state running|idle]
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parent.parent
LAYOUT = REPO / "app/src/main/res/layout/widget_3x1.xml"
DRAWABLES = REPO / "app/src/main/res/drawable"

PANEL_W, PANEL_H = 253.0, 134.0   # Pixel 9 Pro XL 3x1 cell
S = 3                              # device density
FONT_BOLD = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"

A = "{http://schemas.android.com/apk/res/android}"


def dp(v, default=None):
    if v is None:
        return default
    m = re.match(r"(-?[\d.]+)dp", v)
    return float(m.group(1)) if m else default


def color_of(drawable_name):
    """Pull the solid colour + shape out of a shape drawable."""
    p = DRAWABLES / f"{drawable_name}.xml"
    if not p.exists():
        return (255, 0, 255), "rectangle"
    t = p.read_text()
    c = re.search(r'<solid\s+android:color="(#[0-9A-Fa-f]{6,8})"', t)
    shape = re.search(r'android:shape="(\w+)"', t)
    hexv = (c.group(1) if c else "#FF00FF").lstrip("#")
    rgb = tuple(int(hexv[-6:][i:i + 2], 16) for i in (0, 2, 4))
    return rgb, (shape.group(1) if shape else "rectangle")


def font(style, sp):
    path = FONT_BOLD if style == "bold" else FONT_REG
    return ImageFont.truetype(path, int(round(sp * S)))


def text_h(sp):
    return sp * 1.17          # Roboto line box, good enough at these sizes


def resolve_background(el):
    bg = el.get(A + "background", "")
    m = re.match(r"@drawable/(\w+)", bg)
    if not m:
        return None
    return color_of(m.group(1))


def render(state="running", out="/tmp/widget_xml.png"):
    root = ET.parse(LAYOUT).getroot()
    W, H = int(PANEL_W * S), int(PANEL_H * S)
    img = Image.new("RGB", (W, H), (33, 45, 55))          # wallpaper behind
    d = ImageDraw.Draw(img, "RGBA")

    # --- root panel (rounded rect, 24dp, declared in XML) ---
    bg = resolve_background(root)
    if bg:
        rgb, _ = bg
        d.rounded_rectangle([0, 0, W - 1, H - 1], radius=24 * S, fill=rgb)

    report = []

    def draw_text(el, x0, yc, col_override=None):
        txt = el.get(A + "text", "")
        if not txt:
            return
        sp = dp(el.get(A + "textSize", "12sp").replace("sp", "dp"), 12.0)
        style = "bold" if el.get(A + "textStyle") else "regular"
        col = col_override
        if col is None:
            hc = el.get(A + "textColor", "#FFFFFFFF").lstrip("#")
            col = tuple(int(hc[-6:][i:i + 2], 16) for i in (0, 2, 4))
        f = font(style, sp)
        d.text((x0 * S, yc * S), txt, font=f, fill=col, anchor="lm")
        bbox = d.textbbox((0, 0), txt, font=f)
        report.append(f"    {el.get(A + 'id', '?').split('/')[-1]:<13} {txt!r:<20} "
                      f"x={x0:.0f}dp  w={(bbox[2] - bbox[0]) / S:.0f}dp  {sp:.0f}sp {style}")

    for el in root:
        tag = el.tag.split("}")[-1]
        eid = el.get(A + "id", "").split("/")[-1]
        vis = el.get(A + "visibility", "visible")
        w = dp(el.get(A + "layout_width"), None)
        h = dp(el.get(A + "layout_height"), None)
        ms = dp(el.get(A + "layout_marginStart"), 0.0)
        me = dp(el.get(A + "layout_marginEnd"), 0.0)

        # --- single fixed-size View (the state stripe) ---
        if tag == "View":
            # Two sibling stripes exist so the provider can switch state with
            # setViewVisibility alone. Exactly one is visible, so the preview must
            # pick by STATE first; the static android:visibility in the XML only
            # decides what a never-updated widget shows.
            shows = {"running": "stateStripeOn", "idle": "stateStripeOff"}[state]
            if eid != shows:
                report.append(f"    {eid:<13} (gone)")
                continue
            rgb, _ = color_of(el.get(A + "background").split("/")[-1])
            y0 = (PANEL_H - h) / 2 if el.get(A + "layout_centerVertical") else 0
            d.rounded_rectangle([ms * S, y0 * S, (ms + w) * S, (y0 + h) * S],
                                radius=3 * S, fill=rgb)
            report.append(f"    {eid:<13} stripe {w:.0f}x{h:.0f}dp at x={ms:.0f} y={y0:.0f} {rgb}")
            continue

        # --- vertical LinearLayout ---
        kids = [k for k in el]
        line_h = []
        for k in kids:
            if k.get(A + "visibility") == "gone":
                line_h.append(None)
                continue
            sp = dp(k.get(A + "textSize", "12sp").replace("sp", "dp"), 12.0)
            mt = dp(k.get(A + "layout_marginTop"), 0.0)
            # A child with an explicit layout_height (the 42dp circles) is not
            # text-sized; only bare TextViews fall back to their line box.
            kh = dp(k.get(A + "layout_height"), None)
            line_h.append(((kh if kh else text_h(sp)), mt))
        total = sum(hh + mt for hh, mt in line_h if hh is not None)
        y0 = (PANEL_H - total) / 2 if el.get(A + "layout_centerVertical") else 0

        if el.get(A + "layout_alignParentEnd"):           # the controls stack
            # Stack width is wrap_content, so take it from the widest child.
            stack_w = max(dp(k.get(A + "layout_width"), 0.0) for k in kids)
            x0 = PANEL_W - me - stack_w
            report.append(f"    {eid:<13} stack x={x0:.0f}..{PANEL_W - me:.0f}dp "
                          f"y={y0:.0f}..{y0 + total:.0f}dp")
            y = y0
            for k, lh in zip(kids, line_h):
                if lh is None:
                    continue
                kh = dp(k.get(A + "layout_height"), 0.0)
                kw = dp(k.get(A + "layout_width"), 0.0)
                rgb, shape = color_of(k.get(A + "background").split("/")[-1])
                y += lh[1]
                box = [x0 * S, y * S, (x0 + kw) * S, (y + kh) * S]
                if shape == "oval":
                    d.ellipse(box, fill=rgb)
                else:
                    d.rounded_rectangle(box, radius=16 * S, fill=rgb)
                txt = k.get(A + "text", "")
                f = font("regular", 17.0)
                d.text(((x0 + kw / 2) * S, (y + kh / 2) * S), txt, font=f,
                       fill=(255, 255, 255), anchor="mm")
                report.append(f"    {k.get(A + 'id').split('/')[-1]:<13} "
                              f"{'circle' if shape == 'oval' else 'rounded'} {kw:.0f}x{kh:.0f}dp "
                              f"at x={x0:.0f} y={y:.0f} glyph={txt!r} fill={rgb}")
                y += kh
            continue

        # the info column
        col_w = PANEL_W - ms - me
        report.append(f"    {eid:<13} column x={ms:.0f}..{PANEL_W - me:.0f}dp "
                      f"y={y0:.0f}..{y0 + total:.0f}dp  (text box {col_w:.0f}dp)")
        y = y0
        for k, lh in zip(kids, line_h):
            if lh is None:
                report.append(f"    {k.get(A + 'id').split('/')[-1]:<13} (gone)")
                continue
            y += lh[1]
            draw_text(k, ms, y + lh[0] / 2)
            y += lh[0]

    img.save(out)
    print(f"=== rendered from {LAYOUT.relative_to(REPO)} (state={state}) -> {out}  {W}x{H}px")
    print("\n".join(report))
    return report


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    st = "idle" if "--state=idle" in sys.argv else "running"
    render(st, args[0] if args else "/tmp/widget_xml.png")
