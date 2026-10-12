# -*- coding: utf-8 -*-
"""诊断脚本：直接用指定密钥调用腾讯云 BDA，判断鉴权是否通过（不传图片，只测权限）。

用法（密钥从环境变量传入，不要写进文件）：
    BDA_SID=AKIDxxxx BDA_SKEY=xxxx python tools/bda_auth_check.py

判读：
    MissingParameter          → 鉴权通过、服务可用（只是没传图片参数）
    UnauthorizedOperation     → 该密钥所属账号缺 BDA 权限（CAM 策略未绑定/绑错账号）

依赖：pip install tencentcloud-sdk-python-bda
"""
import os
import json

from tencentcloud.common import credential
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile
from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException
from tencentcloud.bda.v20200324 import bda_client, models

SECRET_ID = os.environ["BDA_SID"]
SECRET_KEY = os.environ["BDA_SKEY"]

cred = credential.Credential(SECRET_ID, SECRET_KEY)
hp = HttpProfile()
hp.endpoint = "bda.tencentcloudapi.com"
hp.reqTimeout = 15
cp = ClientProfile()
cp.httpProfile = hp
client = bda_client.BdaClient(cred, "ap-guangzhou", cp)


def run(name, fn):
    try:
        resp = fn()
        print(f"[{name}] OK -> {resp.to_json_string()[:300]}")
    except TencentCloudSDKException as e:
        print(f"[{name}] FAIL code={e.code} requestId={e.requestId}")
        print(f"        message={e.message}")


# 1) 人像分割（后端主流程用的接口），不传图 ⇒ 鉴权通过时应报参数缺失
run("SegmentPortraitPic", lambda: client.SegmentPortraitPic(models.SegmentPortraitPicRequest()))

# 3) 自定义人像分割（后端美肤用），不传图
run("SegmentCustomizedPortraitPic",
    lambda: client.SegmentCustomizedPortraitPic(models.SegmentCustomizedPortraitPicRequest()))
