const legal = require('../../utils/legal');
const specs = require('../../utils/specs');

Page({
  data: {
    appName: legal.APP_NAME,
    appVersion: '1.0.0',
    updatedAt: legal.UPDATED_AT,
    contact: legal.CONTACT_EMAIL,
    meta: specs.META,
    specSource: specs.sourceInfo().sourceText,
    modelName: legal.MODEL_NAME,
    modelLicense: legal.MODEL_LICENSE,
    modelSource: legal.MODEL_SOURCE,
  },

  onOpenPrivacy() {
    wx.navigateTo({ url: '/pages/privacy/privacy' });
  },

  onOpenAgreement() {
    wx.navigateTo({ url: '/pages/agreement/agreement' });
  },
});
