const specs = require('../../utils/specs');
const matting = require('../../utils/matting');
const pipeline = require('../../utils/pipeline');
const api = require('../../utils/api');

const RESULT_KEY = 'dkmz_last_result';

const STEPS = [
  { key: 'crop', label: '人脸检测 · 智能裁剪' },
  { key: 'matting', label: '抠图' },
  { key: 'compose', label: '换底合成' },
  { key: 'size', label: '按规格导出' },
];

Page({
  data: {
    exam: null,
    bgOptions: [],
    bgIndex: 0,

    // 云端精修（腾讯云 BDA 人像分割）：默认关闭，失败自动回退端上抠图
    cloudOn: false,
    // 肤色提亮（仅在云端精修开启时可选，后端做保守提亮+磨皮）
    beautyOn: false,

    modelStateText: '准备中…',
    modelReady: false,
    modelError: '',

    busy: false,
    steps: [],
    progressText: '',

    srcPath: '',
    lastResult: null,

    // 内嵌相机层
    cameraVisible: false,
    devicePosition: 'front', // 默认前置摄像头
    snapping: false,
  },

  onLoad(query) {
    const exam = specs.getExam(query.exam) || specs.allExams()[0];
    const bgOptions = specs.examBackgrounds(exam);
    // 取景引导框：按当前规格比例生成（屏宽 72%）
    const [pw, ph] = exam.image.recommendPx;
    const frameW = Math.round(750 * 0.72);
    const frameH = Math.round((frameW * ph) / pw);
    const camFrameStyle = `width:${frameW}rpx;height:${frameH}rpx;left:${Math.round((750 - frameW) / 2)}rpx;top:24%;`;
    this.setData({
      exam,
      bgOptions,
      bgIndex: 0,
      camFrameStyle,
      steps: STEPS.map((s) => ({ key: s.key, label: s.label, state: 'pending' })),
    });
    this.prepareModel();
  },

  onUnload() {
    // 会话是全局共享单例，不随页面卸载销毁（见 prepareModel 注释）
  },

  // ------------------------------------------------------------ 模型准备

  /**
   * 会话为全局共享（matting.getSharedSession）：整个小程序生命周期只建一次，
   * 换考试模板/重复进出本页 0ms 复用；卸载页面不销毁，避免下次进入重建。
   */
  prepareModel() {
    this.setData({ modelStateText: '准备中…', modelError: '' });
    matting
      .getSharedSession()
      .then(({ session, loadMs }) => {
        this.session = session;
        this.setData({ modelReady: true, modelStateText: '已就绪' });
      })
      .catch((err) => {
        const msg = err && (err.message || err.errMsg || JSON.stringify(err));
        this.setData({ modelError: msg, modelStateText: '模型准备失败' });
      });
  },

  // ------------------------------------------------------------ 交互

  onBgChange(e) {
    this.setData({ bgIndex: Number(e.currentTarget.dataset.index) });
  },

  onCloudToggle(e) {
    this.setData({ cloudOn: !!e.detail.value });
    if (!e.detail.value) this.setData({ beautyOn: false });
  },

  onBeautyToggle(e) {
    this.setData({ beautyOn: !!e.detail.value });
  },

  onPickPhoto() {
    if (this.data.busy) return;
    if (this.data.modelError) {
      this.prepareModel();
      return;
    }
    wx.showActionSheet({
      itemList: ['拍照（默认前置摄像头）', '从相册选择'],
      success: (res) => {
        if (res.tapIndex === 0) {
          this.setData({ cameraVisible: true, devicePosition: 'front' });
        } else {
          this.chooseFromAlbum();
        }
      },
    });
  },

  chooseFromAlbum() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album'],
      sizeType: ['original'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0];
        if (!f) return;
        this.setData({ srcPath: f.tempFilePath });
        this.run(f.tempFilePath);
      },
    });
  },

  // ------------------------------------------------------------ 相机层

  getCameraCtx() {
    if (!this.cameraCtx) this.cameraCtx = wx.createCameraContext();
    return this.cameraCtx;
  },

  onCameraFlip() {
    this.setData({ devicePosition: this.data.devicePosition === 'front' ? 'back' : 'front' });
  },

  onCameraCancel() {
    this.setData({ cameraVisible: false });
  },

  onCameraError() {
    this.setData({ cameraVisible: false });
    wx.showModal({
      title: '相机不可用',
      content: '未获得摄像头权限。可在设置中开启，或从相册选择照片继续制作。',
      confirmText: '去设置',
      success: (r) => {
        if (r.confirm) wx.openSetting();
      },
    });
  },

  onCameraSnap() {
    if (this.data.snapping) return;
    this.setData({ snapping: true });
    this.getCameraCtx().takePhoto({
      quality: 'high',
      success: (res) => {
        this.setData({ cameraVisible: false, snapping: false, srcPath: res.tempImagePath });
        this.run(res.tempImagePath);
      },
      fail: (err) => {
        this.setData({ snapping: false });
        wx.showToast({ title: (err && err.errMsg) || '拍照失败，请重试', icon: 'none' });
      },
    });
  },

  // ------------------------------------------------------------ 流水线

  setStep(key, state) {
    const steps = this.data.steps.map((s) => (s.key === key ? Object.assign({}, s, { state }) : s));
    this.setData({ steps });
  },

  resetSteps() {
    const mattingLabel = this.data.cloudOn && api.isCloudEnabled() ? '云端抠图 · 精修' : '端上抠图';
    this.setData({
      steps: STEPS.map((s) => ({
        key: s.key,
        label: s.key === 'matting' ? mattingLabel : s.label,
        state: 'pending',
      })),
    });
  },

  run(imgPath) {
    const useCloud = this.data.cloudOn && api.isCloudEnabled();
    if (!this.session && !useCloud) {
      wx.showToast({ title: '模型未就绪', icon: 'none' });
      return;
    }
    if (this.data.cloudOn && !api.isCloudEnabled()) {
      wx.showToast({ title: '未连接云端，使用本地抠图', icon: 'none' });
    }
    const bg = this.data.bgOptions[this.data.bgIndex] || specs.getBackground('white');
    this.setData({ busy: true, progressText: '' });
    this.resetSteps();

    pipeline
      .makeIdPhoto({
        imgPath,
        exam: this.data.exam,
        bgHex: bg.hex,
        session: this.session,
        cloudSegment: useCloud
          ? (b64) => api.cloudSegment(b64, this.data.beautyOn)
          : null,
        onStep: (key) => {
          this.data.steps.forEach((s) => {
            if (s.state === 'doing') this.setStep(s.key, 'done');
          });
          this.setStep(key, 'doing');
        },
      })
      .then((result) => {
        this.data.steps.forEach((s) => {
          if (s.state === 'doing') this.setStep(s.key, 'done');
        });
        const payload = {
          examId: this.data.exam.id,
          bgId: bg.id,
          result,
        };
        wx.setStorageSync(RESULT_KEY, payload);
        wx.navigateTo({ url: '/pages/result/result' });
      })
      .catch((err) => {
        const msg = err && (err.message || err.errMsg || JSON.stringify(err));
        wx.showModal({ title: '制作失败', content: msg, showCancel: false });
      })
      .then(() => this.setData({ busy: false, progressText: '' }));
  },
});
