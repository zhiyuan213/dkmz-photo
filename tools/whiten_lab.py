# -*- coding: utf-8 -*-
"""
美白算法方案对比：同一张脸跑 5 种主流美白思路，横向对比给用户挑。
所有方案都限定在皮肤区域内（YCbCr + 分域，避免衣服误判）。
"""
import sys
import os
import json
import numpy as np
import cv2

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "output-verify")


def skin_mask(img, face):
    """肤色 mask（含分域，避开衣领）"""
    h, w = img.shape[:2]
    ycrcb = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb).astype(np.int16)
    cr, cb = ycrcb[:, :, 1], ycrcb[:, :, 2]  # OpenCV YCrCb: [Y, Cr, Cb]
    m = (cb >= 77) & (cb <= 127) & (cr >= 133) & (cr <= 173) & ((cr - cb) > 28)
    m = m.astype(np.float32)
    if face:
        x, y, fw, fh = face["left"], face["top"], face["width"], face["height"]
        chin = y + fh
        neck_end = min(h, int(y + fh * 1.3))
        m[neck_end:] = 0
        for yy in range(int(chin), neck_end):
            m[yy, :max(0, int(x + fw * 0.18))] = 0
            m[yy, min(w, int(x + fw * 0.82)):] = 0
    m = cv2.GaussianBlur(m, (9, 9), 0)
    return m


def apply_mask(img, out, mask):
    m = mask[..., None]
    return img.astype(np.float32) * (1 - m) + out.astype(np.float32) * m


# ---------------- 方案 A：提亮 + 去黄（Lab b 通道）/ 保留红润（a 通道）----------
def style_A(img, mask, level=2):
    P = [(1.0, 0, 0), (0.80, 4, 1), (0.68, 8, 2), (0.58, 11, 3)][level]
    k, dy, da = P
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)
    L, A, B = lab[:, :, 0], lab[:, :, 1], lab[:, :, 2]
    L2 = 255 - (255 - L) * k          # 提亮（soft-clip，暗部多提）
    B2 = np.clip(B - dy, 0, 255)      # 去黄（黄-蓝轴）
    A2 = np.clip(A + da, 0, 255)      # 微增红润
    lab2 = np.stack([L2, A2, B2], axis=2)
    out = cv2.cvtColor(lab2.astype(np.uint8), cv2.COLOR_LAB2BGR)
    return apply_mask(img, out, mask)


# ---------------- 方案 B：MSR Retinex 光照补偿 ----------------
def style_B(img, mask, level=2):
    s = [0.0, 0.45, 0.65, 0.85][level]
    f = img.astype(np.float32) + 1.0
    ret = np.zeros_like(f)
    for sigma in (15, 80, 250):
        blur = cv2.GaussianBlur(f, (0, 0), sigma)
        ret += np.log(f) - np.log(blur)
    ret /= 3.0
    # 归一化（按整体统计，避免局部拉伸）
    lo, hi = np.percentile(ret, 1), np.percentile(ret, 99)
    norm = np.clip((ret - lo) / max(1e-6, hi - lo), 0, 1) * 255
    out = norm.astype(np.uint8)
    out = img.astype(np.float32) * (1 - s) + out.astype(np.float32) * s
    return apply_mask(img, out.astype(np.uint8), mask)


# ---------------- 方案 C：灰世界白平衡 + 提亮 ----------------
def style_C(img, mask, level=2):
    s = [0.0, 0.5, 0.7, 0.9][level]
    skin_px = img[mask > 0.6].astype(np.float32)
    if len(skin_px) < 50:
        return img
    mean_bgr = skin_px.mean(axis=0)  # B,G,R
    gray = mean_bgr.mean()
    gain = gray / np.maximum(mean_bgr, 1.0)
    gain = 1.0 + (gain - 1.0) * s
    out = np.clip(img.astype(np.float32) * gain.reshape(1, 1, 3), 0, 255)
    # 顺带柔提亮
    out = np.clip(255 - (255 - out) * 0.85, 0, 255)
    return apply_mask(img, out.astype(np.uint8), mask)


# ---------------- 方案 D：当前实现（提亮 + 全通道去饱和）作对照 ----------------
def style_D(img, mask, level=2):
    K = [(1.0, 1.0), (0.76, 0.94), (0.62, 0.86), (0.52, 0.78)][level]
    k, sat = K
    ycrcb = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb).astype(np.float32)
    Y, Cb, Cr = ycrcb[:, :, 0], ycrcb[:, :, 1], ycrcb[:, :, 2]
    Y2 = 255 - (255 - Y) * k
    Cb2 = 128 + (Cb - 128) * sat
    Cr2 = 128 + (Cr - 128) * sat
    out = cv2.cvtColor(np.stack([Y2, Cb2, Cr2], axis=2).astype(np.uint8), cv2.COLOR_YCrCb2BGR)
    return apply_mask(img, out, mask)


# ---------------- 方案 E：A 的基础上加"通透感"（提亮后补局部对比）----------------
def style_E(img, mask, level=2):
    base = style_A(img, mask, level)
    # 局部对比增强（USM 的反向用法：只补一点点，避免发灰）
    amt = [0.0, 0.15, 0.25, 0.35][level]
    blur = cv2.GaussianBlur(base.astype(np.float32), (0, 0), 2.5)
    hi = base.astype(np.float32) - blur
    out = np.clip(base.astype(np.float32) + hi * amt, 0, 255)
    return apply_mask(img, out.astype(np.uint8), mask)


STYLES = [
    ("A", "A 提亮+去黄", style_A),
    ("B", "B Retinex", style_B),
    ("C", "C 白平衡", style_C),
    ("D", "D 当前(发灰)", style_D),
    ("E", "E 去黄+通透", style_E),
]


def main():
    meta = json.load(open(os.path.join(TMP, "beauty_meta.json")))
    h, w = meta["h"], meta["w"]
    rgb = np.fromfile(os.path.join(TMP, "beauty_in.raw"), np.uint8).reshape(h, w, 3)
    img = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    mask = skin_mask(img, meta["face"])

    from PIL import Image, ImageDraw, ImageFont
    font = None
    for fp in ["C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"]:
        if os.path.exists(fp):
            font = ImageFont.truetype(fp, 18)
            break

    # 每列：原图 + 各方案（中档）
    results = [("原图", img)]
    for key, name, fn in STYLES:
        out = fn(img, mask, 2)
        results.append((name, out))
        cv2.imwrite(os.path.join(TMP, f"whiten_{key}.jpg"), out, [cv2.IMWRITE_JPEG_QUALITY, 94])

    pad, lh = 12, 38
    total_w = sum(im.shape[1] for _, im in results) + pad * (len(results) + 1)
    canvas = np.full((h + pad * 2 + lh, total_w, 3), 255, np.uint8)
    x = pad
    for _, im in results:
        canvas[pad:pad + h, x:x + w] = im
        x += w + pad
    pil = Image.fromarray(cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(pil)
    x = pad
    for name, _ in results:
        if font:
            bb = d.textbbox((0, 0), name, font=font)
            d.text((x + (w - (bb[2] - bb[0])) // 2, h + pad + 6), name, fill=(0, 0, 0), font=font)
        x += w + pad
    out_path = os.path.join(TMP, "whiten_compare.jpg")
    pil.save(out_path, quality=93)

    # 皮肤区域平均色（量化各方案"白"的程度与偏色）
    print(f"{'方案':<14} {'皮肤均值(BGR)':<22} {'亮度Y':<8} {'黄度(R+G)/2-B':<12}")
    base_px = img[mask > 0.6].astype(np.float32)
    print(f"{'原图':<14} {str(base_px.mean(axis=0).round(1)):<22} "
          f"{base_px.mean():<8.1f} {((base_px[:,2]+base_px[:,1])/2 - base_px[:,0]).mean():<12.1f}")
    for key, name, fn in STYLES:
        out = fn(img, mask, 2)
        px = out[mask > 0.6].astype(np.float32)
        print(f"{name:<14} {str(px.mean(axis=0).round(1)):<22} "
              f"{px.mean():<8.1f} {((px[:,2]+px[:,1])/2 - px[:,0]).mean():<12.1f}")
    print("\n对比图 ->", out_path)


if __name__ == "__main__":
    main()
