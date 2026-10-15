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

> **v0.5.0 结构升级**：去掉了「考试（exam）」维度，`presets` + `exams` 合并为 `sizes`。
> 用户不再先选场景，而是直接选**底色 + 尺寸**；原来挂在考试上的规格知识以
> `size.usedBy` 场景标注的形式保留，体积上限则取原场景里**最严**的那个。

#### 模型下载地址（响应里的 `model` 字段）

端上人像抠图模型（MODNet / ONNX，25MB）**打不进小程序包**（单包上限 2MB），
只能运行时用 `wx.downloadFile` 下载，所以它的下载地址必须是一个
**公网可访问、已 ICP 备案的 https 地址**。

这个地址**不写死在端上**，而是随本接口下发（`model.url`），原因是：
正式版小程序要求下载域名已备案并配进「downloadFile 合法域名」，
地址一旦写死，换 CDN / 换模型就得重新发版审核。

服务端配置（云托管环境变量）：

| 变量 | 说明 |
|---|---|
| `XPHOTO_MODEL_URL` | 模型下载的**绝对 https 地址**，如 `https://<云托管公网域名>/models/modnet-fp32.onnx`。为空则不下发，端上退回内置兜底（仅本地联调，**生产必须配**） |
| `XPHOTO_MODEL_FILE` | 模型文件名，默认 `modnet-fp32.onnx`；容器内对应 `classpath:/static/models/<file-name>` |

模型文件已随镜像部署在 `backend/src/main/resources/static/models/modnet-fp32.onnx`，
公网访问路径即 `/models/modnet-fp32.onnx`（Spring Boot 默认静态资源映射，无需额外路由）。

> ⚠️ **踩过的坑（2026-10-15 线上事故）**：模型地址最初写死在端上，
> 且指向开发机局域网 IP `http://192.168.21.11:5178`，发布后真机一律
> `ERR_CONNECTION_REFUSED` → 制作页「模型准备失败」。现已改为服务端下发，
> 端上代码里**不允许**再出现任何写死的下载地址（本地联调走 `matting.js` 的 `DEV_MODEL_URL`）。

> 💡 换 CDN / 换模型：改 `XPHOTO_MODEL_URL` + 重启服务即可，**不用重新发布小程序**。
> 若换的是文件名或权重，记得同时把文件放进 `static/models/` 并改 `XPHOTO_MODEL_FILE`。

```json
{
  "ok": true,
  "version": "0.5.0",
  "updatedAt": "2026-10-14",
  "product": "登科喵证件照",
  "disclaimer": "各省、各报考点…请以当次官方公告为准。",
  "verifiedFrom": ["研招网 2026 年…网上确认公告"],
  "model": { "fileName": "modnet-fp32.onnx", "url": "https://<云托管公网域名>/models/modnet-fp32.onnx" },
  "backgrounds": [{ "id": "white", "name": "白底", "rgb": [255, 255, 255] }],
  "sizes": [
    {
      "id": "one-inch",
      "name": "一寸",
      "mm": [25, 35],
      "px": [295, 413],
      "sizeRule": "range",
      "minSizeKB": 20,
      "maxSizeKB": 100,
      "sizeNote": "20~100KB（取国考最严要求，可通用于教资认定、四六级）",
      "usedBy": ["国考", "教资认定", "四六级"]
    }
  ]
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `px` | 输出像素（pipeline 直接按它裁剪导出，故结果页「像素尺寸」必然通过） |
| `mm` | 打印毫米尺寸，可为 `null` |
| `sizeRule` | `max` 只约束上限 / `range` 区间 / `none` 不限 |
| `sizeNote` | 结果页直接展示的体积说明，**必须写清上限来源** |
| `usedBy` | 场景标注（原考试库留下来的规格知识），首页尺寸卡片副标题 |
| `cropFaceRatio` | 可选，构图时人脸占画面高度比例，默认 0.45（驾驶证为 0.5） |

> ⚠️ 云端下发的底色是 `{ id, name, rgb }`，前端 `normalizeBackground` 在唯一入口把它
> 换算成 `hex` 再进内存 —— 整条链路（色块、导出、`hexToRgb`）用的都是 hex。

`ok=false, code=SPEC_EMPTY` 表示云端库为空（种子未导入成功）→ 前端用内置库。

### GET /api/photo/specs/version
只取版本号，前端先比版本再决定要不要全量拉。

### GET /api/photo/specs/{id}
单条规格（`one-inch`、`driving-license`…），做增量刷新用。

### GET /api/photo/sizes
尺寸列表。原 `/api/photo/presets` 已随 v0.5.0 结构升级合并到这里。

### 管理端（需 `token=<PHOTO_JOB_TOKEN>`）

| 接口 | 用途 |
|---|---|
| `POST /api/photo/admin/spec-item?token=&id=&kind=&name=&updatedAt=` | **单条 upsert**，body 为该条目的完整 JSON（`kind` 取 `background` / `size`）。某个尺寸改了要求时用这个，改动面最小 |
| `POST /api/photo/admin/specs?token=&overwrite=true` | 全量覆盖导入，body 结构同 `GET /api/photo/specs` |
| `POST /api/photo/admin/reseed?token=` | 重新导入随包内置种子（**只补缺失条目**，不覆盖后台改过的） |

**结构升级怎么生效**（`SpecService`）：种子只在空表时导入，所以老库非空时新结构进不去。
启动时额外做三件事，缺一不可：
1. `purgeRetired()` —— 删 `RETIRED_IDS` 里的行（曾用于下线红底；**红底已恢复，`"red"` 必须不在名单里**，否则每次启动都被删掉）
2. `purgeRetiredKinds()` —— 删 `preset` / `exam` 两种旧 kind 的全部行
3. `syncSchemaIfNeeded()` —— 随包 `meta.schemaVersion` 与库里不一致时做一次 `overwrite=true` 全量重导

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

格式 `goodsId:priceFen:quota:vipDays:name`，多项用 `;` 分隔。默认（2026-10-14 调价）：

| goodsId | 价格 | 权益 | 说明 |
|---|---|---|---|
| `photo5` | ¥3.90 | 高清导出 5 张 | 约 ¥0.78/张 |
| `photo10` | ¥6.90 | 高清导出 10 张 | 约 ¥0.69/张 |

> 原 `photo1`（¥3.90/1 张）、`photo3`（¥6.90/3 张）已下架，`vip1` 年会员**也已下架**。
> ⚠️ 但**后端的会员能力必须保留**（`vip_expire_at` / `addVipDays` / `reduceVipDays` /
> `view` 的 `remaining=-1`）：已购年卡的老用户仍在有效期内，历史订单退款也要走这条路径。
> 下架只是不再出现在 `GET /api/photo/pay/goods` 里，不是把能力删掉。
> 同理，`pages/buy` 的 `benefitText` 与 `pages/orders` 的会员分支都保留兼容。

> ⚠️ `goodsId` 与 `priceFen` 必须与 mp 后台「虚拟支付 → 道具管理」**完全一致**，否则拉起支付被微信拒绝。
> 改 `goodsId`（`photo1`→`photo5`）时后台道具要同步新建，否则要等虚拟支付开通后一起配。

#### 线上配置与调价流程（2026-10-15 起）

线上**以云托管环境变量 `XPHOTO_GOODS` 为准**，不依赖代码里的默认值：调价、增删套餐都
不用改代码 / 重新构建，只动变量 + 重启服务即可。

云托管当前值（环境 `dkmz-backend` → 服务 `dkmz-backend` → 环境变量）：

```
photo5:390:5:0:高清导出 5 次;photo10:690:10:0:高清导出 10 次
```

**改价三步（均无需发包）：**

1. 云托管改 `XPHOTO_GOODS` 里的 `priceFen` / `quota`（例：`390` → `290`）
2. mp 后台「道具管理」把对应道具价格同步改成一致（微信要求两者严格相同）
3. **重启 / 重新部署服务** —— 环境变量在容器启动时注入，不重启不生效

**格式自检（最容易踩的坑）：**

- 分段符必须是**英文冒号 `:`**、多项分隔必须是**英文分号 `;`**——因为 `name` 是中文，
  很容易手滑打成全角 `：` `；`，整串会解析失败（表现为商品列表为空或退回默认值）
- `priceFen` 单位是**分**（`390` = ¥3.90），不是元
- 张数包的 `vipDays` 填 `0`；只有会员类商品才填天数
- 新增套餐：在串尾追加 `;photo20:990:20:0:高清导出 20 次`，并在后台建同名同价道具

> 注意：**每日免费额度不是环境变量**，它是 Java 常量 `EntitlementService.DEFAULT_FREE_QUOTA = 1`，
> 要改必须改代码重新部署。商品之外的其它开关（`XPHOTO_ENABLED`、`REVIEW_MODE`、`BDAB_*` 等）
> 仍按环境变量配置，见第六节清单。

> 💰 成本提醒：这个价位（¥0.69~0.78/次）对腾讯云 BDA（≈¥0.01/次）没问题，
> 但**拍博士 API 是 ¥1/次**——若把「一次返回 8 张」这类第三方能力混进这套定价，会亏本，必须单独定价。

### GET /api/photo/pay/goods
```json
{ "ok": true, "enabled": false, "goods": [{ "goodsId": "photo5", "name": "高清导出 5 次", "priceFen": 390, "quota": 5, "vipDays": 0 }] }
```
`enabled=false` 时前端隐藏购买入口（支付未开通不报错，只降级）。

### POST /api/photo/pay/order
body：`{ "goodsId": "photo5", "code": "<wx.login 的 code>" }`

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

**模型：每日免费 1 张 + 已购张数（消耗型）+ 会员有效期（已下架商品，能力保留）**

与「考试倒计时」的额度语义不同：那边是「可同时管理的提醒数」（删了就释放，实时计算）；
这边导出是**一次性动作**，必须落流水 + 扣减，否则用户重装小程序就能无限导出。

### 免费额度：每天 1 张（2026-10-14 变更）

**原来是「一次性送 1 张」（用完永久没有），现在改为「每天送 1 张」。**

实现方式是**懒发放**，不是定时任务（本服务最小实例数可设 0，没有调度器）：
`EntitlementService.ensure()` 每次被调用时比对业务日（`Asia/Shanghai`），
发现 `photo_free_grant.last_grant_date` 不是今天就补发一次 —— 服务整天没人访问也无所谓，
下一个请求进来时补上。

**并发安全**（两个请求同时进来只发一次）：先做条件 UPDATE
`WHERE openid=? AND last_grant_date <> 今天`，`affected>0` 才算本次发放；
`affected=0` 再尝试 INSERT，主键冲突 ⇒ 今天已发过。不用「先查再写」。

**免费与已购分开记录**：`photo_entitlement` 里 `free_quota` 与 `paid_quota` 本就是两列，
每日重置只写 `free_quota`，**绝不触碰 `paid_quota`**。

> 为什么单独建 `photo_free_grant` 表：`schema.sql` 在服务每次启动时执行，文件开头明确禁止
> `ALTER TABLE ADD COLUMN`（第二次启动就因「列已存在」失败，整个服务起不来），
> 所以「给 `photo_entitlement` 加一列」这条路走不通。

### GET /api/photo/entitlement
```json
{ "ok": true, "freeQuota": 1, "freeDailyQuota": 1, "freeUsedToday": 0,
  "paidQuota": 0, "vipActive": false, "vipExpireAt": null,
  "remaining": 1, "canExport": true }
```
- `freeQuota` = **今天还剩几次免费**（0 或 1），每天 0 点后自动补回
- `freeDailyQuota` 每日赠送额度（当前 1），前端不必硬编码
- `freeUsedToday` 今日已用的免费次数，便于界面展示 `x / 1`
- `vipActive=true` 时 `remaining=-1` 表示不限张数

### POST /api/photo/entitlement/consume
body：`{ "clientNo": "E1759...", "specId": "one-inch", "specOk": true }`

- `clientNo` 由前端一次导出动作生成一个并**复用**（重试传同一个）→ 后端 `photo_export.client_no` 唯一键保证不重复扣减
- 返回 `ok=false, code=QUOTA_EXHAUSTED` → 前端引导购买
- 消耗优先级：**会员 > 当日免费 > 已购张数**（`source` 回传 `vip` / `free` / `paid`，前端据此提示用户花掉的是哪一份）
- 返回体同时带上最新的 `freeQuota` / `paidQuota` / `remaining`，前端不必再拉一次

### POST /api/photo/pay/admin/entitlement?token=&openid=&paidQuota=&vipDays=
管理端权益修数（客服补偿用）。`paidQuota` 直接设定张数；`vipDays` 为从现在起算的天数，`≤0` 表示清除会员。

> 注意：管理端**不提供**修改 `freeQuota` 的口子 —— 它是每日派生的，手改会在下一个请求被覆盖掉。

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
- **内容违规** `{ ok:false, code:"RISKY_CONTENT", msg:"图片内容涉嫌违规，请更换照片后重试" }`
  —— 2026-10-15 审核整改项：用户上传图片属 UGC，进云端处理前必须先过微信
  `security.mediaCheckAsync`；端上收到此码**不得回退本地抠图**，直接中断并提示
- 检测不可用 `{ ok:false, code:"SEC_CHECK_UNAVAILABLE", msg }` —— fail-closed，一并拒绝
- 失败 `{ ok:false, code:"BAD_REQUEST"|"BAD_IMAGE"|"IMAGE_TOO_LARGE"|"BDA_FAIL", msg }`

内容安全（2026-10-15 起，两个收图接口 `/api/photo/segment` 与 `/idphoto/process` 同策略）：

⚠️ **接口选型**：旧的同步接口 `security.imgSecCheck`（`/wxa/img_sec_check`）**已于
2021-09-01 停止维护、当前不可用**（固定报 errcode 47001），不要改回去。现用异步接口
`security.mediaCheckAsync`（`/wxa/media_check_async`）。

流程（对上层保持同步语义）：
1. 提交时把待检图暂存内存（128 位随机 token，10 分钟过期），暴露公网只读地址
   `GET /api/photo/sec-check/media/{token}` 供**微信服务器回拉**（media_url 必须公网可访问）
2. 调 `media_check_async`（`media_type=2`、`version=2`、`scene=1`、**openid 必填**）→ 得 `trace_id`
   · ⚠️ v2 接口 `media_type`：**1 = 音频，2 = 图片**（v1 相反，别照老文档写 1）
   · ⚠️ `openid` 为必填项（且该用户近两小时访问过小程序）；两个入口都传
     `PhotoUserVerifier.verify()` 得到的真实 openid，**不能用可能为空的请求头**
3. 微信异步检测完，把结果推送到 mp 后台配置的「消息推送」地址
   （事件 `wxa_media_check`，30 分钟内到达）→ `POST /api/photo/pay/notify` 按 Event 分派给
   `WxSecCheckService.acceptPush`（与虚拟支付推送同一个 URL）
4. 服务在原地轮询等结果，默认最长 `SEC_CHECK_WAIT_MS=10000` 毫秒

结果语义（`suggest` 依次从 `result` → 顶层 → `detail[0]` 取，`result` 是官方的综合结果）：
`pass` 放行 / `risky` 拦截 / `review` 也按拦截处理。
通道异常或超时未回结果 → fail-closed 拒绝；**微信明确报错（errcode≠0）立即上抛**，
不当成「结果没到」，避免白等满等待时间再给错误提示：
- `errcode=-1008` → 微信下载 media_url 失败，检查 `XPHOTO_PUBLIC_BASE_URL` 是否公网可访问
- 解析 detail 时必须按节点类型处理：XML 推送经 `PushSignUtil.xmlToJson` 后
  `<detail>` 是**嵌套对象/对象数组**，用 `asText()` 取会恒得空串 ⇒ 每次都超时

前置配置（缺一不可）：
| 项 | 位置 |
|---|---|
| 开「开放接口服务」 | 云托管控制台 → 服务 → 服务管理 → 云调用 |
| 登记接口 `/wxa/media_check_async` | 同上 → 微信令牌权限配置（只填路径，一行一个，保存后全环境生效） |
| 消息推送指向 `/api/photo/pay/notify` | mp 后台 → 开发管理 → 消息推送（**加解密方式选明文或兼容**，本项目未实现 AES 解密） |
| `XPHOTO_PUBLIC_BASE_URL` | 云托管环境变量 = 本服务公网基址（末尾不带 `/`） |
| 开完开关后**重新构建部署** | 否则对已有版本不生效（官方明确要求） |

运维自检（不用真机拍照即可验证全链路）：
`GET /api/photo/sec-check/selftest?token=<PHOTO_JOB_TOKEN>&openid=<真实openid>`
· 返回里 `received=true` 表示推送链路已通（微信确实把结果推到了 notify）；
`ok=true` 表示检测本身成功。两者分开，便于区分「链路没配好」与「微信拉不到图」。
· openid 也可由云托管注入的 `X-WX-OPENID` 头带入（用 callContainer 调用时）。

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

调用 `SegmentPortraitPic`（Scene=GEN，RspImgType=base64）。**出片只有云端这一条通道**：
任何失败都直接返回错误、由页面明确提示，**不再回退端上 MODNet 抠图**
（降级会给出一致的成片问题，且会绕过内容安全检测；端上推理仅留作开发者自检）。

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
