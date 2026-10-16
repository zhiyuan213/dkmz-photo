const specs = require('../../utils/specs');
const pipeline = require('../../utils/pipeline');
const api = require('../../utils/api');

const RESULT_KEY = 'dkmz_last_result';

// 步骤条与流水线流程日志共用同一份定义（pipeline.STEPS 里的 seq 就是日志序号），
// 不再各抄一份 —— 顺序/文案要改只改 pipeline.js 一处。
const STEPS = pipeline.STEPS;

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

    // 出片只有一条通道：云端精修（腾讯云 BDA 人像分割 + 面部轻度提亮）。
    // 端上 MODNet 已彻底摘掉（不再预下载 25MB 模型）—— 它既不再参与出片，
    // 又会因模型下载失败把「选照片」卡死。云端不可用时明确提示，不做本地降级。
    // 如需恢复「只替换背景」，把 run() 里 cloudSegment 的 beautify 传 false 并加
    // alphaOnly: true（后端 alphaOnly 出口仍在：只回 alpha 灰度图、端上换底，约 13KB）。
    cloudAvailable: false,

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
    // 云端不可用时连拍照都不必走：出片只有云端一条通道，提前拦掉，
    // 避免用户选完照片才被拒（run() 里还有一道同样的兜底）
    if (!this.data.cloudAvailable) {
      wx.showModal({
        title: '服务暂时不可用',
        content: '云端处理服务未连接，请检查网络后重试',
        showCancel: false,
      });
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
    // 只有云端一条通道，步骤标签固定（不再按端上/云端切换）
    this.setData({
      steps: STEPS.map((s) => ({ key: s.key, label: s.label, state: 'pending' })),
    });
  },

  run(imgPath) {
    // 出片只有一条通道：云端精修（含内容安全检测）。端上 MODNet 不再参与出片，
    // 因此云端不可用时不能"降级出片"，而是明确提示用户 —— 降级会给出不一致的成片，
    // 也会绕过内容安全检测。
    if (!this.data.cloudAvailable) {
      wx.showModal({
        title: '服务暂时不可用',
        content: '云端处理服务未连接，请检查网络后重试',
        showCancel: false,
      });
      return;
    }
    const bg = this.data.bgOptions[this.data.bgIndex] || specs.getBackground('white');
    this.setData({ busy: true, progressText: '' });
    this.resetSteps();

    pipeline
      .makeIdPhoto({
        imgPath,
        sizeSpec: this.data.size,
        bgHex: bg.hex,
        cloudSegment: (b64, extra) => api.cloudSegment(b64, true, extra),
        cloudPrecheck: (b64) => api.precheckPhoto(b64),
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
        // 内容安全拦截：仅提示「内容有违规嫌疑」（审核整改要求），不暴露技术细节
        const risky = err && err.code === 'RISKY_CONTENT';
        // api 层 reject 的是 {code,msg} 对象，pipeline 抛的是 Error，两种都要兜住
        const msg = (err && (err.msg || err.message || err.errMsg)) || '未知错误，请稍后重试';
        wx.showModal({
          title: risky ? '无法使用该照片' : '制作失败',
          content: risky ? '图片内容涉嫌违规，请更换照片后重试' : String(msg),
          showCancel: false,
        });
      })
      .then(() => this.setData({ busy: false, progressText: '' }));
  },
});
