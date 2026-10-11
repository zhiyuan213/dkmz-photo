package com.dkmz.photo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dkmz.photo.entity.PhotoEntitlement;
import com.dkmz.photo.entity.PhotoExport;
import com.dkmz.photo.mapper.PhotoEntitlementMapper;
import com.dkmz.photo.mapper.PhotoExportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户权益：免费体验张数 + 已购张数 + 会员有效期。
 *
 * 为什么张数用「消耗型 + 原子自减」而不是像 exam-countdown 那样实时计算：
 * 提醒额度是「同时存在的提醒数」（删了就释放），而导出是**一次性动作**，
 * 做过一次就永久拥有那张图 —— 所以必须落流水（photo_export）并扣减，
 * 否则用户重装小程序就能无限导出。
 *
 * 幂等：photo_export.client_no 唯一键。重复请求（网络重试/连点）不会重复扣减。
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    /** 免费体验张数：让用户在真机上先看到「无水印成品」长什么样，降低付费决策门槛 */
    public static final int DEFAULT_FREE_QUOTA = 1;

    private final PhotoEntitlementMapper entMapper;
    private final PhotoExportMapper exportMapper;

    public EntitlementService(PhotoEntitlementMapper entMapper, PhotoExportMapper exportMapper) {
        this.entMapper = entMapper;
        this.exportMapper = exportMapper;
    }

    /** 取权益记录，没有就建（默认 1 张免费体验） */
    public PhotoEntitlement ensure(String openid) {
        PhotoEntitlement e = entMapper.selectById(openid);
        if (e == null) {
            e = new PhotoEntitlement();
            e.setOpenid(openid);
            e.setFreeQuota(DEFAULT_FREE_QUOTA);
            e.setPaidQuota(0);
            try {
                entMapper.insert(e);
            } catch (Exception ex) {
                log.warn("photo_entitlement 首次创建冲突，回读: {}", ex.toString());
                e = entMapper.selectById(openid);
                if (e == null) {
                    throw ex;
                }
            }
        }
        if (e.getFreeQuota() == null) {
            e.setFreeQuota(DEFAULT_FREE_QUOTA);
        }
        if (e.getPaidQuota() == null) {
            e.setPaidQuota(0);
        }
        return e;
    }

    /** 会员是否在有效期内 */
    public static boolean vipActive(PhotoEntitlement e) {
        return e != null && e.getVipExpireAt() != null && e.getVipExpireAt().isAfter(LocalDateTime.now());
    }

    public Map<String, Object> view(String openid) {
        PhotoEntitlement e = ensure(openid);
        boolean vip = vipActive(e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("freeQuota", e.getFreeQuota());
        out.put("paidQuota", e.getPaidQuota());
        out.put("vipExpireAt", e.getVipExpireAt() == null ? null : e.getVipExpireAt().toString());
        out.put("vipActive", vip);
        // 会员期内导出不限次 → remaining 给 -1 表示「无限」，前端据此隐藏付费引导
        out.put("remaining", vip ? -1 : (e.getFreeQuota() + e.getPaidQuota()));
        out.put("canExport", vip || (e.getFreeQuota() + e.getPaidQuota()) > 0);
        return out;
    }

    /**
     * 消耗一次导出。
     *
     * @param clientNo 前端生成的幂等号（一次导出动作一个）
     * @return { ok, duplicate, source, remaining }；来源不足时 ok=false + code=QUOTA_EXHAUSTED
     */
    public Map<String, Object> consume(String openid, String clientNo, String specId, Boolean specOk) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (clientNo == null || clientNo.isBlank()) {
            out.put("ok", false);
            out.put("code", "CLIENT_NO_REQUIRED");
            out.put("msg", "缺少幂等号 clientNo");
            return out;
        }
        // ① 幂等闸：同一 clientNo 已处理过 → 直接返回成功，不再扣减
        Long dup = exportMapper.selectCount(new QueryWrapper<PhotoExport>().eq("client_no", clientNo));
        if (dup != null && dup > 0) {
            out.put("ok", true);
            out.put("duplicate", true);
            out.putAll(view(openid));
            return out;
        }

        PhotoEntitlement e = ensure(openid);
        String source;
        if (vipActive(e)) {
            source = "vip";                        // 会员期内不消耗张数
        } else if (e.getFreeQuota() != null && e.getFreeQuota() > 0) {
            source = "free";
        } else if (e.getPaidQuota() != null && e.getPaidQuota() > 0) {
            source = "paid";
        } else {
            out.put("ok", false);
            out.put("code", "QUOTA_EXHAUSTED");
            out.put("msg", "导出次数已用完");
            return out;
        }

        // ② 先落流水（唯一键挡并发），再扣减：万一中间崩了只会少扣不会多扣，对用户无害
        PhotoExport ex = new PhotoExport();
        ex.setClientNo(clientNo);
        ex.setOpenid(openid);
        ex.setSpecId(specId);
        ex.setSource(source);
        ex.setSpecOk(specOk);
        ex.setCreatedAt(LocalDateTime.now());
        try {
            exportMapper.insert(ex);
        } catch (Exception ex2) {
            // 并发下唯一键冲突 = 另一请求已处理，按幂等成功返回
            log.info("导出流水唯一键冲突（并发），按幂等返回 openid={} clientNo={}", openid, clientNo);
            out.put("ok", true);
            out.put("duplicate", true);
            out.putAll(view(openid));
            return out;
        }

        if ("free".equals(source)) {
            entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                    .eq("openid", openid)
                    .setSql("free_quota = GREATEST(IFNULL(free_quota, 0) - 1, 0)"));
        } else if ("paid".equals(source)) {
            entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                    .eq("openid", openid)
                    .setSql("paid_quota = GREATEST(IFNULL(paid_quota, 0) - 1, 0)"));
        }
        log.info("导出消耗 openid={} source={} specId={} specOk={} clientNo={}",
                openid, source, specId, specOk, clientNo);

        out.put("ok", true);
        out.put("duplicate", false);
        out.put("source", source);
        out.putAll(view(openid));
        return out;
    }

    // ==========================================================
    // 发货 / 退款时增减权益（全部走 SQL 原子操作）
    // ==========================================================

    /**
     * 加张数。用 SQL 原子自增而非「读出来加再写回」：并发发货（微信推送 + 兜底查单同时到达）
     * 时后者会丢更新导致少发。
     */
    public void addPaidQuota(String openid, int delta) {
        if (delta == 0) {
            return;
        }
        ensure(openid);
        entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                .eq("openid", openid)
                .setSql("paid_quota = IFNULL(paid_quota, 0) + (" + delta + ")"));
        log.info("paid_quota 原子累加 openid={} delta={}", openid, delta);
    }

    /**
     * 加会员天数：从「当前到期时间与现在之中的较晚者」起算，续费是叠加而不是覆盖。
     */
    public void addVipDays(String openid, int days) {
        if (days <= 0) {
            return;
        }
        ensure(openid);
        entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                .eq("openid", openid)
                .setSql("vip_expire_at = DATE_ADD(GREATEST(IFNULL(vip_expire_at, NOW()), NOW()), INTERVAL " + days + " DAY)"));
        log.info("vip_expire_at 原子顺延 openid={} days={}", openid, days);
    }

    /** 退款回收张数（GREATEST 兜底不为负：用户可能已经把部分张数用掉了） */
    public void reducePaidQuota(String openid, int delta) {
        if (delta <= 0) {
            return;
        }
        ensure(openid);
        entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                .eq("openid", openid)
                .setSql("paid_quota = GREATEST(IFNULL(paid_quota, 0) - (" + delta + "), 0)"));
        log.info("paid_quota 原子扣减 openid={} delta={}", openid, delta);
    }

    /** 退款回收会员天数（只回退到不早于当前时刻，避免把有效期退成负数） */
    public void reduceVipDays(String openid, int days) {
        if (days <= 0) {
            return;
        }
        ensure(openid);
        entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                .eq("openid", openid)
                .setSql("vip_expire_at = GREATEST(DATE_SUB(IFNULL(vip_expire_at, NOW()), INTERVAL " + days + " DAY), NOW())"));
        log.info("vip_expire_at 原子回退 openid={} days={}", openid, days);
    }

    /** 管理端：权益明细（含完整 openid，供修数接口使用；需 token，不对外暴露） */
    public Map<String, Object> adminList() {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (PhotoEntitlement e : entMapper.selectList(new QueryWrapper<PhotoEntitlement>()
                .orderByDesc("updated_at").last("limit 100"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openid", e.getOpenid());
            m.put("freeQuota", e.getFreeQuota());
            m.put("paidQuota", e.getPaidQuota());
            m.put("vipExpireAt", e.getVipExpireAt() == null ? null : e.getVipExpireAt().toString());
            m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", rows.size());
        out.put("rows", rows);
        return out;
    }

    /** 管理端修数：直接设置张数与会员天数 */
    public Map<String, Object> adminSet(String openid, Integer paidQuota, Integer vipDays) {
        ensure(openid);
        if (paidQuota != null && paidQuota >= 0) {
            entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                    .eq("openid", openid).set("paid_quota", paidQuota));
        }
        if (vipDays != null) {
            if (vipDays <= 0) {
                entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                        .eq("openid", openid).set("vip_expire_at", null));
            } else {
                entMapper.update(null, new UpdateWrapper<PhotoEntitlement>()
                        .eq("openid", openid)
                        .setSql("vip_expire_at = DATE_ADD(NOW(), INTERVAL " + vipDays + " DAY)"));
            }
        }
        Map<String, Object> out = view(openid);
        out.put("openid", openid);
        log.info("管理端修数 openid={} paidQuota={} vipDays={}", openid, paidQuota, vipDays);
        return out;
    }
}
