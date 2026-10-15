/**
 * 合规文案公共常量（隐私政策 / 用户协议 / 关于与开源声明 共用）。
 *
 * CONTACT_EMAIL 会公开显示在小程序的这三个页面，修改前请确认愿意对外公开。
 * 微信要求《用户隐私保护指引》及小程序内政策提供可联系开发者的方式。
 */

module.exports = {
  APP_NAME: '登科喵证件照',
  UPDATED_AT: '2026-10-15',

  CONTACT_EMAIL: '13428961230@163.com',

  // 端上人像分割所依赖的开源模型
  MODEL_NAME: 'MODNet',
  MODEL_LICENSE: 'Apache-2.0',
  MODEL_SOURCE: 'HuggingFace Xenova/modnet（MODNet 官方权重的 ONNX 导出）',
};
