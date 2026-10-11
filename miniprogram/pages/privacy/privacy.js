const legal = require('../../utils/legal');

Page({
  data: {
    appName: legal.APP_NAME,
    updatedAt: legal.UPDATED_AT,
    contact: legal.CONTACT_EMAIL,
  },

  /** 打开微信侧《用户隐私保护指引》（小程序管理后台配置的版本） */
  onOpenPrivacyContract() {
    if (typeof wx.openPrivacyContract !== 'function') {
      wx.showToast({ title: '当前微信版本不支持', icon: 'none' });
      return;
    }
    wx.openPrivacyContract({
      fail: () => wx.showToast({ title: '暂时无法打开，请稍后重试', icon: 'none' }),
    });
  },

  onOpenAbout() {
    wx.navigateTo({ url: '/pages/about/about' });
  },

  onOpenAgreement() {
    wx.navigateTo({ url: '/pages/agreement/agreement' });
  },
});
