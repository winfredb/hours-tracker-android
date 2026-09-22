#!/usr/bin/env python3
"""Widget REDESIGN candidates (v4) — four structurally different layouts.

Renders each at TRUE device size (dp x density, supersampled x3) and writes both
individual PNGs and a numbered sheet. Nothing here is an attribute tweak of the
previous design: each candidate arranges information differently and uses a
different control shape.

    /opt/data/.mockvenv/bin/python widget_redesign_render.py --probe
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFont

SS = 3
PANEL_BG = (27, 31, 29)
WALLPAPER = (33, 45, 55)
ON_SURFACE = (225, 227, 222)
MUTED = (167, 176, 171)
FAINT = (46, 53, 50)
TEAL = (35, 92, 82)
BRICK = (94, 47, 44)
RADIUS_DP = 24
NAME, EMPLOYER, HOURS = "KITCHEN REMODEL", "ACME Construction", "4h 12m"

FONTS = [
    ("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf", "bold"),
    ("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", "regular"),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "bold"),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "regular"),
]


def font(style, px):
    for path, kind in FONTS:
        if kind == style and os.path.exists(path):
            return ImageFont.truetype(path, int(px))
    raise SystemExit("no usable TTF")


def width_dp(text, size_sp, style, S):
    f = font(style, size_sp * S)
    bb = f.getbbox(text)
    return (bb[2] - bb[0]) / S


# ------------------------------------------------------------------ candidates
def d1_symmetric(d, S, W, H):
    """1. SYMMETRIC STACK - everything centred, controls centred at the foot."""
    cx = W / 2
    c = lambda y: y * S
    d.text((cx * S, c(23)), NAME, font=font("bold", 11 * S), fill=ON_SURFACE, anchor="mm")
    d.text((cx * S, c(37)), EMPLOYER, font=font("regular", 11 * S), fill=MUTED, anchor="mm")
    d.text((cx * S, c(59)), HOURS, font=font("bold", 30 * S), fill=ON_SURFACE, anchor="mm")
    btn, gap, cy = 40, 12, 98
    for i, col in enumerate((TEAL, BRICK)):
        x = cx - (btn + gap) / 2 + i * (btn + gap)
        d.ellipse([(x - btn / 2) * S, (cy - btn / 2) * S,
                   (x + btn / 2) * S, (cy + btn / 2) * S], fill=col)
    return {"name": (W - 32, NAME, 11, "bold"), "employer": (W - 32, EMPLOYER, 11, "regular"),
            "hours": (W - 32, HOURS, 30, "bold")}


def d2_stripe(d, S, W, H):
    """2. ACCENT STRIPE - state colour down the edge, controls stacked on the right."""
    d.rounded_rectangle([10 * S, 22 * S, 16 * S, 112 * S], radius=3 * S, fill=TEAL)
    d.text((28 * S, 48 * S), HOURS, font=font("bold", 30 * S), fill=ON_SURFACE, anchor="lm")
    d.text((28 * S, 80 * S), NAME, font=font("bold", 12 * S), fill=ON_SURFACE, anchor="lm")
    d.text((28 * S, 96 * S), EMPLOYER, font=font("regular", 11 * S), fill=MUTED, anchor="lm")
    btn, cx = 42, W - 16 - 21
    for i, col in enumerate((TEAL, BRICK)):
        cy = 42 + i * 50
        d.ellipse([(cx - btn / 2) * S, (cy - btn / 2) * S,
                   (cx + btn / 2) * S, (cy + btn / 2) * S], fill=col)
    budget = (cx - btn / 2) - 28 - 8
    return {"hours": (budget, HOURS, 30, "bold"), "name": (budget, NAME, 12, "bold"),
            "employer": (budget, EMPLOYER, 11, "regular")}


def d3_panels(d, S, W, H):
    """3. TWO PANELS - hairline divider; time one side, the job the other."""
    d.rectangle([116 * S, 24 * S, 117 * S, 110 * S], fill=FAINT)
    d.text((16 * S, 44 * S), "TODAY", font=font("bold", 9 * S), fill=MUTED, anchor="lm")
    d.text((16 * S, 74 * S), HOURS, font=font("bold", 26 * S), fill=ON_SURFACE, anchor="lm")
    d.text((130 * S, 44 * S), NAME, font=font("bold", 10 * S), fill=ON_SURFACE, anchor="lm")
    d.text((130 * S, 62 * S), EMPLOYER, font=font("regular", 10 * S), fill=MUTED, anchor="lm")
    btn, cy = 36, 95
    for i, col in enumerate((BRICK, TEAL)):
        cx = (237 - 18) - i * (btn + 8)
        d.ellipse([(cx - btn / 2) * S, (cy - btn / 2) * S,
                   (cx + btn / 2) * S, (cy + btn / 2) * S], fill=col)
    return {"hours": (116 - 16 - 8, HOURS, 26, "bold"), "name": (237 - 130, NAME, 10, "bold"),
            "employer": (237 - 130, EMPLOYER, 10, "regular")}


def d4_pills(d, S, W, H):
    """4. PILL CONTROLS - taller pill buttons instead of circles, job detail left."""
    d.text((16 * S, 32 * S), NAME, font=font("bold", 12 * S), fill=ON_SURFACE, anchor="lm")
    d.text((16 * S, 62 * S), HOURS, font=font("bold", 28 * S), fill=ON_SURFACE, anchor="lm")
    d.text((16 * S, 92 * S), EMPLOYER, font=font("regular", 11 * S), fill=MUTED, anchor="lm")
    pw, ph, px1 = 74, 34, W - 16 - 74
    for i, col in enumerate((TEAL, BRICK)):
        y0 = 30 + i * 44
        d.rounded_rectangle([px1 * S, y0 * S, (px1 + pw) * S, (y0 + ph) * S],
                            radius=(ph / 2) * S, fill=col)
    return {"name": (px1 - 16 - 10, NAME, 12, "bold"), "hours": (px1 - 16 - 10, HOURS, 28, "bold"),
            "employer": (px1 - 16 - 10, EMPLOYER, 11, "regular")}


DESIGNS = [
    ("1. SYMMETRIC STACK", d1_symmetric),
    ("2. ACCENT STRIPE", d2_stripe),
    ("3. TWO PANELS", d3_panels),
    ("4. PILL CONTROLS", d4_pills),
]


def panel(fn, W, H, density):
    S = int(density * SS)
    img = Image.new("RGB", (W * S, H * S), WALLPAPER)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W * S - 1, H * S - 1], radius=RADIUS_DP * S, fill=PANEL_BG)
    budgets = fn(d, S, W, H) or {}
    return img.resize((int(W * density), int(H * density)), Image.LANCZOS), S, budgets


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--w-dp", type=int, default=253)
    ap.add_argument("--h-dp", type=int, default=134)
    ap.add_argument("--density", type=float, default=3.0)
    ap.add_argument("--out", default="/tmp/widget_redesign")
    ap.add_argument("--probe", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    imgs, budgets, S = [], {}, a.density * SS
    for title, fn in DESIGNS:
        im, S, b = panel(fn, a.w_dp, a.h_dp, a.density)
        imgs.append((title, im))
        budgets[title] = b
        im.save(f"{a.out}/{title[0]}.png")
        print(f"{a.out}/{title[0]}.png  {im.size[0]}x{im.size[1]}px")

    if a.probe:
        print("\n--- text budgets (dp) ---")
        for title, b in budgets.items():
            for slot, (budget, text, size, style) in b.items():
                w = width_dp(text, size, style, S)
                print(f"{'OK  ' if w <= budget else 'OVER'} {title:18s} {slot:8s} "
                      f"{size}sp {text!r} -> {w:.0f}dp (budget {budget:.0f}dp)")
        print("\n--- panel pixels ---")
        for title, im in imgs:
            px = im.load()
            w, h = im.size
            print(f"{title:18s} center={px[w//2, h//2]} corner={px[2, 2]} "
                  f"topedge={px[w//2, 1]} corner_rounded={px[2, 2] != PANEL_BG}")

    # numbered sheet, 2 columns
    tw, th = int(a.w_dp * a.density), int(a.h_dp * a.density)
    gap, title_h = 26, 52
    sheet = Image.new("RGB", (gap + 2 * (tw + gap), gap + 2 * (title_h + th + gap)), (18, 18, 20))
    sd = ImageDraw.Draw(sheet)
    big = ImageFont.truetype(FONTS[0][0], 26)
    for i, (title, im) in enumerate(imgs):
        row, col = divmod(i, 2)
        ox, oy = gap + col * (tw + gap), gap + row * (title_h + th + gap) + title_h
        sheet.paste(im, (ox, oy))
        sd.text((ox, oy - title_h + 12), title, font=big, fill=(235, 235, 235))
    sheet.save(f"{a.out}/sheet.png")
    print(f"\n{a.out}/sheet.png  {sheet.size[0]}x{sheet.size[1]}px")


if __name__ == "__main__":
    main()