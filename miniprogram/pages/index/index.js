const specs = require('../../utils/specs');
const config = require('../../utils/config.js');

/**
 * 首页 = 选择器（底色 + 尺寸），不再有「考试/场景」列表。
 *
 * v0.5.0：用户直接选底色和尺寸进制作页。原来挂在考试上的规格知识没有丢，
 * 而是以「尺寸 → usedBy 场景标注」的形式保留（如「295×413 一寸 · 国考/教资认定/四六级」），
 * 用户不需要先理解「我属于哪个考试」也能选对。
 */
Page(config.withConfig({
  data: {
    colors: [],
    colorId: 'white',
    sizes: [],
    sizeId: '',
    // 滑动高亮块的行内样式（位置/尺寸由实测量卡得出）
    colorSlider: 'opacity: 0',
    sizeSlider: 'opacity: 0',
    meta: specs.META,
    specSource: specs.sourceInfo().sourceText,
    bannerOk: true,
    // 备案模式：隐藏订单/权益入口与开发用自检入口
    reviewMode: config.isReviewMode(),
  },

  /** 头图横幅加载失败 → 回退文字版头（本地包内资源一般不会失败，防御性兜底） */
  onBannerError() {
    if (this.data.bannerOk) this.setData({ bannerOk: false });
  },

  onLoad() {
    this.applySpec();
    // 云端规格库拉取完成（可能比首屏晚）后刷新选择器与页脚版本号
    const app = getApp();
    this._specCb = () => this.applySpec();
    if (app && app.onSpecChanged) {
      app.onSpecChanged(this._specCb);
    }
  },

  /** 首次渲染完成后再校准一次滑块位置（双保险：确保进来就停在默认选中项上） */
  onReady() {
    this.updateColorSlider();
    this.updateSizeSlider();
  },

  onUnload() {
    const app = getApp();
    if (app && app.onSpecChanged) {
      app.onSpecChanged(null);
    }
  },

  /** 把规格库投影到界面数据（首次用第一个尺寸作默认值，用户已选过的不覆盖） */
  applySpec() {
    const sizes = specs.allSizes().map((s) => ({
      id: s.id,
      name: s.name,
      summary: specs.sizeSummary(s, true),
      usage: specs.sizeUsage(s),
    }));
    const colors = specs.allBackgrounds();
    const keepSize = this.data.sizeId && sizes.some((s) => s.id === this.data.sizeId);
    const keepColor = this.data.colorId && colors.some((c) => c.id === this.data.colorId);
    this.setData({
      colors,
      // 默认选白底（官方系统最常用）
      colorId: keepColor ? this.data.colorId : ((colors[0] && colors[0].id) || 'white'),
      sizes,
      // 默认选第一个尺寸（一寸，最常用）
      sizeId: keepSize ? this.data.sizeId : ((sizes[0] && sizes[0].id) || ''),
      meta: specs.META,
      specSource: specs.sourceInfo().sourceText,
    }, () => {
      this.updateColorSlider();
      this.updateSizeSlider();
    });
    console.log('[photo][index] 规格库已刷新', specs.META.version, '尺寸', sizes.length, '底色', colors.length);
  },

  /** 下拉刷新：主动向云端要一次更新 */
  onPullDownRefresh() {
    specs.refresh().then((r) => {
      this.applySpec();
      wx.stopPullDownRefresh();
      wx.showToast({
        title: r && r.updated ? `规格库已更新 v${r.version}` : '已是最新规格',
        icon: 'none',
      });
    });
  },

  onPickColor(e) {
    this.setData({ colorId: e.currentTarget.dataset.id });
    this.updateColorSlider();
  },

  onPickSize(e) {
    this.setData({ sizeId: e.currentTarget.dataset.id });
    this.updateSizeSlider();
  },

  /** 通用：实测目标元素相对容器的位置，驱动滑动高亮块 */
  measureSlider(gridSel, itemSel, key, attempt) {
    const tryMeasure = (n) => {
      const q = wx.createSelectorQuery();
      q.select(gridSel).boundingClientRect();
      q.select(itemSel).boundingClientRect();
      q.exec((rects) => {
        const grid = rects && rects[0];
        const card = rects && rects[1];
        // 首帧可能还没渲染出来（量到 null 或 0 尺寸），隔一会儿重试
        if ((!grid || !card || !card.width) && n > 0) {
          setTimeout(() => tryMeasure(n - 1), 120);
          return;
        }
        if (!grid || !card) return;
        this.setData({
          [key]:
            'opacity: 1; transform: translate(' +
            (card.left - grid.left) + 'px, ' + (card.top - grid.top) + 'px);' +
            ' width: ' + card.width + 'px; height: ' + card.height + 'px;',
        });
      });
    };
    setTimeout(() => tryMeasure(attempt === undefined ? 2 : attempt), 30);
  },

  /** 底色区滑块：底色是一行等宽卡片，量出来最稳（换底色数量/间距都不用改公式） */
  updateColorSlider() {
    if (!this.data.colorId || !this.data.colors.length) return;
    this.measureSlider('.color-row', '#color-' + this.data.colorId, 'colorSlider');
  },

  /** 尺寸区滑块：两列网格里选中卡会多一行「适用场景」，每行高度不同，量出来最稳 */
  updateSizeSlider() {
    if (!this.data.sizeId || !this.data.sizes.length) return;
    this.measureSlider('.size-grid', '#size-' + this.data.sizeId, 'sizeSlider');
  },

  /** 进制作页：把底色与尺寸作为参数带过去 */
  onStart() {
    const { colorId, sizeId } = this.data;
    if (!sizeId) {
      wx.showToast({ title: '请选择尺寸', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: `/pages/make/make?color=${colorId}&size=${sizeId}` });
  },

  onOpenLab() {
    wx.navigateTo({ url: '/pages/lab/index' });
  },

  onOpenOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  onOpenAbout() {
    wx.navigateTo({ url: '/pages/about/about' });
  },

  onOpenPrivacy() {
    wx.navigateTo({ url: '/pages/privacy/privacy' });
  },

  onShareAppMessage() {
    return { title: '登科喵证件照｜选底色选尺寸，一键出片', path: '/pages/index/index' };
  },

  onOpenAgreement() {
    wx.navigateTo({ url: '/pages/agreement/agreement' });
  },
}));
