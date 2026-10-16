const specs = require('../../utils/specs');
const api = require('../../utils/api.js');
const config = require('../../utils/config.js');

const RESULT_KEY = 'dkmz_last_result';

/** 生成本次导出动作的幂等号：同一张图重试保存时沿用同一个，避免重复扣减 */
function genClientNo() {
  return 'E' + Date.now() + Math.random().toString(36).slice(2, 8);
}

Page(config.withConfig({
  data: {
    size: null,
    sizeUsage: '',
    bgName: '',
    src: '',
    kb: 0,
    px: '',
    quality: 0,
    faceFound: false,
    metrics: [],
    checks: [],
    meta: specs.META,
    saving: false,
    saved: false,
    savedPreview: false,
    savedSpec: false,
    savedHD: false,
    hdPx: '',
    reviewMode: config.isReviewMode(),
    /**
     * 权益概览（云端可用时异步拉取）。
     * 免费与已购**分开显示**：用户需要清楚知道这次消耗的是「今天免费的 1 次」，
     * 还是自己花钱买的张数 —— 只显示一个合计数字会让人以为免费额度被吃掉了。
     */
    ent: null,
    entError: '',
    /** 处理明细默认折叠：普通用户只关心「合格 / 保存」，耗时数据按需展开 */
    showMetrics: false,
  },

  onLoad() {
    const payload = wx.getStorageSync(RESULT_KEY);
    if (!payload || !payload.result) {
      wx.showToast({ title: '结果已失效，请重新制作', icon: 'none' });
      setTimeout(() => wx.navigateBack(), 1200);
      return;
    }
    const size = specs.getSize(payload.sizeId) || specs.allSizes()[0];
    const bg = specs.getBackground(payload.bgId);
    const r = payload.result;
    const spec = size;

    // 规格校验清单（v0.5.0：不再按「考试」判，改按所选尺寸的体积上限判）
    // 文案保持短句，好让整页在一屏内看完（详细体积说明见首页/制作页）
    const kbRange = spec.sizeRule === 'range' && spec.minSizeKB
      ? `${spec.minSizeKB}~${spec.maxSizeKB} KB`
      : `≤${spec.maxSizeKB} KB`;
    const checks = [];
    checks.push({ k: '像素尺寸', v: `${r.px[0]}×${r.px[1]}`, ok: true });
    checks.push({
      k: '文件体积',
      v: `${r.kb} KB · 要求 ${kbRange}`,
      ok: !r.underMin,
      warn: !!r.underMin,
    });
    checks.push({ k: '底色 · 格式', v: `${bg.name} · JPG`, ok: true });
    if (!r.faceFound) {
      checks.push({ k: '人脸定位', v: '未检出人脸，已按默认构图裁剪', ok: false });
    }

    // 规格校验整体结论（随导出发给服务端，用于统计哪个尺寸最常被判不合格）
    this.specOk = checks.every((c) => c.ok);
    this._result = r;

    this.setData({
      size,
      sizeUsage: specs.sizeUsage(spec),
      bgName: bg.name,
      // 展示带水印预览版；高清无水印版在保存校验通过后给出
      // （备案模式不做权益校验，直接展示高清无水印图）
      src: config.isReviewMode()
        ? r.cleanPreviewPath || r.path || r.previewPath
        : r.previewPath || r.path,
      kb: r.kb,
      px: `${r.px[0]}×${r.px[1]}`,
      hdPx: r.hdPx ? `${r.hdPx[0]}×${r.hdPx[1]}` : '',
      quality: Math.round(r.quality * 100),
      faceFound: r.faceFound,
      metrics: r.metrics,
      checks,
    });

  },

  /** 首次进入及从购买页返回时都重新拉取，避免结果页保留旧的剩余张数。 */
  onShow() {
    this.loadEntitlement();
  },

  /** 拉取权益（免费与已购分开展示）。失败静默：不阻断导出主流程 */
  loadEntitlement() {
    if (config.isReviewMode() || !api.isCloudEnabled()) return;
    api
      .fetchEntitlement()
      .then((res) => {
        if (res && res.ok) {
          this.setData({
            ent: {
              freeQuota: res.freeQuota,
              freeDailyQuota: res.freeDailyQuota == null ? 1 : res.freeDailyQuota,
              paidQuota: res.paidQuota,
              vipActive: !!res.vipActive,
              vipExpireAt: res.vipExpireAt,
              remaining: res.remaining,
            },
            entError: '',
          });
        }
      })
      .catch((err) => {
        console.warn('[photo][result] 权益拉取失败', err);
        this.setData({ entError: '权益信息获取失败，保存时会重新校验' });
      });
  },

  onPreview() {
    wx.previewImage({ urls: [this.data.src] });
  },

  onToggleMetrics() {
    this.setData({ showMetrics: !this.data.showMetrics });
  },

  /**
   * 保存到相册。
   *
   * 导出前先向服务端「消耗一次」：
   *   会员 → 当日免费 1 次 → 已购张数，依次判定；
   *   clientNo 由本页生成一次并复用 → 网络重试、用户连点都不会重复扣减（后端靠它做幂等）。
   * 云端不可用（未配环境 / 网络异常）时降级为直接保存，避免用户白做一张图。
   */
  onSave() {
    if (this.data.saving || this.data.saved) return;
    const r = this._result || {};
    const previewPath = r.previewPath || r.path;
    const savePreview = () => this.saveToAlbum(previewPath);
    // 备案模式：不校验权益、不消耗额度，直接保存两种无水印版本。
    if (config.isReviewMode()) {
      this._exportUnlocked = true;
      this.saveVersions();
      return;
    }
    // 本页已经完成权益校验后，保存失败重试无需再次请求或消耗额度。
    if (this._exportUnlocked) {
      this.saveVersions();
      return;
    }
    if (!api.isCloudEnabled()) {
      console.log('[photo][result] 云端未启用，跳过权益校验直接保存预览版');
      savePreview();
      return;
    }
    if (!this._clientNo) {
      this._clientNo = genClientNo();
    }
    this.setData({ saving: true });
    api
      .consumeExport(this._clientNo, this.data.size ? this.data.size.id : '', this.specOk !== false)
      .then((res) => {
        console.log('[photo][result] 导出校验结果', res && res.ok, res && res.source, res && res.remaining);
        if (res && res.ok) {
          // 消耗来源分开提示：让用户知道自己花掉的是哪一份
          if (res.source === 'free') {
            wx.showToast({ title: '已使用今日免费 1 次', icon: 'none' });
          } else if (res.source === 'paid') {
            wx.showToast({ title: '已使用 1 张已购次数', icon: 'none' });
          } else if (res.source === 'vip') {
            wx.showToast({ title: '会员权益已使用', icon: 'none' });
          }
          this.syncEntitlement(res);
          // 一次权益解锁同一张照片的报名版与高清留存版。
          this._exportUnlocked = true;
          this.saveVersions();
          return;
        }
        this.setData({ saving: false });
        if (res && res.code === 'QUOTA_EXHAUSTED') {
          this.showQuotaExhausted();
          return;
        }
        if (res && res.code === 'NO_OPENID') {
          wx.showToast({ title: '登录态失效，请重新进入小程序', icon: 'none' });
          return;
        }
        // 其他未明确的拒绝：降级保存预览版，不让用户白做
        savePreview();
      })
      .catch((err) => {
        // 云端异常不让用户白做：先保存，日志留痕
        console.warn('[photo][result] 权益校验失败，降级直接保存', err);
        this.setData({ saving: false });
        wx.showToast({ title: '网络异常，将保存预览版', icon: 'none' });
        savePreview();
      });
  },

  /** 先保存符合报名规格的照片，再保存高分辨率留存照；失败可续存未完成的一张。 */
  saveVersions() {
    const r = this._result || {};
    const specPath = r.path;
    const hdPath = r.cleanPreviewPath;
    if (!specPath || !hdPath) {
      wx.showToast({ title: '照片文件已失效，请重新制作', icon: 'none' });
      this.setData({ saving: false });
      return;
    }
    this.setData({ saving: true });
    const save = (path) => new Promise((resolve, reject) => {
      wx.saveImageToPhotosAlbum({ filePath: path, success: resolve, fail: reject });
    });
    let chain = Promise.resolve();
    if (!this.data.savedSpec) {
      chain = chain.then(() => save(specPath)).then(() => this.setData({ savedSpec: true }));
    }
    if (!this.data.savedHD) {
      chain = chain.then(() => hdPath === specPath ? null : save(hdPath))
        .then(() => this.setData({ savedHD: true }));
    }
    chain.then(() => {
      this.setData({ saving: false, saved: true });
      wx.showToast({ title: hdPath === specPath ? '照片已保存' : '两张照片已保存', icon: 'success' });
    }).catch((e) => {
      this.setData({ saving: false });
      const msg = (e && e.errMsg) || '';
      if (msg.includes('auth')) {
        wx.showModal({
          title: '需要相册权限', content: '请在设置中允许保存到相册', confirmText: '去设置',
          success: (res) => { if (res.confirm) wx.openSetting(); },
        });
      } else if (!msg.includes('cancel')) {
        if (this.data.savedSpec) {
          wx.showModal({
            title: '高清版保存失败',
            content: '报名版已保存。再次点击“继续保存高清版”即可续存，不会重复扣除次数。',
            showCancel: false,
          });
        } else {
          wx.showToast({ title: '报名版保存失败，请重试', icon: 'none' });
        }
      }
    });
  },

  /** 用消耗接口回传的最新权益覆盖界面数字（省一次请求） */
  syncEntitlement(res) {
    if (res.freeQuota == null && res.paidQuota == null) return;
    this.setData({
      'ent.freeQuota': res.freeQuota,
      'ent.paidQuota': res.paidQuota,
      'ent.remaining': res.remaining,
      'ent.vipActive': !!res.vipActive,
      entError: '',
    });
  },

  /** 次数用完：引导购买（虚拟支付未开通时给出说明，避免用户点了没反应） */
  showQuotaExhausted() {
    const cloudReady = api.isCloudEnabled();
    const ent = this.data.ent;
    // 今日免费已用完是最常见的情况：明确告诉用户「明天还能免费做 1 张」，
    // 否则用户会以为免费额度被彻底用光、再也不来了
    const content = ent && ent.freeQuota === 0 && !ent.paidQuota
      ? '今天的免费次数已用完，明天可再来免费制作 1 张。也可购买导出次数，立即解锁无水印高清图。'
      : cloudReady
      ? '可购买高清导出张数，解锁无水印高清图。'
      : '购买功能即将开通，敬请期待。';
    wx.showModal({
      title: '导出次数已用完',
      content,
      confirmText: cloudReady ? '去购买' : '知道了',
      showCancel: false,
      success: (r) => {
        if (r.confirm && cloudReady) {
          wx.navigateTo({ url: '/pages/buy/buy?from=result' });
        }
      },
    });
  },

  saveToAlbum(filePath) {
    this.setData({ saving: true });
    wx.saveImageToPhotosAlbum({
      filePath: filePath || this.data.src,
      success: () => {
        this.setData({ saved: true, savedPreview: true });
        wx.showToast({ title: '已保存到相册', icon: 'success' });
      },
      fail: (e) => {
        const msg = (e && e.errMsg) || '';
        if (msg.includes('auth')) {
          wx.showModal({
            title: '需要相册权限',
            content: '请在设置中允许保存到相册',
            confirmText: '去设置',
            success: (r) => {
              if (r.confirm) wx.openSetting();
            },
          });
        } else if (!msg.includes('cancel')) {
          wx.showToast({ title: '保存失败', icon: 'none' });
        }
      },
      complete: () => this.setData({ saving: false }),
    });
  },

  onRemake() {
    wx.navigateBack();
  },

  onOpenBuy() {
    wx.navigateTo({ url: '/pages/buy/buy?from=result' });
  },

  onHome() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
}));
