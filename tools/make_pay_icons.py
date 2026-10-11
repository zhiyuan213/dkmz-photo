# -*- coding: utf-8 -*-
"""生成虚拟支付「道具管理」用的道具图片（200x200，<200KB）。

用法：python tools/make_pay_icons.py
输出：docs/pay-icons/photo1.png / photo3.png / vip1.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "pay-icons")
SIZE = 200
BLUE = (13, 96, 248)        # 品牌主蓝 #0D60F8
BLUE_SOFT = (233, 242, 253) # #E9F2FD
NAVY = (31, 43, 67)         # #1F2B43
GOLD = (240, 185, 60)
WHITE = (255, 255, 255)


def font(size):
    for p in ("C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def rounded_bg(color):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([6, 6, SIZE - 6, SIZE - 6], radius=36, fill=color)
    return img, d


def person(d, cx, cy, s, color):
    """人像剪影：头 + 肩"""
    d.ellipse([cx - s * 0.28, cy - s * 0.62, cx + s * 0.28, cy - s * 0.06], fill=color)
    d.pieslice([cx - s * 0.52, cy - s * 0.02, cx + s * 0.52, cy + s * 0.94], 180, 360, fill=color)


def badge(img, text, right=True):
    d = ImageDraw.Draw(img)
    f = font(26)
    tw = d.textbbox((0, 0), text, font=f)[2]
    pad = 12
    x1 = SIZE - 22 - tw - pad * 2 if right else 22
    x0 = x1 if right else x1
    box = [x0 - pad, SIZE - 64, x0 + tw + pad, SIZE - 22]
    d.rounded_rectangle(box, radius=14, fill=WHITE)
    d.text((box[0] + pad, SIZE - 66), text, font=f, fill=BLUE)
    return img


def icon_photo1():
    img, d = rounded_bg(BLUE)
    # 白色相框 + 人像
    d.rounded_rectangle([48, 34, 152, 150], radius=14, fill=WHITE)
    person(d, 100, 104, 84, BLUE)
    # 相框底边留一条“照片”白条
    d.rectangle([60, 132, 140, 142], fill=BLUE_SOFT)
    return badge(img, "1张")


def icon_photo3():
    img, d = rounded_bg(BLUE)
    # 三张错开的相框
    for i, dx in enumerate((0, 16, 32)):
        top = 44 - (i % 2) * 6
        d.rounded_rectangle([38 + dx, top, 122 + dx, top + 104], radius=12,
                            fill=WHITE if i == 2 else (219, 231, 250))
    person(d, 132, 108, 72, BLUE)
    return badge(img, "3张")


def icon_vip():
    img, d = rounded_bg(NAVY)
    # 金色皇冠
    d.polygon([(58, 84), (72, 52), (92, 76), (100, 44), (108, 76), (128, 52), (142, 84)], fill=GOLD)
    d.rectangle([58, 84, 142, 98], fill=GOLD)
    for gx in (72, 100, 128):
        d.ellipse([gx - 5, 44 if gx == 100 else 48, gx + 5, 54 if gx == 100 else 58], fill=GOLD)
    # 金色大字「年卡」
    f = font(52)
    d.text((100, 118), "年卡", font=f, fill=GOLD, anchor="ma")
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in (("photo1", icon_photo1), ("photo3", icon_photo3), ("vip1", icon_vip)):
        p = os.path.join(OUT, name + ".png")
        img = fn()  # 保留 RGBA，圆角外透明
        img.save(p, "PNG", optimize=True)
        print(p, os.path.getsize(p), "bytes")


if __name__ == "__main__":
    main()
