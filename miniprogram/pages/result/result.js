const specs = require('../../utils/specs');
const api = require('../../utils/api.js');
const { REVIEW_MODE } = require('../../utils/config.js');

const RESULT_KEY = 'dkmz_last_result';

/** 生成本次导出动作的幂等号：同一张图重试保存时沿用同一个，避免重复扣减 */
function genClientNo() {
  return 'E' + Date.now() + Math.random().toString(36).slice(2, 8);
}

Page({
  data: {
    exam: null,
    bgName: '',
    src: '',
    kb: 0,
    px: '',
    quality: 0,
    faceFound: false,
    metrics: [],
    checks: [],
    notes: [],
    meta: specs.META,
    saving: false,
    saved: false,
    reviewMode: REVIEW_MODE,
  },

  onLoad() {
    const payload = wx.getStorageSync(RESULT_KEY);
    if (!payload || !payload.result) {
      wx.showToast({ title: '结果已失效，请重新制作', icon: 'none' });
      setTimeout(() => wx.navigateBack(), 1200);
      return;
    }
    const exam = specs.getExam(payload.examId);
    const bg = specs.getBackground(payload.bgId);
    const r = payload.result;
    const spec = exam.image;

    // 规格校验清单
    const checks = [];
    checks.push({ k: '像素尺寸', v: `${r.px[0]}×${r.px[1]}（推荐 ${spec.recommendPx[0]}×${spec.recommendPx[1]}）`, ok: true });
    checks.push({
      k: '文件体积',
      v: `${r.kb} KB（要求：${spec.sizeNote}）`,
      ok: r.underMin ? false : true,
      warn: !!r.underMin,
    });
    checks.push({ k: '底色', v: bg.name, ok: true });
    checks.push({ k: '格式', v: 'JPG', ok: true });
    if (!r.faceFound) {
      checks.push({ k: '人脸定位', v: '未检出人脸，已按默认构图裁剪，请检查头顶是否留白', ok: false });
    }

    // 规格校验整体结论（随导出发给服务端，用于统计「哪个考试最常被判不合格」）
    this.specOk = checks.every((c) => c.ok);
    this._result = r;

    this.setData({
      exam,
      bgName: bg.name,
      // 展示带水印预览版；高清无水印版在保存校验通过后给出
      // （备案模式不做权益校验，直接展示高清无水印图）
      src: REVIEW_MODE ? r.path || r.previewPath : r.previewPath || r.path,
      kb: r.kb,
      px: `${r.px[0]}×${r.px[1]}`,
      quality: Math.round(r.quality * 100),
      faceFound: r.faceFound,
      metrics: r.metrics,
      checks,
      notes: exam.notes,
    });
  },

  onPreview() {
    wx.previewImage({ urls: [this.data.src] });
  },

  /**
   * 保存到相册。
   *
   * 导出前先向服务端「消耗一次」：
   *   免费体验张数 / 已购张数 / 会员 → 决定能不能导出；
   *   clientNo 由本页生成一次并复用 → 网络重试、用户连点都不会重复扣减（后端靠它做幂等）。
   * 云端不可用（未配环境 / 网络异常）时降级为直接保存，避免用户白做一张图。
   */
  onSave() {
    if (this.data.saving || this.data.saved) return;
    const r = this._result || {};
    const previewPath = r.previewPath || r.path;
    const cleanPath = r.path;
    const save = (p) => this.saveToAlbum(p || previewPath);
    // 备案模式：不校验权益、不消耗额度，直接保存高清无水印图
    if (REVIEW_MODE) {
      save(cleanPath);
      return;
    }
    if (!api.isCloudEnabled()) {
      console.log('[photo][result] 云端未启用，跳过权益校验直接保存预览版');
      save();
      return;
    }
    if (!this._clientNo) {
      this._clientNo = genClientNo();
    }
    this.setData({ saving: true });
    api
      .consumeExport(this._clientNo, this.data.exam ? this.data.exam.id : '', this.specOk !== false)
      .then((res) => {
        this.setData({ saving: false });
        console.log('[photo][result] 导出校验结果', res && res.ok, res && res.source, res && res.remaining);
        if (res && res.ok) {
          if (res.source === 'paid' || res.source === 'vip') {
            wx.showToast({ title: res.source === 'vip' ? '会员权益已使用' : '已使用 1 张', icon: 'none' });
          }
          // 权益校验通过 → 保存高清无水印版
          save(cleanPath);
          return;
        }
        if (res && res.code === 'QUOTA_EXHAUSTED') {
          this.showQuotaExhausted();
          return;
        }
        if (res && res.code === 'NO_OPENID') {
          wx.showToast({ title: '登录态失效，请重新进入小程序', icon: 'none' });
          return;
        }
        // 其他未明确的拒绝：降级保存预览版，不让用户白做
        save();
      })
      .catch((err) => {
        // 云端异常不让用户白做：先保存，日志留痕
        console.warn('[photo][result] 权益校验失败，降级直接保存', err);
        this.setData({ saving: false });
        wx.showToast({ title: '网络异常，已保存预览版', icon: 'none' });
        save();
      });
  },

  /** 次数用完：引导购买（虚拟支付未开通时给出说明，避免用户点了没反应） */
  showQuotaExhausted() {
    const cloudReady = api.isCloudEnabled();
    wx.showModal({
      title: '导出次数已用完',
      content: cloudReady
        ? '可购买高清导出张数或开通会员，解锁无水印高清图。'
        : '购买功能即将开通，敬请期待。',
      confirmText: cloudReady ? '去购买' : '知道了',
      showCancel: false,
      success: (r) => {
        if (r.confirm && cloudReady) {
          wx.navigateTo({ url: '/pages/buy/buy' });
        }
      },
    });
  },

  saveToAlbum(filePath) {
    this.setData({ saving: true });
    wx.saveImageToPhotosAlbum({
      filePath: filePath || this.data.src,
      success: () => {
        this.setData({ saved: true });
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

  onHome() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
});
