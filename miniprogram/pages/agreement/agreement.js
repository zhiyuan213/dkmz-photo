const legal = require('../../utils/legal');

Page({
  data: {
    appName: legal.APP_NAME,
    updatedAt: legal.UPDATED_AT,
    contact: legal.CONTACT_EMAIL,
  },

  onOpenPrivacy() {
    wx.navigateTo({ url: '/pages/privacy/privacy' });
  },

  onOpenAbout() {
    wx.navigateTo({ url: '/pages/about/about' });
  },
});
