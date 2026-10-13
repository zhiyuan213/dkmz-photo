-- ============================================================
-- 登科喵证件照 · 数据库结构
--
-- ⚠️ 本文件在服务**每次启动**时执行（spring.sql.init.mode=always），
--    因此只允许「幂等」语句：CREATE TABLE IF NOT EXISTS / INSERT IGNORE。
--    绝对不要写 ALTER TABLE ADD COLUMN —— 第二次启动就会因「列已存在」失败，
--    导致整个服务起不来（exam-countdown 项目踩过这个坑，需加列时请新建表或手工执行迁移）。
--
-- 表名统一 photo_ 前缀：即使与 exam-countdown 共用一个 MySQL 实例也不会撞名。
-- ============================================================

-- ------------------------------------------------------------
-- 规格库（核心资产，必须能紧急修正）
--   kind: meta 库级元信息 / background 底色 / preset 预设尺寸 / exam 考试规格
--   payload: 条目自身的完整 JSON（结构与小程序端 utils/specs.js 一致，原样下发）
-- 种子数据由应用启动时从 classpath:specs-seed.json 导入（仅空表时执行一次）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS photo_spec (
  id          VARCHAR(64)  NOT NULL COMMENT '条目 ID：meta / 底色 id / 预设 id / 考试 id（如 kaoyan）',
  kind        VARCHAR(16)  NOT NULL COMMENT 'meta / background / preset / exam',
  name        VARCHAR(128) DEFAULT NULL COMMENT '展示名（后台列表用）',
  payload     MEDIUMTEXT   NOT NULL COMMENT '条目 JSON（原样下发）',
  sort_no     INT          NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
  updated_at  VARCHAR(16)  DEFAULT NULL COMMENT '该条规格的核实/更新日期（可单独标注）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modified_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_kind_sort (kind, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证件照规格库';

-- ------------------------------------------------------------
-- 虚拟支付订单（道具直购）
-- 主键 outTradeNo 即下发给微信的业务订单号，发货推送靠它回捞订单。
-- 状态：INIT 已下单未支付 / DELIVERED 已发货（权益已到账）/ REFUNDED 已退款（权益已回收）
-- 幂等：① status 已是 DELIVERED 直接跳过 ② wx_order_id（微信平台单号）二次去重
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS photo_pay_order (
  out_trade_no   VARCHAR(64)  NOT NULL COMMENT '业务订单号 P+时间戳+随机',
  openid         VARCHAR(64)  NOT NULL COMMENT '付款用户 openid',
  product_id     VARCHAR(64)  NOT NULL COMMENT '道具 ID（与 mp 后台道具管理一致）',
  goods_name     VARCHAR(128) DEFAULT NULL COMMENT '商品展示名',
  quota_delta    INT          NOT NULL DEFAULT 0 COMMENT '本单发放的高清导出张数',
  vip_days       INT          NOT NULL DEFAULT 0 COMMENT '本单发放的会员天数',
  quantity       INT          NOT NULL DEFAULT 1 COMMENT '购买数量',
  price_fen      INT          NOT NULL DEFAULT 0 COMMENT '道具单价（分）',
  actual_fen     INT          DEFAULT NULL COMMENT '实付金额（分，来自回调）',
  env            INT          NOT NULL DEFAULT 0 COMMENT '0 现网 1 沙箱',
  status         VARCHAR(16)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/DELIVERED/REFUNDED',
  wx_order_id    VARCHAR(64)  DEFAULT NULL COMMENT '微信平台单号 WeChatPayInfo.MchOrderNo',
  transaction_id VARCHAR(64)  DEFAULT NULL COMMENT '微信支付交易号',
  attach         VARCHAR(255) DEFAULT NULL COMMENT '透传 openid|goodsId|quota|vipDays',
  notify_count   INT          NOT NULL DEFAULT 0 COMMENT '收到发货推送次数',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at        DATETIME     DEFAULT NULL,
  delivered_at   DATETIME     DEFAULT NULL,
  PRIMARY KEY (out_trade_no),
  KEY idx_openid_created (openid, created_at),
  KEY idx_status_created (status, created_at),
  KEY idx_wx_order (wx_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟支付订单表';

-- ------------------------------------------------------------
-- 退款流水（审计留痕）。同 (wx_refund_id, ret_code) 只落一条，重复推送不产生多条。
-- 不合并进 photo_pay_order：schema.sql 每次启动执行，给老表加列不幂等。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS photo_pay_refund (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  out_trade_no      VARCHAR(64)  NOT NULL COMMENT '业务订单号（对应 photo_pay_order.out_trade_no）',
  openid            VARCHAR(64)  DEFAULT NULL COMMENT '退款用户 openid',
  wx_refund_id      VARCHAR(64)  DEFAULT NULL COMMENT '微信退款单号 WxRefundId',
  mch_refund_id     VARCHAR(64)  DEFAULT NULL COMMENT '商户退款单号 MchRefundId',
  wx_order_id       VARCHAR(64)  DEFAULT NULL COMMENT '退款单对应的微信订单号 WxOrderId',
  refund_fee        INT          NOT NULL DEFAULT 0 COMMENT '退款金额（分）',
  ret_code          INT          DEFAULT NULL COMMENT '退款状态 0 成功 非 0 失败',
  ret_msg           VARCHAR(255) DEFAULT NULL COMMENT '退款结果说明',
  quota_reduced     INT          NOT NULL DEFAULT 0 COMMENT '本次回收的张数（未发货订单为 0）',
  vip_days_reduced  INT          NOT NULL DEFAULT 0 COMMENT '本次回收的会员天数',
  succ_at           DATETIME     DEFAULT NULL COMMENT '退款完成时间',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (out_trade_no),
  KEY idx_wx_refund (wx_refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟支付退款记录表';

-- ------------------------------------------------------------
-- 用户权益：免费体验张数 + 已购张数 + 会员有效期
-- 注意是「消耗型」：导出一次扣一张（会员期内不扣），与 exam-countdown 的
-- 「可同时管理的提醒数」（删了就释放）语义不同，不能照搬实时计算的做法。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS photo_entitlement (
  openid        VARCHAR(64) PRIMARY KEY COMMENT '用户 openid',
  free_quota    INT NOT NULL DEFAULT 1 COMMENT '免费体验张数（默认 1，让用户先看到无水印成品）',
  paid_quota    INT NOT NULL DEFAULT 0 COMMENT '已购剩余张数（消耗型，导出原子自减）',
  vip_expire_at DATETIME DEFAULT NULL COMMENT '会员到期时间（null=从未购买）',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户权益表';

-- ------------------------------------------------------------
-- 导出流水：client_no 唯一 → 网络重试 / 连点不会重复扣减张数
-- 同时沉淀「用户导出的是哪个规格、规格校验有没有过」的数据，
-- 用于后续优化规格库（哪个考试最常被判不合格）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS photo_export (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_no  VARCHAR(64) NOT NULL COMMENT '前端生成的幂等号（一次导出动作一个）',
  openid     VARCHAR(64) NOT NULL,
  spec_id    VARCHAR(64) DEFAULT NULL COMMENT '导出所用规格（exam id / preset id）',
  source     VARCHAR(16) DEFAULT NULL COMMENT '本次消耗来源：free / paid / vip',
  spec_ok    TINYINT(1)  DEFAULT NULL COMMENT '规格校验是否通过（未通过也允许导出，仅统计）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_client_no (client_no),
  KEY idx_openid_created (openid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='高清导出流水（含幂等键）';
-- 制作限流：仅保存用户摘要和请求时间，不保存照片。
CREATE TABLE IF NOT EXISTS photo_processing_guard (
  id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO photo_processing_guard(id) VALUES (1);

CREATE TABLE IF NOT EXISTS photo_processing_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  INDEX idx_processing_time(created_at),
  INDEX idx_processing_user_time(user_hash, created_at)
) ENGINE=InnoDB;
