# -*- coding: utf-8 -*-
"""
PC 端复刻 miniprogram 流水线做验证（与 utils/pipeline.js + utils/face.js 逻辑一致）
用法：
  python scripts/verify_pipeline.py <照片路径>
输出到 output-verify/：
  1_mask_raw.png    MODNet alpha 原文（清理前）
  2_mask_clean.png  越界清零 + 下半区残影清理后
  3_result_white/red/blue.jpg  三种底色合成结果
  控制台输出：人脸框 / 裁剪框 / 人脸占比 / 残影像素统计
"""
import sys
import os
import numpy as np
import cv2
import onnxruntime as ort
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, 'models', 'model.onnx')
OUT_DIR = os.path.join(ROOT, 'output-verify')

SHORT_SIDE = 384          # 与 makeIdPhoto 一致
GHOST_THRESHOLD = 0.45    # cleanGhostAlpha
GHOST_START_ROW = 0.58
FACE_H_RATIO = 0.45       # 通用证件照人脸占比（驾照 0.5）


def calc_crop_box(face, img_w, img_h, ratio):
    """复刻 face.js calcCropBox v2：横向收缩/夹取不造像素，纵向只允许顶部越界"""
    fx, fy, fw, fh_ = face
    cx = fx + fw / 2
    crop_h = fh_ / FACE_H_RATIO
    crop_w = crop_h * ratio
    if crop_w > img_w:
        crop_w = img_w
        crop_h = crop_w / ratio
    left = max(0.0, min(cx - crop_w / 2, img_w - crop_w))
    cy = fy + fh_ / 2 - crop_h * 0.06
    top = cy - crop_h / 2
    if top + crop_h > img_h:
        top = img_h - crop_h
    return dict(left=round(left), top=round(top), width=round(crop_w), height=round(crop_h))


def crop_and_scale(img, box, out_w, out_h):
    """复刻 pipeline.js cropAndScale：铺白 + 交集映射（横向/底部不越界，只有顶部可能）"""
    canvas = Image.new('RGB', (out_w, out_h), (255, 255, 255))
    iw, ih = img.size
    sx, sy = max(0, box['left']), max(0, box['top'])
    ex = min(iw, box['left'] + box['width'])
    ey = min(ih, box['top'] + box['height'])
    if ex > sx and ey > sy:
        scale_x = out_w / box['width']
        scale_y = out_h / box['height']
        dx = round((sx - box['left']) * scale_x)
        dy = round((sy - box['top']) * scale_y)
        dw = round((ex - sx) * scale_x)
        dh = round((ey - sy) * scale_y)
        region = img.crop((sx, sy, ex, ey)).resize((dw, dh), Image.LANCZOS)
        canvas.paste(region, (dx, dy))
    return canvas


def run_matting(pil_img):
    """复刻 matting.js：短边 384（32 对齐）、x/127.5-1、输出 [0,1] 直接作 alpha"""
    w, h = pil_img.size
    if w <= h:
        miw = SHORT_SIDE
        mih = round(h * SHORT_SIDE / w / 32) * 32
    else:
        mih = SHORT_SIDE
        miw = round(w * SHORT_SIDE / h / 32) * 32
    resized = pil_img.resize((miw, mih), Image.LANCZOS)
    x = np.asarray(resized, dtype=np.float32) / 127.5 - 1.0
    x = x.transpose(2, 0, 1)[None]  # 1,3,H,W
    sess = ort.InferenceSession(MODEL, providers=['CPUExecutionProvider'])
    out = sess.run(None, {'input': x})[0]
    alpha = out[0, 0]  # H,W，值域 [0,1]
    return alpha, miw, mih


def extend_alpha_out_of_source(alpha, out_w, out_h, box, src_w, src_h):
    """复刻 v2：越界区一律置 0"""
    px_x = box['width'] / out_w
    px_y = box['height'] / out_h
    x_in0 = int(np.clip(np.ceil((0 - box['left']) / px_x), 0, out_w - 1))
    x_in1 = int(np.clip(np.floor((src_w - box['left']) / px_x) - 1, 0, out_w - 1))
    y_in0 = int(np.clip(np.ceil((0 - box['top']) / px_y), 0, out_h - 1))
    y_in1 = int(np.clip(np.floor((src_h - box['top']) / px_y) - 1, 0, out_h - 1))
    a = alpha.copy()
    a[:y_in0, :] = 0
    a[y_in1 + 1:, :] = 0
    a[:, :x_in0] = 0
    a[:, x_in1 + 1:] = 0
    return a


def clean_ghost_alpha(alpha):
    """复刻 cleanGhostAlpha：下半区 alpha<0.45 归 0，上半区不动"""
    a = alpha.copy()
    start = int(alpha.shape[0] * GHOST_START_ROW)
    a[start:, :] = np.where(a[start:, :] < GHOST_THRESHOLD, 0.0, a[start:, :])
    return a


def compose(cropped_rgb, alpha, bg_rgb):
    a = alpha[..., None]
    return (cropped_rgb * a + np.array(bg_rgb, dtype=np.float32) * (1 - a)).astype(np.uint8)


def main():
    src = sys.argv[1]
    os.makedirs(OUT_DIR, exist_ok=True)
    img = Image.open(src).convert('RGB')
    iw, ih = img.size
    print(f'原图 {iw}x{ih}')

    # ① 人脸检测（OpenCV Haar 近似 VisionKit 的框）
    gray = cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2GRAY)
    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
    faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(60, 60))
    if len(faces) == 0:
        print('!! 未检出人脸'); return
    face = max(faces, key=lambda f: f[2] * f[3])  # 最大脸
    print(f'人脸框 x={face[0]} y={face[1]} w={face[2]} h={face[3]}')

    # ② 构图（教资认定 295×413）
    out_w, out_h = 295, 413
    ratio = out_w / out_h
    box = calc_crop_box(face, iw, ih, ratio)
    face_ratio = face[3] / box['height'] * 100
    print(f"裁剪框 {box} | 人脸占框高 {face_ratio:.1f}%（目标 45%）")
    print(f"  顶部越界: {'是(' + str(-box['top']) + 'px 铺白)' if box['top'] < 0 else '否'}"
          f" | 横向越界: {'是❌' if box['left'] < 0 or box['left'] + box['width'] > iw else '否✅'}"
          f" | 底部越界: {'是❌' if box['top'] + box['height'] > ih else '否✅'}")

    # ③ 裁剪 + ④ 推理
    cropped = crop_and_scale(img, box, out_w, out_h)
    cropped.save(os.path.join(OUT_DIR, '0_cropped.png'))
    alpha_raw, miw, mih = run_matting(cropped)
    alpha = cv2.resize(alpha_raw, (out_w, out_h), interpolation=cv2.INTER_LINEAR)
    print(f'推理蒙版 {miw}x{mih}')

    # ⑤ alpha 修正链（box 为原图坐标系，srcW/srcH 传原图尺寸）
    alpha_eos = extend_alpha_out_of_source(alpha, out_w, out_h, box, iw, ih)
    alpha_clean = clean_ghost_alpha(alpha_eos)

    # 残影统计（下半区 0.05~0.45 的半透明像素）
    start = int(out_h * GHOST_START_ROW)
    ghost_raw = int(((alpha_eos[start:, :] >= 0.05) & (alpha_eos[start:, :] < GHOST_THRESHOLD)).sum())
    ghost_clean = int(((alpha_clean[start:, :] >= 0.05) & (alpha_clean[start:, :] < GHOST_THRESHOLD)).sum())
    print(f'下半区残影像素（0.05≤α<0.45）: 清理前 {ghost_raw} → 清理后 {ghost_clean}')

    # 残影位置分布（列直方，前 5 个聚集列段）
    mask_ghost = (alpha_eos[start:, :] >= 0.05) & (alpha_eos[start:, :] < GHOST_THRESHOLD)
    col_hits = mask_ghost.sum(axis=0)
    hot = [i for i, v in enumerate(col_hits) if v > out_h * 0.08]
    if hot:
        segs, s0 = [], hot[0]
        for i in range(1, len(hot)):
            if hot[i] != hot[i - 1] + 1:
                segs.append((s0, hot[i - 1])); s0 = hot[i]
        segs.append((s0, hot[-1]))
        print('残影聚集列段:', ', '.join(f'x{a}~{b}' for a, b in segs))
    else:
        print('残影聚集列段: 无')

    # ⑥ 可视化与合成
    Image.fromarray((alpha_eos * 255).astype(np.uint8)).save(os.path.join(OUT_DIR, '1_mask_raw.png'))
    Image.fromarray((alpha_clean * 255).astype(np.uint8)).save(os.path.join(OUT_DIR, '2_mask_clean.png'))
    arr = np.asarray(cropped, dtype=np.float32)
    for name, bg in [('white', (255, 255, 255)), ('red', (184, 2, 2)), ('blue', (67, 142, 219))]:
        out = compose(arr, alpha_clean, bg)
        Image.fromarray(out).save(os.path.join(OUT_DIR, f'3_result_{name}.jpg'), quality=92)
    print('输出完成 →', OUT_DIR)


if __name__ == '__main__':
    main()
