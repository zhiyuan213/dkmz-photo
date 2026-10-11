# -*- coding: utf-8 -*-
"""
美颜 JS 实现验证 —— 数据准备与结果拼图
流程：
  1. prepare: 读图 -> 导出 raw RGBA + alpha + face(输出坐标) 供 Node 跑 beauty.js
  2. collect: 把 Node 输出的 raw 转回 PNG 并拼对比图
"""
import sys
import os
import json
import numpy as np
import cv2

OUT = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(OUT, "..", "output-verify")


def prepare(src, dst_dir):
    img = cv2.imread(src)
    h, w = img.shape[:2]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    rgb.tofile(os.path.join(dst_dir, "beauty_in.raw"))

    # 人脸框（Haar），并映射到输出坐标（此处裁剪图即原图，比例 1:1）
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    cc = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    faces = cc.detectMultiScale(gray, 1.1, 5, minSize=(60, 60))
    x, y, fw, fh = max(faces, key=lambda f: f[2] * f[3])
    face = {"left": int(x), "top": int(y), "width": int(fw), "height": int(fh)}

    # 人像 alpha：GrabCut 模拟 MODNet 输出
    gc = np.zeros((h, w), np.uint8)
    bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    rect = (max(0, x - int(fw * 0.35)), max(0, y - int(fh * 0.75)),
            min(w, x + fw + int(fw * 0.35)) - max(0, x - int(fw * 0.35)),
            min(h, y + fh + int(fh * 1.2)) - max(0, y - int(fh * 0.75)))
    rect = (rect[0], rect[1], min(rect[2], w - 8), min(rect[3], h - 8))
    cv2.grabCut(img, gc, rect, bgd, fgd, 5, cv2.GC_INIT_WITH_RECT)
    alpha = np.where((gc == cv2.GC_FGD) | (gc == cv2.GC_PR_FGD), 1.0, 0.0).astype(np.float32)
    alpha = cv2.GaussianBlur(alpha, (5, 5), 0)
    alpha.tofile(os.path.join(dst_dir, "beauty_alpha.raw"))

    with open(os.path.join(dst_dir, "beauty_meta.json"), "w") as f:
        json.dump({"w": w, "h": h, "face": face}, f)
    print(f"prepare: {w}x{h} face={face}")


def collect(dst_dir, variants):
    meta = json.load(open(os.path.join(dst_dir, "beauty_meta.json")))
    w, h = meta["w"], meta["h"]
    base = cv2.cvtColor(np.fromfile(os.path.join(dst_dir, "beauty_in.raw"), np.uint8).reshape(h, w, 3),
                        cv2.COLOR_RGB2BGR)

    from PIL import Image, ImageDraw, ImageFont
    font = None
    for fp in ["C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"]:
        if os.path.exists(fp):
            font = ImageFont.truetype(fp, 20)
            break

    imgs = [("原图", base)]
    for name, label in variants:
        raw = os.path.join(dst_dir, f"beauty_out_{name}.raw")
        if not os.path.exists(raw):
            print("缺:", raw)
            continue
        arr = np.fromfile(raw, np.uint8).reshape(h, w, 4)[:, :, :3]
        bgr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
        imgs.append((label, bgr))
        print(f"{label}: {raw}")

    pad, label_h = 14, 40
    total_w = sum(im.shape[1] for _, im in imgs) + pad * (len(imgs) + 1)
    canvas = np.full((h + pad * 2 + label_h, total_w, 3), 255, np.uint8)
    x = pad
    for _, im in imgs:
        canvas[pad:pad + im.shape[0], x:x + im.shape[1]] = im
        x += im.shape[1] + pad
    pil = Image.fromarray(cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(pil)
    x = pad
    for label, im in imgs:
        if font:
            bb = d.textbbox((0, 0), label, font=font)
            d.text((x + (im.shape[1] - (bb[2] - bb[0])) // 2, h + pad + 8), label, fill=(0, 0, 0), font=font)
        x += im.shape[1] + pad
    out = os.path.join(dst_dir, "beauty_compare.jpg")
    pil.save(out, quality=92)
    print("对比图 ->", out)


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "prepare":
        prepare(sys.argv[2], TMP)
    elif mode == "collect":
        variants = [
            ("l1", "美白轻"),
            ("l2", "美中"),
            ("l3", "美白强"),
            ("s1", "磨皮轻"),
            ("s2", "磨皮中"),
            ("h2", "黑发中"),
        ]
        collect(TMP, variants)
