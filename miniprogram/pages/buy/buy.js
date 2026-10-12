const api = require('../../utils/api.js');
const buy = require('../../utils/buy.js');
const { REVIEW_MODE } = require('../../utils/config.js');

/** 商品卡辅助文案：会员显示天数，张数包显示张数 */
function benefitText(g) {
  if (g.vipDays > 0) return `${g.vipDays} 天不限张数导出`;
  if (g.quota > 0) return `${g.quota} 张高清无水印导出`;
  return '—';
}

Page({
  data: {
    loading: true,
    goods: [],
    entitlement: null,
    cloudEnabled: true,
    error: '',
    paying: false,
    reviewMode: REVIEW_MODE,
  },

  onLoad() {
    // 备案模式：深链进入也只显示占位，不拉商品/权益、不出现任何价格
    if (REVIEW_MODE) {
      this.setData({ loading: false });
      return;
    }
    this.setData({ cloudEnabled: api.isCloudEnabled() });
    if (!api.isCloudEnabled()) {
      this.setData({ loading: false, error: '云端服务未配置，暂时无法购买' });
      return;
    }
    this.load();
  },

  onShow() {
    // 从订单页 / 支付收银台返回后刷新权益
    if (!REVIEW_MODE && api.isCloudEnabled() && !this.data.loading) {
      this.load();
    }
  },

  onPullDownRefresh() {
    if (REVIEW_MODE) {
      wx.stopPullDownRefresh();
      return;
    }
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    this.setData({ loading: true, error: '' });
    return Promise.all([api.fetchGoods(), api.fetchEntitlement()])
      .then(([goodsRes, entRes]) => {
        const list = goodsRes && goodsRes.ok ? goodsRes.goods || [] : [];
        this.setData({
          goods: list.map((g) => ({
            goodsId: g.goodsId,
            name: g.name,
            priceText: buy.fmtYuan(g.priceFen),
            benefit: benefitText(g),
            vip: (g.vipDays || 0) > 0,
          })),
          entitlement: entRes && entRes.ok ? entRes : null,
          loading: false,
          error: goodsRes && goodsRes.ok && list.length === 0 ? '商品暂未上架' : '',
        });
      })
      .catch((err) => {
        console.warn('[photo][buy] 加载失败', err);
        this.setData({
          loading: false,
          error: (err && (err.msg || err.errMsg)) || '加载失败，请下拉重试',
        });
      });
  },

  onBuy(e) {
    if (this.data.paying) return;
    const goods = this.data.goods[e.currentTarget.dataset.index];
    if (!goods) return;
    this.setData({ paying: true });
    buy
      .payGoods({ goodsId: goods.goodsId, priceFen: 0 })
      .then((r) => {
        if (r.ok) {
          this.load(); // 刷新权益概览
        }
      })
      .then(() => this.setData({ paying: false }));
  },

  onGoOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  onGoHome() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
});
