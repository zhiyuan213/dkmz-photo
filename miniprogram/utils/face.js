/**
 * VisionKit 静态图片人脸检测（自动构图的依据）
 *
 * wx.createVKSession({ track: { face: { mode: 2 } } })
 *   mode: 2 = 静态图片模式；返回归一化（0~1）的人脸框 + 五官关键点
 *   基础库 2.25.0+（Android 微信 8.0.25+ / iOS 微信 8.0.24+）
 *
 * 注意：wx.faceDetect / wx.initFaceDetect 已停止维护，不要再使用。
 */

function supported() {
  return typeof wx.createVKSession === 'function';
}

/**
 * 检测静态图片中的人脸
 * @param {string} imgPath 本地图片路径
 * @param {number} width   传入 frameBuffer 的宽（一般用原图宽）
 * @param {number} height  传入 frameBuffer 的高
 * @param {object} opts    scoreThreshold 默认 0.5；modelMode 0/1/2 小/中/大模型
 * @returns {Promise<Array>} 人脸数组（像素坐标 + 归一化坐标）
 */
function detectFace(imgPath, width, height, opts) {
  const o = opts || {};
  return new Promise((resolve, reject) => {
    if (!supported()) {
      reject(new Error('当前基础库不支持 VisionKit，需 2.25.0+'));
      return;
    }

    let session = null;
    let settled = false;
    const finish = (fn, arg) => {
      if (settled) return;
      settled = true;
      try {
        session && session.off && session.off('updateAnchors');
      } catch (e) {
        /* ignore */
      }
      try {
        session && session.stop && session.stop();
      } catch (e) {
        /* ignore */
      }
      fn(arg);
    };

    try {
      session = wx.createVKSession({ track: { face: { mode: 2 } }, version: 'v1' });
    } catch (e) {
      reject(new Error('createVKSession 调用失败：' + (e.errMsg || e.message)));
      return;
    }

    const canvas = wx.createOffscreenCanvas({ type: '2d', width, height });
    const ctx = canvas.getContext('2d');
    const img = canvas.createImage();

    img.onload = () => {
      const t0 = Date.now();
      ctx.drawImage(img, 0, 0, width, height);
      const imgData = ctx.getImageData(0, 0, width, height);

      session.on('updateAnchors', (anchors) => {
        const list = (anchors || []).map((a) => ({
          // 归一化坐标（0~1）
          origin: a.origin,
          size: a.size,
          points: a.points,
          // 像素坐标，便于直接算裁剪框
          left: a.origin.x * width,
          top: a.origin.y * height,
          boxWidth: a.size.width * width,
          boxHeight: a.size.height * height,
          angle: a.angle,
        }));
        if (!list.length) return;
        finish(resolve, { faces: list, costMs: Date.now() - t0, width, height });
      });

      session.start((err) => {
        if (err) {
          finish(reject, new Error('VKSession 启动失败：' + JSON.stringify(err)));
          return;
        }
        try {
          session.detectFace({
            frameBuffer: imgData.data.buffer,
            width,
            height,
            scoreThreshold: o.scoreThreshold || 0.5,
            sourceType: 1, // 1 = 随机静态图片
            modelMode: o.modelMode === undefined ? 1 : o.modelMode,
          });
        } catch (e) {
          finish(reject, new Error('detectFace 调用失败：' + (e.errMsg || e.message)));
        }
        // 兜底：个别机型不回 updateAnchors（或照片里没有人），超时按「未检出」结束
        const timeoutMs = o.timeoutMs || 6000;
        setTimeout(() => finish(reject, new Error('检测超时（未回流结果）')), timeoutMs);
      });
    };
    img.onerror = () => finish(reject, new Error('图片解码失败'));
    img.src = imgPath;
  });
}

/**
 * 依据证件照构图规则，从人脸框反推裁剪框（等比例，按 targetRatio）
 * 规则：人脸框向上扩展（留出头顶）、向下多扩（含肩部），水平居中
 *
 * 越界策略（v2）：
 * - 横向：不造像素。理想框宽于原图时整体缩小到源图宽（人脸占比随之变
 *   大，属物理上限——照片里就没有那么多内容），再平移夹取居中。
 *   之前试过「边缘列拉伸补齐」，但源图边缘列的 MODNet alpha 不可靠，
 *   会产出与人身断开的悬空色块，已废弃。
 * - 纵向：允许顶部越界（头顶之上必是背景，cropAndScale 铺白、
 *   alpha=0，合成时即为目标底色）；不允许底部越界（超出就整体上移）。
 *
 * @param {number} faceHeightRatio 人脸框占画面高度的目标比例（默认 0.45；
 *        个别证件如驾驶证要求头部占 2/3，可用 exam.portrait.cropFaceRatio 覆盖）
 */
function calcCropBox(face, imgWidth, imgHeight, targetRatio, faceHeightRatio) {
  const ratio = targetRatio || 3 / 4; // 宽:高
  const fh = faceHeightRatio || 0.45;
  const cx = face.left + face.boxWidth / 2;

  let cropHeight = face.boxHeight / fh;
  let cropWidth = cropHeight * ratio;
  if (cropWidth > imgWidth) {
    cropWidth = imgWidth;
    cropHeight = cropWidth / ratio;
  }

  // 横向平移夹取（cropWidth ≤ imgWidth，必然放得下）
  const left = Math.max(0, Math.min(cx - cropWidth / 2, imgWidth - cropWidth));

  // 纵向：人脸中心略低于画面中心（头顶留白、下方含肩）；底部不许越界
  const cy = face.top + face.boxHeight / 2 - cropHeight * 0.06;
  let top = cy - cropHeight / 2;
  if (top + cropHeight > imgHeight) {
    top = imgHeight - cropHeight;
  }

  return {
    left: Math.round(left),
    top: Math.round(top), // 可为负：顶部越界铺白底，alpha=0
    width: Math.round(cropWidth),
    height: Math.round(cropHeight),
  };
}

module.exports = { supported, detectFace, calcCropBox };
