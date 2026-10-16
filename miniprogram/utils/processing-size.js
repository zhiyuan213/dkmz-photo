/** 高清工作图按原图可用细节逐档选择，不为凑像素放大原图。 */
function cloudProcessingSizes(box, outW, outH) {
  const sourceScale = Math.max(1, Math.min(box.width / outW, box.height / outH));
  const sizes = [];
  [960, 768, 640, Math.max(outW, outH)].forEach((longEdge) => {
    const scale = Math.max(1, Math.min(3, sourceScale, longEdge / Math.max(outW, outH)));
    const width = Math.round(outW * scale);
    const height = Math.round(outH * scale);
    if (!sizes.some((s) => s.width === width && s.height === height)) sizes.push({ width, height });
  });
  return sizes;
}

module.exports = { cloudProcessingSizes };
