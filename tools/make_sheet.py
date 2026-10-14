# -*- coding: utf-8 -*-
"""
把「原图 / 白底 / 蓝底 / 红底」拼成一张对比图（本地评审用）。
用法: python make_sheet.py --variant nobeauty [--out output-debug/compare_nobeauty.png]
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
]


def load_font(size):
    for f in FONT_CANDIDATES:
        if os.path.exists(f):
            try:
                return ImageFont.truetype(f, size)
            except Exception:
                continue
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="nobeauty", choices=["nobeauty", "beauty"])
    ap.add_argument("--dir", default="output-debug")
    ap.add_argument("--out", default="")
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    base = os.path.abspath(args.dir)
    titles = ["原图（裁剪）", "白底 #FFFFFF", "蓝底 #438EDB", "红底 #B80202"]
    paths = [
        os.path.join(base, "person_crop_2x.png"),
        os.path.join(base, f"{args.variant}_white_2x.png"),
        os.path.join(base, f"{args.variant}_blue_2x.png"),
        os.path.join(base, f"{args.variant}_red_2x.png"),
    ]
    imgs = [Image.open(p).convert("RGB") for p in paths]
    pw, ph = imgs[0].size

    gap = 16
    bar = 54
    pad = 20
    title_h = 60
    W = pad * 2 + pw * 4 + gap * 3
    H = pad * 2 + title_h + bar + ph + (30 if args.label else 0)

    sheet = Image.new("RGB", (W, H), (245, 246, 248))
    d = ImageDraw.Draw(sheet)
    f_title = load_font(30)
    f_sub = load_font(20)
    f_head = load_font(26)

    y_bar = pad
    if args.label:
        d.rectangle([0, 0, W, 30 + bar], fill=(255, 255, 255))
        d.text((pad, 14), args.label, font=f_head, fill=(20, 20, 20))
        y_bar = pad + 30

    y_img = y_bar + title_h
    x = pad
    for i, (t, im) in enumerate(zip(titles, imgs)):
        d.text((x + 4, y_bar + 16), t, font=f_title, fill=(30, 30, 30))
        sheet.paste(im, (x, y_img))
        d.rectangle([x, y_img, x + pw - 1, y_img + ph - 1], outline=(205, 208, 214), width=1)
        if i < 3:
            d.text((x + pw + 2, y_img + ph // 2), ">", font=f_sub, fill=(160, 160, 160))
        x += pw + gap

    out = args.out or os.path.join(base, f"compare_{args.variant}.png")
    sheet.save(out)
    print("saved:", os.path.abspath(out), sheet.size)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
