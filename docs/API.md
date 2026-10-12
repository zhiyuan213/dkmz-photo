# 登科喵证件照 · 后端 API

服务名 `dkmz-backend`（Spring Boot 3.3 + MyBatis-Plus + MySQL 8），部署在微信云托管。
小程序 AppID：`wx3e149506a33d7e64`。

**Base URL**
- 容器调用（推荐）：`wx.cloud.callContainer({ config:{env:ENV_ID}, path, header:{'X-WX-SERVICE':'dkmz-backend'} })` —— 免域名白名单、免鉴权，云托管自动注入 `X-WX-OPENID`
- 公网域名：`https://<云托管默认域名>`（需在 mp 后台配 request 合法域名）

> ⚠️ 本服务**不需要常驻定时任务**，最小实例数可以设为 0（支付回调/用户请求唤醒），
> 虚拟支付的兜底查单交给云托管「定时任务」定期调 `POST /api/photo/pay/recheck`。

---

## 一、规格库（核心资产，云端可配）

规格库为什么要放云端：规格写错会让用户报名被驳回（比不提供更糟），
出问题必须**几分钟内**改掉，而不是等小程序发版审核。

### GET /api/photo/specs
整库下发。前端启动时拉取，失败则用随包内置库（三条防线：内置 → 本地缓存 → 云端）。

```json
{
  "ok": true,
  "version": "0.3.0",
  "updatedAt": "2026-10-11",
  "product": "登科喵证件照",
  "disclaimer": "各省、各报考点…请以当次官方公告为准。",
  "verifiedFrom": ["教育部教育考试院…"],
  "backgrounds": [{ "id": "white", "name": "白底", "hex": "#FFFFFF" }],
  "presets": [{ "id": "1inch", "name": "一寸", "px": [295, 413] }],
  "exams": [
    {
      "id": "kaoyan",
      "name": "考研",
      "aliases": ["硕士", "研究生"],
      "backgrounds": ["white", "blue"],
      "image": { "aspectRatio": "3:4", "recommendPx": [480, 640], "sizeRule": "max", "maxSizeKB": 10240 },
      "portrait": { "headShoulderRatio": "头部和肩部占画面 2/3" },
      "notes": ["近三个月内正面、免冠、无妆、彩色头像"]
    }
  ]
}
```

`ok=false, code=SPEC_EMPTY` 表示云端库为空（种子未导入成功）→ 前端用内置库。

### GET /api/photo/specs/version
只取版本号，前端先比版本再决定要不要全量拉。

### GET /api/photo/specs/{id}
单条规格（`kaoyan`、`ntce-certify`…），做增量刷新用。

### GET /api/photo/presets
通用预设尺寸列表（一寸/二寸…）。

### 管理端（需 `token=<PHOTO_JOB_TOKEN>`）

| 接口 | 用途 |
|---|---|
| `POST /api/photo/admin/spec-item?token=&id=&kind=&name=&updatedAt=` | **单条 upsert**，body 为该条目的完整 JSON。某考试改了要求时用这个，改动面最小 |
| `POST /api/photo/admin/specs?token=&overwrite=true` | 全量覆盖导入，body 结构同 `GET /api/photo/specs` |
| `POST /api/photo/admin/reseed?token=` | 重新导入随包内置种子（**只补缺失条目**，不覆盖后台改过的） |

> 示例：教资认定改了尺寸要求
> ```bash
> curl -X POST "$HOST/api/photo/admin/spec-item?token=$TOKEN&id=ntce-certify&kind=exam&updatedAt=2026-11-01" \
>   -H 'Content-Type: application/json' \
>   -d '{"id":"ntce-certify","name":"教资认定","image":{"recommendPx":[295,413],...}}'
> ```
> 改完前端下次启动（或下拉刷新首页）即生效，无需发版。

---

## 二、商品与支付

### 商品配置（环境变量 `XPHOTO_GOODS`）

格式 `goodsId:priceFen:quota:vipDays:name`，多项用 `;` 分隔。默认：

| goodsId | 价格 | 权益 | 说明 |
|---|---|---|---|
| `photo1` | ¥3.90 | 高清导出 1 张 | 单张解锁 |
| `photo3` | ¥6.90 | 高清导出 3 张 | 规格套装 |
| `vip1` | ¥19.90 | 会员 365 天 | 期内不限张数 |

> ⚠️ `goodsId` 与 `priceFen` 必须与 mp 后台「虚拟支付 → 道具管理」**完全一致**，否则拉起支付被微信拒绝。

### GET /api/photo/pay/goods
```json
{ "ok": true, "enabled": false, "goods": [{ "goodsId": "photo1", "name": "单张高清无水印导出", "priceFen": 390, "quota": 1, "vipDays": 0 }] }
```
`enabled=false` 时前端隐藏购买入口（支付未开通不报错，只降级）。

### POST /api/photo/pay/order
body：`{ "goodsId": "photo1", "code": "<wx.login 的 code>" }`

`code` **必需**：虚拟支付用户态签名要用 `session_key`，只能由后端用 code 走 `jscode2session` 换。

返回拉起支付三件套（`signData` 必须原样透传给 `wx.requestVirtualPayment`，不可二次序列化）：
```json
{ "ok": true, "orderId": "P20261012...", "mode": "short_series_goods", "env": 0,
  "signData": "{...}", "paySig": "...", "signature": "...", "goods": {...} }
```

### GET /api/photo/pay/order/{orderId}
本地订单状态，支付后用来自动确认到账（前端 `requestVirtualPayment` 的 success 只算弱确认）。

### GET /api/photo/pay/my-orders
当前用户订单列表（云托管注入 openid）。**订单中心页 `pages/orders/orders` 的数据源**。

### POST /api/photo/pay/notify
微信发货/退款推送入口（`GET` 用于推送 URL 校验，原样回 `echostr`）。按 `Event` 分派：

| Event | 处理 |
|---|---|
| `xpay_goods_deliver_notify` | 发货：CAS 抢占订单 → 发权益（张数 / 会员天数） |
| `xpay_refund_notify` | 退款：置 `REFUNDED` + **回收权益**（微信不会替我们扣回） |
| `xpay_subscribe_ios_refund_query_notify` | iOS 退款问询，3 秒内应答（已发货→拒绝，未发货→建议退款） |

**幂等三道闸**：① 订单已 `DELIVERED` ② 同一微信平台单号已发过货 ③ 发货用
`UPDATE ... WHERE status IN ('INIT','PAID')` 的 CAS，只有抢到的通道才发权益。
**无签名推送不直接拒**（云托管转发的微信推送不带签名），改为调 `/xpay/query_order`
核验确已支付后才发货，避免伪造推送白拿权益。

### POST /api/photo/pay/recheck?token=
兜底查单：补发近 6 小时未发货的订单 + 回收微信侧已退款的权益。挂云托管定时任务。

### GET /api/photo/pay/admin/overview?token=
订单总览（各状态计数 + 最近 30 单，openid 已打码）。

---

## 三、用户权益

**模型：免费体验 1 张 + 已购张数（消耗型）+ 会员有效期**

与「考试倒计时」的额度语义不同：那边是「可同时管理的提醒数」（删了就释放，实时计算）；
这边导出是**一次性动作**，必须落流水 + 扣减，否则用户重装小程序就能无限导出。

### GET /api/photo/entitlement
```json
{ "ok": true, "freeQuota": 1, "paidQuota": 0, "vipActive": false,
  "vipExpireAt": null, "remaining": 1, "canExport": true }
```
`vipActive=true` 时 `remaining=-1` 表示不限张数。

### POST /api/photo/entitlement/consume
body：`{ "clientNo": "E1759...", "specId": "kaoyan", "specOk": true }`

- `clientNo` 由前端一次导出动作生成一个并**复用**（重试传同一个）→ 后端 `photo_export.client_no` 唯一键保证不重复扣减
- 返回 `ok=false, code=QUOTA_EXHAUSTED` → 前端引导购买
- 消耗优先级：**会员 > 免费张数 > 已购张数**

### POST /api/photo/pay/admin/entitlement?token=&openid=&paidQuota=&vipDays=
管理端权益修数（客服补偿用）。`paidQuota` 直接设定张数；`vipDays` 为从现在起算的天数，`≤0` 表示清除会员。

---

## 三B、云端精修（腾讯云 BDA 人像分割）

### POST /api/photo/segment
body：`{ "image": "<base64 或 dataURL>", "beauty": false }`

- `image`：base64 ≤5M，分辨率 <2000×2000（超限服务端自动压缩到长边 1920）
- `beauty`：可选，`true` 时先做保守美肤（肤色提亮+磨皮，作用区域 = 自定义分割
  `SegmentCustomizedPortraitPic` 的 **Face-only** mask，不含五官/头发）再抠图；
  需后端 `BDAB_BEAUTY_ENABLED=true`，美肤失败自动退化为纯抠图，不阻断
- ⚠️ 美肤必须在**抠图前**做（作用于 RGB 原图）：透明底 PNG 进 Java ImageIO
  会被铺黑且丢失 alpha，参考实现里「先抠图再美肤」的 `/process` 顺序有此 bug，已规避

返回：
- 成功 `{ ok:true, image:"<透明底PNG base64>", beautyApplied:bool, costMs }`
- 未开通 `{ ok:false, code:"BDA_DISABLED", msg }`
- 失败 `{ ok:false, code:"BAD_REQUEST"|"BAD_IMAGE"|"IMAGE_TOO_LARGE"|"BDA_FAIL", msg }`

防刷（2026-10-13 起）：
- **必须携带微信侧注入的用户身份**（`X-WX-OPENID` / `X-WX-FROM-OPENID`，云托管
  只对小程序 callContainer 通道注入）：公网直连拿不到 ⇒ `{ok:false, code:"NO_OPENID"}`
- **滑动窗口限流**（内存实现，阈值见 application.yml `photo.segment.rate.*`）：
  单用户 6 次/分钟、60 次/天；全局 30 次/分钟、900 次/天；超限
  `{ok:false, code:"RATE_LIMITED", msg}`，前端自动回退端上抠图
- 局限：公网直连可伪造身份头绕开单用户档（全局档仍兜底）；
  **彻底防刷 = 云托管控制台关闭「公网访问」**（callContainer 走内网不受影响）

可靠性：偶发 `ServerError/RequestTimeout/UnKnowError` 自动重试 1 次；
SDK 错误码已翻译成中文（分辨率超限/无人脸/欠费等可直接 toast）。
日志严禁打印图片 base64（SDK 日志级别已收敛到 WARN）。

调用 `SegmentPortraitPic`（Scene=GEN，RspImgType=base64）。前端在用户开启
「云端精修」时调用，拿到透明底 PNG 后在端上重建 alpha 继续换底合成；
**任何失败一律回退端上 MODNet 抠图**，出片不中断。

环境变量（不配置 = 功能关闭，不影响其他接口）：

| 变量 | 说明 |
|---|---|
| `BDAB_ENABLED` | 总开关（配置好密钥后置 `true`） |
| `BDAB_SECRET_ID` / `BDAB_SECRET_KEY` | 腾讯云 API 密钥（建议子账号仅授权 bda，`QcloudBDAAccess`） |
| `BDAB_REGION` | 仅 `ap-beijing` / `ap-guangzhou` / `ap-shanghai`，默认广州 |
| `BDAB_ENDPOINT` | 可选。云托管容器直连 https 报 PKIX 时，试 `bda.internal.tencentcloudapi.com`（限同地域 VPC） |
| `BDAB_BEAUTY_ENABLED` | 美肤开关（每次美肤+抠图消耗 2 次 BDA 调用，共享免费额度） |

计费：每接口每月 1000 次免费额度，超出后付费约 0.01 元/次。
隐私：开启后照片上传腾讯云做分割，隐私政策页已披露（「云端精修」条目）。

---

## 四、环境变量（云托管控制台配置）

| 变量 | 说明 |
|---|---|
| `WX_APPID` / `WX_SECRET` | 小程序 AppID/AppSecret（`wx3e149506a33d7e64`） |
| `WX_CLOUD_CALL` | `true`（默认）走云托管云调用通道，本地开发设 `false` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | MySQL 连接（建议独立库 `dkmz_photo`） |
| `PHOTO_JOB_TOKEN` | 运维接口口令（规格维护、兜底查单、订单总览、权益修数） |
| `XPHOTO_ENABLED` | 虚拟支付总开关（开通并自测通过后置 `true`） |
| `XPHOTO_OFFER_ID` | mp 后台「虚拟支付 → 基础配置」的 OfferID |
| `XPHOTO_APP_KEY` | 现网 AppKey（**密钥**，只配环境变量） |
| `XPHOTO_ENV` | `0` 现网 / `1` 沙箱 |
| `XPHOTO_PUSH_TOKEN` | mp 后台消息推送 Token（校验推送来源） |
| `XPHOTO_GOODS` | 商品配置（见上） |
| `BDAB_ENABLED` | 云端精修（BDA 人像分割）总开关 |
| `BDAB_SECRET_ID` / `BDAB_SECRET_KEY` | 腾讯云 API 密钥（见「三B」节） |
| `BDAB_REGION` / `BDAB_ENDPOINT` | BDA 地域 / 覆盖接入域名（见「三B」节） |

连接串完整示例（**下方为云托管现网实际跑通的串**，仅主机按内网地址替换）：

```
jdbc:mysql://<内网地址>:3306/dkmz_photo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
```

两个易踩的点：

- `characterEncoding` 写的是 **Java 字符集名**，`utf8` 与 `UTF-8` 等价；**写成 `utf8mb4` 会让驱动抛
  `Unsupported character encoding 'utf8mb4'` 导致服务启动失败**（`utf8mb4` 是 MySQL 的字符集名，不是 Java 的）
- 若遇到 `Public Key Retrieval is not allowed` / `CommunicationsException`，
  再追加 `&useSSL=false&allowPublicKeyRetrieval=true`（现网连接串**未使用**这两个参数）

## 五、数据库

启动时执行 `backend/src/main/resources/schema.sql`（`sql.init.mode=always`），
**只允许幂等语句**（`CREATE TABLE IF NOT EXISTS`）—— 写 `ALTER TABLE ADD COLUMN`
会因第二次启动「列已存在」而**导致服务起不来**（考试倒计时项目踩过此坑）。

| 表 | 说明 |
|---|---|
| `photo_spec` | 规格库（`kind` = meta/background/preset/exam，`payload` 为条目 JSON） |
| `photo_pay_order` | 支付订单（主键 = 业务订单号，微信推送靠它回捞） |
| `photo_pay_refund` | 退款流水（审计留痕，`wx_refund_id` 幂等） |
| `photo_entitlement` | 用户权益（免费张数 / 已购张数 / 会员到期） |
| `photo_export` | 导出流水（`client_no` 唯一 = 扣减幂等键） |

种子数据：启动时若 `photo_spec` 为空，从 `classpath:specs-seed.json` 导入（由
`docs/考试证件照规格库-v0.1.json` 生成）。
