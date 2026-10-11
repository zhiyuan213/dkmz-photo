# dkmz-photo · 登科喵证件照小程序

> 帮考生 **3 分钟**做出符合报名要求的证件照：选考试 → 自动裁剪构图 → 一键换底色 → 付费解锁高清无水印。
> 与「考试倒计时」（`D:/code/exam-countdown`）联动导流：主站提醒「该报名了」，这里「把报名的照片做出来」。

## 项目状态

| 项 | 状态 |
|---|---|
| 服务类目 | ✅ 工具 → 图片处理（已通过） |
| 微信认证 | ✅ 已完成（个人主体，30 元/年） |
| 小程序改名「登科喵证件照」 | ✅ **已生效**（2026-10-10 提交，10-11 通过；原「登科喵教资倒计时」） |
| 小程序头像 | ✅ 已定稿（`assets/avatar-512-clean.png`，蓝色相机 + 证件照人像，已去四角取景框） |
| 变更备案 | ⏳ **待提交**（改名已生效，前置条件满足；mp 后台【设置 → 基本信息 → 小程序备案 → 变更】） |
| 虚拟支付开通 | ⏳ 待申请（条件已满足：已认证 + 个人主体 + 类目含工具；**可不等备案，并行推进**） |
| **阶段 0 · 端上抠图验证** | ✅ **通过**（2026-10-11，vivo V2419A：fp32 / 384 短边全链路 498ms，输出 295×413 / 15KB；fp16 不被微信 Android 支持，生产用 fp32） |
| **阶段 1 · 免费主链路** | ✅ **真机全模板验证通过**（2026-10-11，vivo V2419A：7 模板全跑通，全规格校验绿、26~76KB 达标；拍照默认前置 + 取景引导框 + 越界补底构图） |
| **阶段 2 · 后端骨架** | ✅ **已完成**（2026-10-11：规格库云端下发 + 后台可配 + 订单中心 + 虚拟支付下单/发货/退款 + 权益体系；代码就绪，待云托管环境与 OfferID 接入联调） |
| 提审合规页（隐私政策 / 用户协议 / 关于与开源声明） | ✅ 已完成（2026-10-11，见 `pages/privacy`、`pages/agreement`、`pages/about`，首页页脚三个入口） |

## 目录说明

```
dkmz-photo/
├── miniprogram/                    # 小程序前端（阶段 1：免费主链路）
│   ├── pages/
│   │   ├── index/                  # 首页：考试规格列表 + 搜索 + 下拉刷新规格库 + 页脚入口
│   │   ├── make/                   # 制作页：选照片 → 自动裁剪 → 换底 → 按规格导出
│   │   ├── result/                 # 结果页：预览 + 规格校验 + 导出（先校验权益再保存）
│   │   ├── orders/                 # 订单中心：权益概览 + 订单列表（提审需登记此 path）
│   │   ├── about/                  # 关于与开源声明（含规格库版本/来源/免责、MODNet 许可）
│   │   ├── privacy/                # 用户隐私保护政策（提审必备）
│   │   ├── agreement/              # 用户协议（提审必备）
│   │   └── lab/                    # 端上引擎自检（阶段 0 验证页保留，供补测 512 / iOS）
│   ├── styles/
│   │   └── doc.wxss                # 合规文本页公共样式（三个政策页 @import 复用）
│   ├── utils/
│   │   ├── api.js                  # 网络层：云托管调用封装（容器调用/公网域名）+ 离线降级
│   │   ├── legal.js                # 合规文案常量（应用名/更新日期/联系邮箱/开源组件）
│   │   ├── specs.js                # 考试规格库：内置兜底 + 本地缓存 + **云端下发**
│   │   ├── pipeline.js             # 制作流水线：人脸构图→抠图→换底→水印→按体积二分导出
│   │   ├── matting.js              # 模型下载缓存 · 推理会话 · 预处理 · 抠图 · 换底合成
│   │   └── face.js                 # VisionKit 静态图人脸检测 + 证件照构图裁剪框
│   └── project.config.json
├── backend/                        # 服务端（云托管服务 dkmz-backend，Spring Boot 3.3 + MySQL）
│   ├── src/main/java/com/dkmz/photo/
│   │   ├── controller/             # SpecController（规格库下发/后台维护）、PhotoPayController（支付/权益）
│   │   ├── service/                # SpecService、PhotoPayService、EntitlementService、TokenManager
│   │   ├── entity/ · mapper/       # photo_spec / photo_pay_order / photo_pay_refund / photo_entitlement / photo_export
│   │   └── support/                # PaySignUtil（虚拟支付签名）、PushSignUtil（推送验签+XML）、OpenidSupport
│   └── src/main/resources/
│       ├── schema.sql              # 建表（全部 IF NOT EXISTS，可重复启动）
│       ├── specs-seed.json         # 规格库种子（由 docs/规格库-v0.1.json 生成）
│       └── application.yml         # 全部凭据走环境变量，默认留空
├── Dockerfile · docker/            # 云托管多阶段构建（Maven 国内镜像 + JRE 运行时）
├── models/                         # 模型权重（*.onnx 不进 git，见该目录 README）
├── tools/
│   └── gen-avatar.py               # 小程序头像处理（裁白边 + 去四角取景框 + 平面拟合补底）
├── assets/                         # 头像等上架素材
├── scripts/
│   ├── inspect-model.py            # ONNX 模型体检（算子在不在微信支持列表 / 输入输出 / 动态轴）
│   ├── serve-models.mjs            # 本地 HTTP 模型服务（0.0.0.0:5178，模拟器 127.0.0.1 / 真机走局域网 IP）
│   └── download-models.sh          # 重新下载模型权重
├── docs/
│   ├── 证件照小程序-落地方案.md      # 产品定位 / MVP / 技术方案 / 变现 / 上线前置 / 风险
│   ├── API.md                       # 后端接口文档（规格库下发 / 支付 / 权益 / 环境变量 / 表结构）
│   ├── 考试证件照规格库-v0.1.md      # 各考试报名照规格（核心资产）
│   ├── 考试证件照规格库-v0.1.json    # 同上，机器可读（后端种子来源）
│   └── 端上抠图验证手册.md           # 阶段 0 操作步骤 / 验收标准 / 排查表 / 实测记录（已回填）
├── project.config.json             # 开发者工具导入这个（miniprogramRoot: miniprogram/）
└── README.md
```

## 服务端部署（云托管服务 `dkmz-backend`）

> 与「考试倒计时」**各自独立的云托管环境**（不同 AppID 不能共用环境）。
> 本服务无定时任务，最小实例数可设 0（按量走，支付回调与用户请求唤醒）。

1. 云托管控制台为本小程序新建环境 → 新建服务 `dkmz-backend`，部署方式选「Git 仓库」指向本仓库 `Dockerfile`
2. 建 MySQL（建议独立库 `dkmz_photo`；表名已加 `photo_` 前缀，与 exam 库同实例也不冲突）
3. 配环境变量：`WX_APPID`、`WX_SECRET`、`SPRING_DATASOURCE_*`、`PHOTO_JOB_TOKEN`（随机串）
4. 部署后自测：`GET /api/photo/specs` 应返回 `ok:true` 且 `exams` 有 7 条（种子自动导入）
5. 虚拟支付开通后补：`XPHOTO_ENABLED=true`、`XPHOTO_OFFER_ID`、`XPHOTO_APP_KEY`、`XPHOTO_PUSH_TOKEN`、`XPHOTO_GOODS`
6. 云托管「消息推送」绑定到本服务的 `POST /api/photo/pay/notify`；再加定时任务调 `POST /api/photo/pay/recheck?token=<PHOTO_JOB_TOKEN>`（建议每 10 分钟）
7. 把环境 ID 填进 `miniprogram/utils/api.js` 的 `ENV_ID`

> 接口细节见 `docs/API.md`。

## 技术路线（端上处理，零 API 成本 + 原图不出手机）

| 环节 | 方案 |
|---|---|
| 人脸检测 / 自动构图 | VisionKit `wx.createVKSession({ track: { face: { mode: 2 } } })` 静态图模式（基础库 2.25.0+） |
| 人像抠图 | `wx.createInferenceSession` 跑 MODNet ONNX（基础库 2.30.0+），输出 alpha 蒙版 |
| 换底 / 合成 / 导出 | `wx.createOffscreenCanvas` 绘制 + `canvasToTempFilePath`；按目标体积反推 JPEG 质量 |
| 水印 | 只在端上叠加，付费后重导无水印高清 |
| 云端 | 仅规格库下发 + 虚拟支付回调；**用户原图不上传** |

**模型选型（已真机验证）**：生产用 **fp32** `model.onnx` 25MB（vivo 实测：加载 263ms / 384 短边全链路 498ms）。
⚠️ fp16 真机「Failed to convert onnx model」（微信 Android 转换器不支持 fp16 权重）；int8 动态量化含 `ConvInteger` 等**微信不支持**算子。体积优化后续走 QDQ 静态量化。

## 快速开始（阶段 0 验证）

```bash
# 1. 确保模型在位（首次或用 git clone 后）
bash scripts/download-models.sh

# 2. 起本地模型服务
node scripts/serve-models.mjs          # → http://127.0.0.1:5178/model_fp16.onnx
```

3. 微信开发者工具 → **导入项目** → 目录选 `D:/code/dkmz-photo`（不是 `miniprogram`）
   - AppID 填「登科喵」小程序的，没有就选「测试号」
   - 详情 → 本地设置 → 勾上「**不校验合法域名、web-view、TLS 版本以及 HTTPS 证书**」
4. 页面点「下载并加载模型」→ 选照片 → 抠图 → 换底，看耗时与边缘质量

详细步骤、验收标准、失败排查表、实测记录表见 **`docs/端上抠图验证手册.md`**。

## 待办

- [ ] 阶段 0 真机实测并回填数据（加载 / 推理 / 合成耗时、边缘质量、导出体积）
- [ ] 据实测结论定案：端上 / 端上初筛 + 云端精修 / 全云端
- [ ] 阶段 1：工程骨架 + 4 个页面 + 规格库下发 + 免费链路跑通
- [ ] 补 AppID 到 `project.config.json`（正式开发时需要）
- [ ] 上线前置：变更备案 → 开通虚拟支付 → 建道具 / 配发货推送

## 许可

抠图模型权重来自 MODNet 官方权重（Apache-2.0，经 HuggingFace `Xenova/modnet` 转 ONNX），
允许商用，需保留许可与版权声明。详见 `models/README.md`。
