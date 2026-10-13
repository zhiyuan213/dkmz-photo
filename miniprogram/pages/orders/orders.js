const api = require('../../utils/api.js');
const config = require('../../utils/config.js');

/** 订单状态 → 展示文案（后端状态机：INIT 未支付 / DELIVERED 已到账 / REFUNDED 已退款） */
const STATUS_TEXT = {
  INIT: { text: '待支付', cls: 'pending' },
  PAID: { text: '处理中', cls: 'pending' },
  DELIVERED: { text: '已到账', cls: 'done' },
  REFUNDED: { text: '已退款', cls: 'refunded' },
};

Page(config.withConfig({
  data: {
    loading: true,
    orders: [],
    entitlement: null,
    cloudEnabled: true,
    error: '',
    reviewMode: config.isReviewMode(),
  },

  onLoad() {
    // 备案模式：页面只渲染中性空态，不请求订单/权益数据
    if (config.isReviewMode()) {
      this.setData({ loading: false });
      return;
    }
    this.setData({ cloudEnabled: api.isCloudEnabled() });
    if (!api.isCloudEnabled()) {
      this.setData({ loading: false, error: '云端服务未配置' });
      return;
    }
    this.loadAll();
  },

  onShow() {
    // 从支付页返回后刷新（支付状态可能已变更）
    if (!config.isReviewMode() && api.isCloudEnabled() && !this.data.loading) {
      this.loadAll();
    }
  },

  onPullDownRefresh() {
    if (config.isReviewMode()) {
      wx.stopPullDownRefresh();
      return;
    }
    this.loadAll().then(() => wx.stopPullDownRefresh());
  },

  loadAll() {
    this.setData({ loading: true, error: '' });
    return Promise.all([api.fetchMyOrders(), api.fetchEntitlement()])
      .then(([orderRes, entRes]) => {
        const orders = (orderRes && orderRes.orders ? orderRes.orders : []).map((o) => {
          const st = STATUS_TEXT[o.status] || { text: o.status || '未知', cls: 'pending' };
          return Object.assign({}, o, {
            statusText: st.text,
            statusCls: st.cls,
            priceText: ((o.priceFen || 0) / 100).toFixed(2),
            benefitText: this.benefitText(o),
            timeText: (o.createdAt || '').replace('T', ' ').slice(0, 16),
          });
        });
        this.setData({
          orders,
          entitlement: entRes && entRes.ok ? entRes : null,
          loading: false,
        });
      })
      .catch((err) => {
        console.warn('[photo][orders] 加载失败', err);
        this.setData({
          loading: false,
          error: (err && (err.msg || err.errMsg)) || '加载失败，请下拉重试',
        });
      });
  },

  /** 权益描述：会员显示到期日，张数包显示张数 */
  benefitText(o) {
    if (o.vipDays > 0) return `会员 ${o.vipDays} 天`;
    if (o.quotaDelta > 0) return `高清导出 ${o.quotaDelta} 张`;
    return '—';
  },

  onCopyOrderId(e) {
    const id = e.currentTarget.dataset.id;
    wx.setClipboardData({ data: id });
  },

  onGoBuy() {
    wx.navigateTo({ url: '/pages/buy/buy' });
  },

  onGoHome() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
}));
