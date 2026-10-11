/**
 * 美颜 JS 实现验证脚本（Node 端直接跑 miniprogram/utils/beauty.js）
 * 用真实像素数据，不是看代码猜效果。
 */
const fs = require('fs');
const path = require('path');
const beauty = require('../miniprogram/utils/beauty.js');

const DIR = path.join(__dirname, '..', 'output-verify');
const meta = JSON.parse(fs.readFileSync(path.join(DIR, 'beauty_meta.json'), 'utf8'));
const { w, h } = meta;
const n = w * h;

const rgb = new Uint8Array(fs.readFileSync(path.join(DIR, 'beauty_in.raw')));

const alphaBuf = fs.readFileSync(path.join(DIR, 'beauty_alpha.raw'));
const alpha = new Float32Array(alphaBuf.buffer, alphaBuf.byteOffset, alphaBuf.byteLength / 4);

/** 取原图副本（源 raw 是 RGB 3 通道，需按步长 3 展开） */
function base() {
  const data = new Uint8ClampedArray(n * 4);
  for (let i = 0, p = 0, q = 0; i < n; i++, p += 4, q += 3) {
    data[p] = rgb[q];
    data[p + 1] = rgb[q + 1];
    data[p + 2] = rgb[q + 2];
    data[p + 3] = 255;
  }
  return data;
}

const VARIANTS = [
  ['on', beauty.BEAUTY_ON],           // 开关打开（= 线上效果：美白轻 + 磨皮中）
  ['w1', { whiten: 1 }],              // 仅美白（竞品对标参数）
  ['s1', { smooth: 1 }],              // 仅磨皮
  ['h2', { hair: 2 }],                // 黑发（未纳入开关，单独验证用）
];

console.log(`图像 ${w}x${h} = ${n} px\n`);

for (const [name, opt] of VARIANTS) {
  const data = base();
  const t0 = Date.now();
  const t = beauty.applyBeauty(data, w, h, Object.assign({ alpha, face: meta.face }, opt));
  const wall = Date.now() - t0;
  fs.writeFileSync(path.join(DIR, `beauty_out_${name}.raw`), Buffer.from(data.buffer));
  console.log(
    `${name.padEnd(5)} ${JSON.stringify(opt).padEnd(34)} ${String(wall).padStart(4)}ms` +
      `  (skin ${t.skin || 0} / smooth ${t.smooth || 0} / whiten ${t.whiten || 0} / hair ${t.hair || 0})`
  );
}
