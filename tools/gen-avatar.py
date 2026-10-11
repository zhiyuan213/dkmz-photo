# -*- coding: utf-8 -*-
"""
生成小程序头像（登科喵证件照）。

输入：带白边的方形图标（AI 生成），输出：512×512 干净版头像——
  1) 裁掉外圈白边并居中成正方形
  2) 去掉四角「取景框」白色角标（按连通域识别，原地用邻近蓝色渐变平面拟合回填）
  3) 圆角方框之外的四角白区同样回填为蓝色（圆裁/方裁都不露白）

用法：python tools/gen-avatar.py <输入图> <输出图>
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

WHITE = 205          # 近白阈值（三通道均 > WHITE 视为白色前景）
OUT = 512           # 输出尺寸


def trim_white(im: Image.Image) -> Image.Image:
    """裁掉四周白边，并把内容居中放到正方形画布上"""
    a = np.asarray(im.convert('RGB')).astype(np.int16)
    nonwhite = ~((a[:, :, 0] > 240) & (a[:, :, 1] > 240) & (a[:, :, 2] > 240))
    rows = np.any(nonwhite, axis=1)
    cols = np.any(nonwhite, axis=0)
    r0, r1 = int(np.argmax(rows)), int(len(rows) - np.argmax(rows[::-1]))
    c0, c1 = int(np.argmax(cols)), int(len(cols) - np.argmax(cols[::-1]))
    icon = im.convert('RGB').crop((c0, r0, c1, r1))
    w, h = icon.size
    side = max(w, h)
    sq = Image.new('RGB', (side, side), (255, 255, 255))
    sq.paste(icon, ((side - w) // 2, (side - h) // 2))
    return sq


def fill_plane_fit(rgb: np.ndarray, mask: np.ndarray):
    """
    用平面拟合回填 mask 区域：拟合样本只取「蓝色背景」像素（B 通道明显高于 R、
    且非 mask），避免白色前景（照片框/相机）污染拟合结果。
    """
    h, w = mask.shape
    r, g, b = rgb[:, :, 0].astype(np.int16), rgb[:, :, 1].astype(np.int16), rgb[:, :, 2].astype(np.int16)
    blueish = (b > r + 25) & (b > 140) & ~mask
    ys, xs = np.nonzero(blueish)
    if len(ys) < 10:
        blueish = ~mask                      # 兜底：全用非 mask 像素
        ys, xs = np.nonzero(blueish)
    A = np.stack([xs, ys, np.ones_like(xs)], axis=1).astype(np.float64)
    coef = np.linalg.lstsq(A, rgb[blueish].astype(np.float64), rcond=None)[0]

    my, mx = np.nonzero(mask)
    B = np.stack([mx, my, np.ones_like(mx)], axis=1).astype(np.float64)
    vals = np.clip(B @ coef, 0, 255).astype(np.uint8)
    rgb[my, mx] = vals


def clean(sq: Image.Image) -> Image.Image:
    rgb = np.asarray(sq).astype(np.uint8).copy()
    h, w = rgb.shape[:2]

    a = rgb.astype(np.int16)
    white = (a[:, :, 0] > 185) & (a[:, :, 1] > 185) & (a[:, :, 2] > 185)

    # 全图白色连通域分类：大块/贴边的是主体（照片框、相机、外圈描边、圆角白区）→ 保护
    labels, n = ndimage.label(white)
    protected = np.zeros((h, w), dtype=bool)
    border = np.zeros((h, w), dtype=bool)
    border[:2, :] = border[-2:, :] = True
    border[:, :2] = border[:, -2:] = True
    for i in range(1, n + 1):
        m = labels == i
        ys, xs = np.nonzero(m)
        bw, bh = xs.max() - xs.min(), ys.max() - ys.min()
        if bw > 0.4 * w or bh > 0.4 * h or (m & border).any():
            protected |= m

    # 角标 = 角部区域内的白色、非保护像素
    corner = 0.32
    region = np.zeros((h, w), dtype=bool)
    cy, cx = int(h * corner), int(w * corner)
    region[:cy, :cx] = region[:cy, w - cx:] = True
    region[h - cy:, :cx] = region[h - cy:, w - cx:] = True
    to_fill = white & ~protected & region

    # 膨胀 4px 盖住柔和阴影/抗锯齿边，但绝不越入保护区
    to_fill = ndimage.binary_dilation(to_fill, iterations=6) & ~protected

    # 按连通域逐个扩边回填
    flabels, fn = ndimage.label(to_fill)
    for i in range(1, fn + 1):
        m = flabels == i
        ys, xs = np.nonzero(m)
        pad = 28
        y0, y1 = max(0, ys.min() - pad), min(h, ys.max() + pad + 1)
        x0, x1 = max(0, xs.min() - pad), min(w, xs.max() + pad + 1)
        sub = rgb[y0:y1, x0:x1]
        sub_mask = to_fill[y0:y1, x0:x1]
        fill_plane_fit(sub, sub_mask)
        rgb[y0:y1, x0:x1] = sub

    return Image.fromarray(rgb)


def main():
    src = sys.argv[1]
    dst = sys.argv[2]
    sq = trim_white(Image.open(src))
    out = clean(sq).resize((OUT, OUT), Image.LANCZOS)
    out.save(dst)
    print('已生成:', dst, out.size)


if __name__ == '__main__':
    main()
