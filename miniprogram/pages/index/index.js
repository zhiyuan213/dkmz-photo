const specs = require('../../utils/specs');

Page({
  data: {
    keyword: '',
    exams: [],
    meta: specs.META,
    specSource: specs.sourceInfo().sourceText,
  },

  onLoad() {
    this.setData({ exams: this.decorate(specs.allExams()) });
    // 云端规格库拉取完成（可能比首屏晚）后刷新列表与页脚版本号
    const app = getApp();
    this._specCb = () => this.applySpecUpdate();
    if (app && app.onSpecChanged) {
      app.onSpecChanged(this._specCb);
    }
  },

  onUnload() {
    const app = getApp();
    if (app && app.onSpecChanged) {
      app.onSpecChanged(null);
    }
  },

  /** 规格库更新后同步界面（版本号 + 列表 + 来源） */
  applySpecUpdate() {
    this.setData({
      exams: this.decorate(specs.allExams()),
      meta: specs.META,
      specSource: specs.sourceInfo().sourceText,
    });
    console.log('[photo][index] 规格库已刷新', specs.META.version);
  },

  /** 下拉刷新：主动向云端要一次更新 */
  onPullDownRefresh() {
    specs.refresh().then((r) => {
      this.applySpecUpdate();
      wx.stopPullDownRefresh();
      wx.showToast({
        title: r && r.updated ? `规格库已更新 v${r.version}` : '已是最新规格',
        icon: 'none',
      });
    });
  },

  decorate(list) {
    return list.map((e) => ({
      id: e.id,
      name: e.name,
      stage: e.stage,
      group: e.group,
      bgNames: specs.examBackgrounds(e).map((b) => b.name).join(' / '),
      sizeSummary: specs.sizeSummary(e),
    }));
  },

  onSearchInput(e) {
    const kw = e.detail.value;
    this.setData({ keyword: kw, exams: this.decorate(specs.search(kw)) });
  },

  onClearSearch() {
    this.setData({ keyword: '', exams: this.decorate(specs.allExams()) });
  },

  onPickExam(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/make/make?exam=${id}` });
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

  onOpenAgreement() {
    wx.navigateTo({ url: '/pages/agreement/agreement' });
  },
});
