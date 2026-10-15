const specs = require('../../utils/specs');
const matting = require('../../utils/matting');
const pipeline = require('../../utils/pipeline');
const api = require('../../utils/api');

const RESULT_KEY = 'dkmz_last_result';

const STEPS = [
  { key: 'crop', label: '人脸检测 · 智能裁剪' },
  { key: 'matting', label: '抠图' },
  { key: 'compose', label: '换底合成' },
  { key: 'size', label: '按尺寸导出' },
];

/**
 * 通用拍摄要求。
 *
 * v0.5.0 去掉「考试」维度后，原来挂在每个考试上的 notes 没了归属，
 * 但注意事项是真实有用的（报名照被驳回最常见的原因就是戴眼镜、遮眉眼耳、美颜过度）。
 * 这里保留各考试原文里重复出现的**共性条款**，属于通用要求、不涉及某个考试的特定规格。
 * 尺寸特有的部分由 size.sizeNote 单独展示。
 */
const SHOOT_NOTES = [
  '近 6 个月内正面、免冠、彩色头像',
  '不得佩戴眼镜、隐形眼镜、美瞳',
  '不得戴帽子、头巾、发带',
  '头发不得遮挡眉毛、眼睛、耳朵',
  '不得美颜、特效、翻拍，不得 PS 修改人像特征',
];

function sizeLimitText(size) {
  if (size.sizeRule === 'range') return `${size.minSizeKB}–${size.maxSizeKB} KB`;
  if (size.sizeRule === 'max') {
    return size.maxSizeKB >= 1024 ? `≤ ${size.maxSizeKB / 1024} MB` : `≤ ${size.maxSizeKB} KB`;
  }
  return '以提交平台要求为准';
}

function sizeRequirementNote(size) {
  const note = String(size.sizeNote || '');
  const bracket = note.match(/[（(](.*?)[）)]/);
  const commaAt = note.indexOf('，');
  const text = bracket ? bracket[1] : commaAt >= 0 ? note.slice(commaAt + 1) : '';
  return text.replace(/交管\s*12123/g, '交管 12123').trim();
}

Page({
  data: {
    size: null,
    shootNotes: SHOOT_NOTES,
    bgOptions: [],
    bgIndex: 0,
    // 底色滑动高亮块（与首页一致，位置由 JS 实测）
    bgSlider: 'opacity: 0',

    // AI 精修已默认启用（不再提供开关）：替换背景 + 美颜。
    // 抠图一律走云端 BDA；云端不可用时自动回退端上 MODNet（此时不含美颜）。
    // 如需恢复「只替换背景」，把 run() 里的 beautify 传 false 并加 alphaOnly: true
    //（后端 alphaOnly 出口仍在：只回 alpha 灰度图、端上换底，响应约 13KB）。
    cloudAvailable: false,

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

    // 拍摄要求默认折叠：首屏留给底色选择与主操作按钮
    showNotes: false,
  },

  onLoad(query) {
    // v0.5.0：入参从 ?exam= 改为 ?color=&size=（首页的两个选择器）
    const size = specs.getSize(query.size) || specs.allSizes()[0];
    const bgOptions = specs.allBackgrounds();
    const wanted = query.color || 'white';
    const found = bgOptions.findIndex((b) => b.id === wanted);
    // 取景引导框：按当前尺寸比例生成（屏宽 72%）
    const [pw, ph] = size.px;
    const frameW = Math.round(750 * 0.72);
    const frameH = Math.round((frameW * ph) / pw);
    const camFrameStyle = `width:${frameW}rpx;height:${frameH}rpx;left:${Math.round((750 - frameW) / 2)}rpx;top:24%;`;
    this.setData({
      size,
      sizeLimit: sizeLimitText(size),
      sizeRequirementNote: sizeRequirementNote(size),
      sizeUsage: specs.sizeUsage(size),
      bgOptions,
      bgIndex: found < 0 ? 0 : found,
      camFrameStyle,
      cloudAvailable: api.isCloudEnabled(),
      steps: STEPS.map((s) => ({ key: s.key, label: s.label, state: 'pending' })),
    }, () => this.updateBgSlider());
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
        // 模型地址由服务端下发，而规格库是异步拉的：首次进页面可能还没拿到地址。
        // 这里补拉一次规格库再重试；只重试一次，避免网络异常时来回死循环。
        if (err && err.code === 'NO_MODEL_URL' && !this._modelRetried) {
          this._modelRetried = true;
          this.setData({ modelStateText: '获取模型地址…' });
          specs
            .refresh()
            .then(() => this.prepareModel())
            .catch(() => {});
        }
      });
  },

  // ------------------------------------------------------------ 交互

  onBgChange(e) {
    this.setData({ bgIndex: Number(e.currentTarget.dataset.index) });
    this.updateBgSlider();
  },

  /** 首次渲染完成后再校准一次滑块位置（确保进来就停在默认选中的底色上） */
  onReady() {
    this.updateBgSlider();
  },

  /** 底色滑动高亮块：实测选中卡相对行的位置（与首页同款动效） */
  updateBgSlider() {
    const cur = this.data.bgOptions[this.data.bgIndex];
    if (!cur || !cur.id) return;
    const tryMeasure = (n) => {
      const q = wx.createSelectorQuery();
      q.select('.bg-row').boundingClientRect();
      q.select('#bg-' + cur.id).boundingClientRect();
      q.exec((rects) => {
        const row = rects && rects[0];
        const card = rects && rects[1];
        if ((!row || !card || !card.width) && n > 0) {
          setTimeout(() => tryMeasure(n - 1), 120);
          return;
        }
        if (!row || !card) return;
        this.setData({
          bgSlider:
            'opacity: 1; transform: translate(' +
            (card.left - row.left) + 'px, ' + (card.top - row.top) + 'px);' +
            ' width: ' + card.width + 'px; height: ' + card.height + 'px;',
        });
      });
    };
    setTimeout(() => tryMeasure(2), 30);
  },

  onToggleNotes() {
    this.setData({ showNotes: !this.data.showNotes });
  },

  onPickPhoto() {
    if (this.data.busy) return;
    if (this.data.modelError) {
      this._modelRetried = false; // 手动重试时允许再补拉一次规格库
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
    const mattingLabel = this.data.cloudAvailable ? '云端抠图 · 换底美颜' : '端上抠图';
    this.setData({
      steps: STEPS.map((s) => ({
        key: s.key,
        label: s.key === 'matting' ? mattingLabel : s.label,
        state: 'pending',
      })),
    });
  },

  run(imgPath) {
    // 抠图一律云端优先，且固定带美颜（AI 精修已默认启用、不可关闭）：
    //   云端可用 → 上传裁剪图，云端一次完成「人像分割 + 面部提亮 + 换底合成」，
    //             回不透明成品图（约 60~90KB），端上只加水印/导出
    //   云端不可用 → 静默回退端上 MODNet（本地处理、不上传，此时不含美颜）
    const useCloud = this.data.cloudAvailable;
    if (!useCloud && !this.session) {
      wx.showToast({ title: '模型未就绪', icon: 'none' });
      return;
    }
    if (!useCloud) {
      wx.showToast({ title: '未连接云端，使用本地抠图（不含美颜）', icon: 'none' });
    }
    const bg = this.data.bgOptions[this.data.bgIndex] || specs.getBackground('white');
    this.setData({ busy: true, progressText: '' });
    this.resetSteps();

    pipeline
      .makeIdPhoto({
        imgPath,
        sizeSpec: this.data.size,
        bgHex: bg.hex,
        session: this.session,
        cloudSegment: useCloud ? (b64, extra) => api.cloudSegment(b64, true, extra) : null,
        // 固定走「成品图模式」（云端连换底一起做）；不再传 alphaOnly
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
          sizeId: this.data.size.id,
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
