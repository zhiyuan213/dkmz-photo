const legal = require('../../utils/legal');

Page({
  data: {
    appName: legal.APP_NAME,
    updatedAt: legal.UPDATED_AT,
    contact: legal.CONTACT_EMAIL,
    // 开源声明（与「关于与开源声明」页共用同一份常量，避免两边说法不一致）
    modelName: legal.MODEL_NAME,
    modelLicense: legal.MODEL_LICENSE,
    modelSource: legal.MODEL_SOURCE,
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
