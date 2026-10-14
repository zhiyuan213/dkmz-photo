#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拍博士证件照 API · 试用探测脚本（仅依赖 Python 标准库，无需 pip install）。

用途：拿到云市场 AppCode 后，用一张真实手机照片跑一次接口，
      把返回的每一张图落盘，人工看质量（换装贴合 / 抠图边缘 / 美颜是否失真）。

用法：
    export PAIBOSHI_APPCODE=你的AppCode

    # 1) 干跑：只打印将要发送的请求，不联网、不消耗配额
    python tools/paiboshi_probe.py --image 自拍.jpg --dry-run

    # 2) 真跑：结果落到 paiboshi-trial/
    python tools/paiboshi_probe.py --image 自拍.jpg --out paiboshi-trial

    # 3) 换装（需要 clothes_id，编码表见商品页「接口文档」）
    python tools/paiboshi_probe.py --image 自拍.jpg --clothes-id 1001

    # 4) 不传图片、改用图床 URL（标准版只支持 img_url）
    python tools/paiboshi_probe.py --url https://example.com/a.jpg

可用环境变量：
    PAIBOSHI_APPCODE    阿里云云市场 AppCode（简单认证，首选）
    PAIBOSHI_ENDPOINT   覆盖接入地址
    PAIBOSHI_PRODUCT_ID 默认规格 skuid
    PAIBOSHI_CLOTHES_ID 默认换装编码

⚠️ 本脚本只用于试用验证。真正接入走服务端（密钥仅存云托管环境变量），
   不要把这个脚本或密钥打包进小程序。
"""

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_ENDPOINT = "https://idsuipai.market.alicloudapi.com/d/openservice/idphoto_make_enhance"

# 图片魔数 → 扩展名（按 base64 解码后的头几个字节判断真实格式）
MAGIC = [
    (b"\x89PNG\r\n\x1a\n", "png"),
    (b"\xff\xd8\xff", "jpg"),
    (b"GIF8", "gif"),
    (b"RIFF", "webp"),
    (b"BM", "bmp"),
]


def sniff_ext(raw: bytes) -> str:
    for magic, ext in MAGIC:
        if raw.startswith(magic):
            return ext
    return "bin"


def load_image_b64(path: Path) -> str:
    raw = path.read_bytes()
    if len(raw) > 5 * 1024 * 1024:
        print(f"⚠️  图片 {len(raw) / 1024 / 1024:.1f}MB 超过常见的 5MB 上限，接口可能拒绝", file=sys.stderr)
    return base64.b64encode(raw).decode("ascii")


def build_body(args) -> dict:
    """组装请求体。字段名依拍博士公开示例，未确认的字段以文档为准。"""
    body = {}

    # 规格：product_id=0 时走自定义像素/毫米尺寸
    product_id = args.product_id
    if product_id is None:
        product_id = os.environ.get("PAIBOSHI_PRODUCT_ID")
    body["product_id"] = int(product_id) if product_id not in (None, "") else 0

    if body["product_id"] == 0:
        body["px_width"] = args.px_width
        body["px_height"] = args.px_height
        body["mm_width"] = args.mm_width
        body["mm_height"] = args.mm_height

    clothes_id = args.clothes_id
    if clothes_id is None:
        clothes_id = os.environ.get("PAIBOSHI_CLOTHES_ID")
    if clothes_id not in (None, ""):
        body["clothes_id"] = clothes_id

    # img_url 与 img_base64 只能有一个
    if args.url:
        body["img_url"] = args.url
    else:
        body["img_base64"] = "<base64 已省略>" if args.dry_run else load_image_b64(Path(args.image))

    return body


def build_headers(args) -> dict:
    appcode = args.appcode or os.environ.get("PAIBOSHI_APPCODE", "")
    if not appcode:
        sys.exit("缺少 AppCode：请 export PAIBOSHI_APPCODE=xxx，或用 --appcode 指定")

    headers = {
        "Authorization": f"APPCODE {appcode}",
        "Content-Type": "application/json; charset=UTF-8",
        "X-Requested-With": "XMLHttpRequest",
        # 腾讯云网关侧要求；阿里云侧无害
        "request-id": base64.b16encode(os.urandom(16)).decode("ascii").lower(),
    }
    return headers


def post_json(endpoint: str, headers: dict, body: dict, timeout: int = 60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(endpoint, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except urllib.error.URLError as e:
        sys.exit(f"网络/证书错误（云托管容器直连第三方常见 PKIX 问题，先确认连通性）：{e}")


B64_RE = re.compile(rb"^[A-Za-z0-9+/=\s]+$")


def is_probably_base64(s: str) -> bool:
    if len(s) < 200:
        return False
    body = s.split("base64,", 1)[-1].strip()
    if len(body) < 200 or len(body) % 4 != 0:
        return False
    return bool(B64_RE.match(body.encode("ascii", "ignore")))


def save_payload(value: str, out_dir: Path, name: str) -> str | None:
    """把一个可能是 base64 或 URL 的字符串落盘，返回落盘文件名。"""
    try:
        if value.startswith("http://") or value.startswith("https://"):
            with urllib.request.urlopen(value, timeout=60) as r:
                raw = r.read()
            ext = sniff_ext(raw) if raw else "bin"
            fp = out_dir / f"{name}.{ext}"
            fp.write_bytes(raw)
            return fp.name

        if is_probably_base64(value):
            pure = value.split("base64,", 1)[-1].strip()
            raw = base64.b64decode(pure)
            fp = out_dir / f"{name}.{sniff_ext(raw)}"
            fp.write_bytes(raw)
            return fp.name
    except Exception as e:  # 落盘失败不阻断整体流程，试用阶段要看全貌
        print(f"   ⚠️  {name} 保存失败：{e}", file=sys.stderr)
    return None


def walk_and_save(node, out_dir: Path, prefix: str, results: list, depth: int = 0):
    """递归遍历响应，把图片载荷落盘，并记录每个字段的规模。"""
    if depth > 6:
        return
    if isinstance(node, dict):
        for k, v in node.items():
            walk_and_save(v, out_dir, f"{prefix}{k}", results, depth + 1)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_and_save(v, out_dir, f"{prefix}_{i}", results, depth + 1)
    elif isinstance(node, str):
        saved = save_payload(node, out_dir, prefix)
        if saved:
            results.append((prefix, saved, len(node)))
        elif len(node) < 200:
            print(f"   {prefix} = {node}")


def summarize(payload: dict):
    """先打印返回结构，方便数清楚到底几张图、分别是什么。"""
    print("\n— 返回结构 —")
    body = payload.get("body", payload)
    if not isinstance(body, dict):
        print(json.dumps(payload, ensure_ascii=False)[:2000])
        return
    for k, v in body.items():
        if isinstance(v, list):
            kinds = []
            for item in v:
                if isinstance(item, str):
                    if item.startswith("http"):
                        kinds.append("URL")
                    elif is_probably_base64(item):
                        kinds.append(f"base64({len(item) / 1024:.0f}KB)")
                    else:
                        kinds.append(f"str:{item[:40]}")
                else:
                    kinds.append(type(item).__name__)
            print(f"   {k}: 共 {len(v)} 项 → {', '.join(kinds) if kinds else '空'}")
        elif isinstance(v, str) and is_probably_base64(v):
            print(f"   {k}: base64({len(v) / 1024:.0f}KB)")
        else:
            print(f"   {k}: {json.dumps(v, ensure_ascii=False)[:200]}")


def main():
    p = argparse.ArgumentParser(description="拍博士证件照 API 试用探测")
    src = p.add_mutually_exclusive_group(required=not True)
    src.add_argument("--image", help="本地图片路径（会转 base64 发送，需高级版）")
    src.add_argument("--url", help="图片公网 URL（与 --image 二选一，标准版走这个）")

    p.add_argument("--out", default="paiboshi-trial", help="结果输出目录")
    p.add_argument("--endpoint", default=os.environ.get("PAIBOSHI_ENDPOINT", DEFAULT_ENDPOINT))
    p.add_argument("--appcode", default="")
    p.add_argument("--product-id", default=None, help="规格 skuid；0 = 自定义尺寸")
    p.add_argument("--clothes-id", default=None, help="换装编码（编码表见商品页接口文档）")
    p.add_argument("--px-width", type=int, default=295)
    p.add_argument("--px-height", type=int, default=413)
    p.add_argument("--mm-width", type=int, default=25)
    p.add_argument("--mm-height", type=int, default=35)
    p.add_argument("--dry-run", action="store_true", help="只打印请求，不联网")
    args = p.parse_args()

    if not args.image and not args.url:
        p.error("必须提供 --image 或 --url")

    body = build_body(args)
    headers = build_headers(args)

    print(f"POST {args.endpoint}")
    print(f"Authorization: APPCODE ****{ (args.appcode or os.environ.get('PAIBOSHI_APPCODE',''))[-4:] }")
    print("Body:")
    for k, v in body.items():
        shown = v if not isinstance(v, str) or len(v) < 80 else f"{v[:40]}…({len(v)} chars)"
        print(f"   {k} = {shown}")

    if args.dry_run:
        print("\n（--dry-run 干跑结束，未发送请求、未消耗配额）")
        return

    status, raw = post_json(args.endpoint, headers, body)
    print(f"\nHTTP {status}")

    txt = raw.decode("utf-8", "replace")
    try:
        payload = json.loads(txt)
    except json.JSONDecodeError:
        print("返回不是 JSON，原文前 2000 字符：")
        print(txt[:2000])
        return

    summarize(payload)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print("\n— 落盘 —")
    results: list = []
    walk_and_save(payload, out_dir, "", results)

    if results:
        for prefix, fname, size in results:
            print(f"   {prefix or '(root)'} → {fname}  ({size / 1024:.0f}KB)")
    else:
        print("   未识别到图片载荷，请检查返回结构（可能是错误的错误码，或字段命名不同）")

    # 留一份脱敏原始响应，便于核对字段
    redacted = json.loads(json.dumps(payload))
    (out_dir / "_raw_response.json").write_text(
        json.dumps(redacted, ensure_ascii=False, indent=2)[:200000], encoding="utf-8"
    )
    print(f"\n共 {len(results)} 张图 → {out_dir.resolve()}")


if __name__ == "__main__":
    main()
