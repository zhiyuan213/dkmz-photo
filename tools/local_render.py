# -*- coding: utf-8 -*-
"""
本地调试 harness：用真实 BDA 接口复刻 af351d5 版证件照管线，
输出 红/白/蓝 三底色成品，供肉眼评审迭代。不参与线上部署。

用法:
  export BDAB_SECRET_ID=...   # 必填，腾讯云 API 密钥
  export BDAB_SECRET_KEY=...  # 必填；勿写入代码 / 勿提交仓库
  python local_render.py <照片路径> [--out 输出目录] [--px 300 400] [--no-beauty]

流程（对齐 miniprogram/utils/pipeline.js @af351d5 + backend SkinBeautyService v2）:
  1. BDA DetectFace 取人脸框          （替代端上 VisionKit）
  2. calcCropBox 构图                  （face.js 同款公式）
  3. 裁剪缩放 outW×outH = 人像层       （等价 cropAndScale，LANCZOS 高质量）
  4. 上传小图 384×512/q0.8 → SegmentPortraitPic → 透明底 PNG
  5. alpha = PNG 的 A 通道 → 双线性 resizeMask → cleanGhostAlpha
     → extendAlphaOutOfSource → decontaminateEdge（残影重估版）
  6. 换底合成（白 #FFFFFF / 蓝 #438EDB / 红 #B80202）
  每种底色出两版：
    nobeauty_*  —— 现网真实行为（美肤断链，人像层=原始像素）
    beauty_*    —— 美白生效的候选效果（YCbCr 提亮 v2 + 磨皮，只作用皮肤 mask）
另存 alpha.png / skinmask.png 供诊断。
"""
import argparse
import base64
import io
import os
import sys

import numpy as np
from PIL import Image
from tencentcloud.bda.v20200324 import bda_client, models as bda_models
from tencentcloud.common import credential
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile

# 密钥只从环境变量读取，绝不写进文件（与 tools/bda_auth_check.py 一致）
try:
    SECRET_ID = os.environ["BDAB_SECRET_ID"]
    SECRET_KEY = os.environ["BDAB_SECRET_KEY"]
except KeyError as e:
    sys.exit(
        "缺少环境变量 %s —— 运行前请先导出：\n"
        "  export BDAB_SECRET_ID=...\n"
        "  export BDAB_SECRET_KEY=...\n"
        "（密钥见腾讯云访问管理，勿写入代码或提交到仓库）" % e.args[0]
    )
REGION = os.environ.get("BDAB_REGION", "ap-guangzhou")

BGS = [("white", "#FFFFFF"), ("blue", "#438EDB"), ("red", "#B80202")]

# pipeline.js / SkinBeautyService 常量
FACE_DETECT_CAP = 1280
CLOUD_UPLOAD_LONG_EDGE = 512
CLOUD_UPLOAD_QUALITY = 0.8
FACE_HEIGHT_RATIO = 0.45  # cropFaceRatio 默认

MAX_DELTA = 26.0
MEDIAN_LIFT = 9.0
SHADOW_KEEP = 0.75
DEYELLOW = 0.12
SMOOTH_ID = 0.20


def make_client():
    cred = credential.Credential(SECRET_ID, SECRET_KEY)
    hp = HttpProfile(endpoint="bda.tencentcloudapi.com")
    cp = ClientProfile(httpProfile=hp)
    return bda_client.BdaClient(cred, REGION, cp)


def b64_of_pil(img, fmt="JPEG", quality=95):
    buf = io.BytesIO()
    img.save(buf, fmt, quality=quality)
    return base64.b64encode(buf.getvalue()).decode()


def pil_of_b64(b64):
    return Image.open(io.BytesIO(base64.b64decode(b64)))


def clamp(v):
    return 0 if v < 0 else (255 if v > 255 else v)


# ------------------------------------------------- 1. 人脸检测（OpenCV，替代端上 VisionKit）
_face_cascade = None


def _get_cascade():
    global _face_cascade
    if _face_cascade is None:
        import cv2
        _face_cascade = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    return _face_cascade


def detect_face(client, img: Image.Image):
    import cv2
    ow, oh = img.size
    scale = min(1.0, FACE_DETECT_CAP / max(ow, oh))
    w, h = max(32, round(ow * scale)), max(32, round(oh * scale))
    probe = img.resize((w, h), Image.BILINEAR)
    gray = cv2.cvtColor(np.asarray(probe), cv2.COLOR_RGB2GRAY)
    gray = cv2.equalizeHist(gray)
    faces = _get_cascade().detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(32, 32))
    if len(faces) == 0:
        return None
    x, y, fw, fh2 = max(faces, key=lambda f: f[2] * f[3])
    return {
        "left": x / scale,
        "top": y / scale,
        "boxWidth": fw / scale,
        "boxHeight": fh2 / scale,
    }


# ------------------------------------------------- 2. 构图（face.js calcCropBox）
def calc_crop_box(face, img_w, img_h, target_ratio=3 / 4, face_h_ratio=FACE_HEIGHT_RATIO):
    fh = face_h_ratio
    cx = face["left"] + face["boxWidth"] / 2
    crop_h = face["boxHeight"] / fh
    crop_w = crop_h * target_ratio
    if crop_w > img_w:
        crop_w = img_w
        crop_h = crop_w / target_ratio
    left = max(0.0, min(cx - crop_w / 2, img_w - crop_w))
    cy = face["top"] + face["boxHeight"] / 2 - crop_h * 0.06
    top = cy - crop_h / 2
    if top + crop_h > img_h:
        top = img_h - crop_h
    return {
        "left": round(left),
        "top": round(top),
        "width": round(crop_w),
        "height": round(crop_h),
    }


# ------------------------------------------------- 3. 裁剪缩放（cropAndScale）
def crop_and_scale(img: Image.Image, box, out_w, out_h):
    """box 只允许顶部越界：越界区铺白底"""
    canvas = Image.new("RGB", (out_w, out_h), (255, 255, 255))
    sx = max(0, box["left"])
    sy = max(0, box["top"])
    ex = min(img.width, box["left"] + box["width"])
    ey = min(img.height, box["top"] + box["height"])
    if ex > sx and ey > sy:
        scale_x = out_w / box["width"]
        scale_y = out_h / box["height"]
        dx = (sx - box["left"]) * scale_x
        dy = (sy - box["top"]) * scale_y
        dw = (ex - sx) * scale_x
        dh = (ey - sy) * scale_y
        part = img.crop((sx, sy, ex, ey)).resize((max(1, round(dw)), max(1, round(dh))), Image.LANCZOS)
        canvas.paste(part, (round(dx), round(dy)))
    return canvas


# ------------------------------------------------- 5a. 蒙版处理
def resize_mask(mask: np.ndarray, tw: int, th: int) -> np.ndarray:
    """matting.resizeMask 同款双线性（含 0.5 采样偏移语义）"""
    mh, mw = mask.shape
    ys = np.clip((np.arange(th) + 0.5) * (mh / th) - 0.5, 0, mh - 1)
    xs = np.clip((np.arange(tw) + 0.5) * (mw / tw) - 0.5, 0, mw - 1)
    y0 = np.floor(ys).astype(int)
    y1 = np.minimum(y0 + 1, mh - 1)
    wy = (ys - y0)[:, None]
    x0 = np.floor(xs).astype(int)
    x1 = np.minimum(x0 + 1, mw - 1)
    wx = (xs - x0)[None, :]
    v00 = mask[np.ix_(y0, x0)]
    v01 = mask[np.ix_(y0, x1)]
    v10 = mask[np.ix_(y1, x0)]
    v11 = mask[np.ix_(y1, x1)]
    top = v00 + (v01 - v00) * wx
    bottom = v10 + (v11 - v10) * wx
    return top + (bottom - top) * wy


def clean_ghost_alpha(alpha: np.ndarray):
    """下半区（>=58% 行）alpha<0.45 清零"""
    h, w = alpha.shape
    start = int(h * 0.58)
    alpha[start:, :] = np.where(alpha[start:, :] < 0.45, 0.0, alpha[start:, :])


def extend_alpha_out_of_source(alpha: np.ndarray, src_w, src_h, box):
    """越界区 alpha=0（顶部补白等）"""
    h, w = alpha.shape
    px_x = box["width"] / w
    px_y = box["height"] / h

    def rng(a, b):
        lo = int(np.clip(np.ceil(a), 0, w - 1))
        hi = int(np.clip(np.floor(b) - 1, 0, w - 1))
        return lo, hi

    x_in0, x_in1 = rng((0 - box["left"]) / px_x, (src_w - box["left"]) / px_x)
    y_in0, y_in1 = rng((0 - box["top"]) / px_y, (src_h - box["top"]) / px_y)
    alpha[:y_in0, :] = 0
    alpha[y_in1 + 1:, :] = 0
    alpha[:, :x_in0] = 0
    alpha[:, x_in1 + 1:] = 0


def decontaminate_edge(alpha: np.ndarray, rgb: np.ndarray, ghost_dist=14):
    """pipeline.js decontaminateEdge 移植 + 残影重估全域化（头发白霜修复实验）
    ghost_dist=1e9：上半区全部允许颜色证据重估（原版仅距背景 14px），
    加 r-b≤28 皮肤防线保护脸/镜框。原位修改 alpha/rgb"""
    h, w = alpha.shape
    n = w * h
    SAT_MAX = 42
    RB_MAX = 28
    ghost_row = int(h * 0.58)

    # 1) 原背景色估计
    trans = alpha < 0.03
    cnt = int(trans.sum())
    if cnt > n * 0.005:
        B = rgb[trans].mean(axis=0)
    else:
        B = np.array([255.0, 255.0, 255.0])
    LB = B.mean()
    LUM_MIN = max(150.0, LB - 70)

    # 2) chamfer 距离场
    INF = 1e6
    dist = np.where(alpha < 0.08, 0.0, INF).astype(np.float64)
    for y in range(h):
        for x in range(w):
            v = dist[y, x]
            if x > 0 and dist[y, x - 1] + 1 < v:
                v = dist[y, x - 1] + 1
            if y > 0:
                if dist[y - 1, x] + 1 < v:
                    v = dist[y - 1, x] + 1
                if x > 0 and dist[y - 1, x - 1] + 1.41 < v:
                    v = dist[y - 1, x - 1] + 1.41
                if x < w - 1 and dist[y - 1, x + 1] + 1.41 < v:
                    v = dist[y - 1, x + 1] + 1.41
            dist[y, x] = v
    for y in range(h - 1, -1, -1):
        for x in range(w - 1, -1, -1):
            v = dist[y, x]
            if x < w - 1 and dist[y, x + 1] + 1 < v:
                v = dist[y, x + 1] + 1
            if y < h - 1:
                if dist[y + 1, x] + 1 < v:
                    v = dist[y + 1, x] + 1
                if x < w - 1 and dist[y + 1, x + 1] + 1.41 < v:
                    v = dist[y + 1, x + 1] + 1.41
                if x > 0 and dist[y + 1, x - 1] + 1.41 < v:
                    v = dist[y + 1, x - 1] + 1.41
            dist[y, x] = v

    # 3) 反推 + 残影重估 + 收边
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    lum = rgb.mean(axis=2)
    rb = rgb[..., 0] - rgb[..., 2]
    low_sat = ((mx - mn) <= SAT_MAX) & (rb <= RB_MAX)
    in_zone = (np.arange(h)[:, None] < ghost_row) & (dist <= ghost_dist)
    cand = in_zone & low_sat & (lum >= LUM_MIN)
    w_color = np.clip((lum - 70) / (LB - 70), 0, 1)
    w_mask = 1.0 - alpha
    w = np.where(cand, np.maximum(w_mask, w_color), w_mask)

    kill = w >= 0.9
    alpha[kill] = 0.0

    keep = ~kill & (alpha > 0)
    inv = 1.0 / (1.0 - w[keep, None])
    rgb[keep] = np.clip((rgb[keep] - B[None, :] * w[keep, None]) * inv, 0, 255)
    alpha[keep] = 1.0 - w[keep]


# ------------------------------------------------- 5b. 头发修复（连通泛洪 + 高光压暗）
def compute_wall_mask(alpha: np.ndarray, rgb: np.ndarray, top_ratio=0.62,
                      sat_max=40, lum_min=130, rb_max=22):
    """
    墙体泄漏区：从真背景种子（原始 alpha<0.05）沿「中性灰白+亮」像素 BFS 生长（限上半区）。
    r-b ≤ rb_max 是皮肤防线：黄种人皮肤 r 明显大于 b，白墙是中性色——泛洪进不了脸。
    必须用 decontaminate 之前的原始数据算，避免内部残留低 alpha 点当种子。
    """
    from collections import deque
    h, w = alpha.shape
    top = int(h * top_ratio)
    sat = rgb.max(axis=2) - rgb.min(axis=2)
    lum = rgb.mean(axis=2)
    rb = rgb[..., 0] - rgb[..., 2]
    wallish = (sat <= sat_max) & (lum >= lum_min) & (rb <= rb_max)

    visited = alpha < 0.05
    dq = deque(zip(*np.where(visited)))
    while dq:
        y, x = dq.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < top and 0 <= nx < w and not visited[ny, nx] and wallish[ny, nx]:
                visited[ny, nx] = True
                dq.append((ny, nx))
    return visited & wallish


def process_hair(alpha: np.ndarray, rgb: np.ndarray, wall: np.ndarray,
                 top_ratio=0.62, highlight_keep=0.5):
    """
    头发白霜修复（连续键控版）。wall=泛洪出的墙体连通区（含发丝缝隙透墙）。
      1) 泛洪区内接近纯墙的像素（lum≥235）直接 alpha=0（发冠内部灰色小孔清干净）
      2) 作用区 = wall 膨胀 5px ∩ 上半区 ∩ 中性色（rb≤25, sat≤55）——脸/镜框够不到泛洪区，天然免疫
      3) 区内连续键控：t=墙占比（lum 200→0, 245→1），alpha×(1−t)；
         亮度向发丝暗部 HD 压缩（keep=highlight_keep×alpha，越透明压得越狠）
    """
    import cv2
    h, w = alpha.shape
    top = int(h * top_ratio)
    lum = rgb.mean(axis=2)
    sat = rgb.max(axis=2) - rgb.min(axis=2)
    rb = rgb[..., 0] - rgb[..., 2]

    # 1) 泛洪区内纯墙
    solid_wall = wall & (lum >= 235)
    alpha[solid_wall] = 0.0

    # 2) 作用区
    kernel = np.ones((5, 5), np.uint8)
    zone = cv2.dilate(wall.astype(np.uint8), kernel).astype(bool)
    zone &= (np.arange(h)[:, None] < top) & (rb <= 25) & (sat <= 55) & (alpha > 0)

    # 发丝暗部中位数
    rows = np.arange(h)[:, None] < top
    hair_zone = rows & ~wall & (alpha > 0.5)
    hd_vals = lum[hair_zone & (lum < 100)]
    HD = float(np.median(hd_vals)) if hd_vals.size else 60.0

    # 3) 连续键控
    t = np.clip((lum - 200.0) / 45.0, 0, 1)
    alpha[:] = np.where(zone, alpha * (1 - t), alpha)
    lum2 = rgb.mean(axis=2)
    keep = highlight_keep * alpha
    f = (HD + (lum2 - HD) * keep) / np.maximum(lum2, 1.0)
    apply = zone & (lum2 >= HD + 20)
    rgb[:] = np.where(apply[..., None], np.clip(rgb * f[..., None], 0, 255), rgb)
    return HD, int(zone.sum())
def skin_beautify(rgb: np.ndarray, skin_mask: np.ndarray):
    """rgb: HxWx3 float, skin_mask: HxW bool。YCbCr 提亮 + 保边磨皮"""
    img = rgb.copy()
    luma = 0.299 * img[..., 0] + 0.587 * img[..., 1] + 0.114 * img[..., 2]
    vals = luma[skin_mask]
    median = float(np.median(vals)) if vals.size else 128.0
    target = min(238.0, median + MEDIAN_LIFT)

    r, g, b = img[..., 0], img[..., 1], img[..., 2]
    y0 = luma
    cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
    cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b

    delta = np.clip((target - y0) * SHADOW_KEEP, 0, None)
    hi_guard = 1.0 - np.clip((y0 - 190.0) / 65.0, 0, 1) ** 1.5
    delta = np.minimum(delta * hi_guard, MAX_DELTA)
    y1 = np.minimum(255.0, y0 + delta)
    cr1 = 128.0 + (cr - 128.0) * (1.0 - DEYELLOW)

    nr = y1 + 1.402 * (cr1 - 128.0)
    ng = y1 - 0.344136 * (cb - 128.0) - 0.714136 * (cr1 - 128.0)
    nb = y1 + 1.772 * (cb - 128.0)
    out = np.stack([nr, ng, nb], axis=2)
    img = np.where(skin_mask[..., None] & (delta[..., None] >= 0.5), out, img)

    # 磨皮：5×5 均值（保边 diff<=90），高频保留 80%
    return smooth(img, skin_mask, SMOOTH_ID), median


def smooth(img: np.ndarray, mask: np.ndarray, strength):
    h, w, _ = img.shape
    radius = 2
    keep_detail = 1.0 - strength
    src = img.copy()
    # 用盒滤波近似逐像素 5×5 邻域（保边：色差>90 的邻居剔除）
    pad = np.pad(src, ((radius, radius), (radius, radius), (0, 0)), mode="edge")
    pad_m = np.pad(mask, ((radius, radius), (radius, radius)), mode="constant", constant_values=False)
    sr = np.zeros((h, w), dtype=np.float64)
    sg = np.zeros((h, w), dtype=np.float64)
    sb = np.zeros((h, w), dtype=np.float64)
    cnt = np.zeros((h, w), dtype=np.float64)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            ys, ye = radius + dy, radius + dy + h
            xs, xe = radius + dx, radius + dx + w
            win = pad[ys:ye, xs:xe]
            win_m = pad_m[ys:ye, xs:xe]
            diff = np.abs(win - src).sum(axis=2)
            ok = win_m & (diff <= 90)
            cnt += ok
            sr += np.where(ok, win[..., 0], 0.0)
            sg += np.where(ok, win[..., 1], 0.0)
            sb += np.where(ok, win[..., 2], 0.0)
    valid = (cnt > 0) & mask
    cnt_safe = np.maximum(cnt, 1)
    ar, ag, ab = sr / cnt_safe, sg / cnt_safe, sb / cnt_safe
    nr = ar + (src[..., 0] - ar) * keep_detail
    ng = ag + (src[..., 1] - ag) * keep_detail
    nb = ab + (src[..., 2] - ab) * keep_detail
    out = np.stack([nr, ng, nb], axis=2)
    return np.where(valid[..., None], out, src)


# ------------------------------------------------- 合成
def composite(alpha: np.ndarray, rgb: np.ndarray, bg_hex: str):
    v = bg_hex.lstrip("#")
    bg = np.array([int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16)], dtype=np.float64)
    a = alpha[..., None]
    out = np.clip(rgb * a + bg[None, None, :] * (1 - a), 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("photo")
    ap.add_argument("--out", default="output-debug")
    ap.add_argument("--px", nargs=2, type=int, default=[300, 400])
    ap.add_argument("--beauty", action="store_true", default=True)
    ap.add_argument("--no-beauty", dest="beauty", action="store_false")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    out_w, out_h = args.px
    img = Image.open(args.photo).convert("RGB")
    print(f"原图 {img.width}x{img.height}")

    client = make_client()

    # 1) 人脸检测
    face = detect_face(client, img)
    if face:
        print(f"人脸框 left={face['left']:.0f} top={face['top']:.0f} w={face['boxWidth']:.0f} h={face['boxHeight']:.0f}")
    else:
        print("!! 未检出人脸（用兜底构图）")

    # 2) 构图
    ow, oh = img.size
    if face:
        box = calc_crop_box(face, ow, oh, out_w / out_h)
    else:
        face_h = min(ow, oh) * 0.35
        fake = {"left": ow / 2 - face_h * 0.35, "top": oh * 0.08, "boxWidth": face_h * 0.7, "boxHeight": face_h}
        box = calc_crop_box(fake, ow, oh, out_w / out_h)
    print(f"裁剪框 {box} (top 可为负)")

    # 3) 人像层（高质量裁剪到输出尺寸）
    person_src = crop_and_scale(img, box, out_w, out_h)
    person = np.asarray(person_src, dtype=np.float64)

    # 4) 上传小图 → BDA 分割
    s = CLOUD_UPLOAD_LONG_EDGE / max(out_w, out_h)
    up_w, up_h = max(1, round(out_w * s)), max(1, round(out_h * s))
    upload = crop_and_scale(img, box, up_w, up_h)
    req = bda_models.SegmentPortraitPicRequest()
    req.Image = b64_of_pil(upload, quality=int(CLOUD_UPLOAD_QUALITY * 100))
    req.Scene = "GEN"
    req.RspImgType = "base64"
    resp = client.SegmentPortraitPic(req)
    cut = pil_of_b64(resp.ResultImage).convert("RGBA")
    print(f"BDA 分割 ok，蒙版 {cut.width}x{cut.height}")

    # 5) alpha 蒙版链
    alpha = np.asarray(cut)[..., 3].astype(np.float64) / 255.0
    alpha = resize_mask(alpha, out_w, out_h)
    if box["top"] < 0 or box["left"] < 0 or box["left"] + box["width"] > ow or box["top"] + box["height"] > oh:
        extend_alpha_out_of_source(alpha, ow, oh, box)
    clean_ghost_alpha(alpha)
    Image.fromarray((alpha * 255).astype(np.uint8), "L").save(os.path.join(args.out, "alpha.png"))

    # 皮肤 mask（美肤作用区域）
    skin300 = None
    if args.beauty:
        try:
            creq = bda_models.SegmentCustomizedPortraitPicRequest()
            creq.Image = b64_of_pil(upload, quality=int(CLOUD_UPLOAD_QUALITY * 100))
            opt = bda_models.SegmentationOptions()
            opt.Face = True
            creq.SegmentationOptions = opt
            cresp = client.SegmentCustomizedPortraitPic(creq)
            mimg = pil_of_b64(cresp.MaskImage).convert("L")
            if mimg.size != (out_w, out_h):
                mimg = mimg.resize((out_w, out_h), Image.BILINEAR)
            skin300 = np.asarray(mimg).astype(np.float64) > 128
            Image.fromarray((skin300 * 255).astype(np.uint8), "L").save(os.path.join(args.out, "skinmask.png"))
            print(f"皮肤 mask 覆盖率 {(skin300.mean() * 100):.1f}%")
        except Exception as e:
            print(f"皮肤 mask 获取失败，跳过美白对照: {e}")
            args.beauty = False

    # 墙体泄漏区（用原始数据算，供头发修复做作用区）
    wall = compute_wall_mask(alpha, person)
    print(f"墙体泄漏区: {int(wall.sum())}px")
    Image.fromarray((wall * 255).astype(np.uint8), "L").save(os.path.join(args.out, "wall.png"))

    # 每个 variant 各自跑 decontaminateEdge（原位改 alpha/rgb）+ 头发修复
    variants = []
    # —— 现网真实行为：人像层 = 原始像素（美肤断链）——
    nb_alpha = alpha.copy()
    nb_rgb = person.copy()
    decontaminate_edge(nb_alpha, nb_rgb)
    HD, npx = process_hair(nb_alpha, nb_rgb, wall)
    print(f"头发修复: 发色暗部中位 {HD:.0f}, 作用区 {npx}px")
    variants.append(("nobeauty", nb_alpha, nb_rgb))

    # —— 美白生效候选：人像层 = 美肤后像素 ——
    if args.beauty and skin300 is not None:
        b_rgb, median = skin_beautify(person, skin300)
        print(f"美肤 medianY={median:.0f}")
        b_alpha = alpha.copy()
        decontaminate_edge(b_alpha, b_rgb)
        process_hair(b_alpha, b_rgb, wall)
        variants.append(("beauty", b_alpha, b_rgb))

    results = []
    for name, a2, rgb2 in variants:
        for bg_name, bg_hex in BGS:
            out = composite(a2, rgb2, bg_hex)
            path = os.path.join(args.out, f"{name}_{bg_name}.jpg")
            out.save(path, "JPEG", quality=95)
            results.append(path)
            # 2x 放大预览版，方便肉眼看
            out.resize((out_w * 2, out_h * 2), Image.LANCZOS).save(
                os.path.join(args.out, f"{name}_{bg_name}_2x.png"))
    print("\n输出文件:")
    for p in results:
        print(" ", os.path.abspath(p))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
