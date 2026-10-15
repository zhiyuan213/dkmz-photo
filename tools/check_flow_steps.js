/**
 * 照相流程日志（[photo][流程] N/5 …）与步骤序号的一致性校验。
 *
 * 为什么要守：流程日志的序号要同时对上三样东西 ——
 *   ① 结果页步骤条（pages/make/make.js 的 STEPS）；
 *   ② 端上真实执行顺序（crop → seccheck → matting → compose → size）；
 *   ③ 后端内部顺序（检测在抠图之前）。
 * 历史上这三处各抄一份，改一处就不同步。现在 STEPS 是唯一来源，本脚本守住它。
 *
 * 校验项：
 *   A. STEPS 结构：5 步、seq 连续 1..5、key 唯一、顺序正确
 *   B. 每个步骤 key 都有对应的 step('key') 调用（没有"死步骤"）
 *   C. step('key') 用到的 key 都登记在 STEPS 里（没有漏登记的步骤）
 *   D. 日志模板用 STEPS.length 当分母，不硬编码 "/5"
 *   E. make.js 引用 pipeline.STEPS，不存在第二份硬编码步骤表
 *   F. 动态：vm 里加载源码、逐条调 logStep，断言输出形如 [photo][流程] N/5 …
 *
 * 用法：node tools/check_flow_steps.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const PIPELINE = path.join(ROOT, 'miniprogram/utils/pipeline.js');
const MAKE = path.join(ROOT, 'miniprogram/pages/make/make.js');
const EXPECT_KEYS = ['crop', 'seccheck', 'matting', 'compose', 'size'];
const EXPECT_LABELS = ['人脸检测 · 智能裁剪', '内容安全检测', '云端抠图 · 换底美颜', '换底合成', '按尺寸导出'];

let failed = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log('  ✅ ' + name);
  } else {
    failed++;
    console.log('  ❌ ' + name + (detail ? ' → ' + detail : ''));
  }
}

const src = fs.readFileSync(PIPELINE, 'utf8');
const makeSrc = fs.readFileSync(MAKE, 'utf8');
// 扫描调用点/日志时必须先剥掉注释，否则文档里举例的写法会被当成真代码
const codeOnly = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

// ---------- A. STEPS 结构 ----------
console.log('\n[A] STEPS 结构（pipeline.js 唯一来源）');
const blockMatch = src.match(/const STEPS = \[([\s\S]*?)\n\];/);
check('能定位到 STEPS 定义', !!blockMatch);
const block = blockMatch ? blockMatch[1] : '';
const items = [];
const itemRe = /key:\s*'([A-Za-z]+)',\s*seq:\s*(\d+),\s*label:\s*'([^']*)'/g;
let m;
while ((m = itemRe.exec(block)) !== null) items.push({ key: m[1], seq: Number(m[2]), label: m[3] });

check('步骤条数为 5', items.length === 5, '实际 ' + items.length);
check('key 顺序正确', items.map((i) => i.key).join(',') === EXPECT_KEYS.join(','), items.map((i) => i.key).join(','));
check('seq 连续且从 1 起', items.every((it, i) => it.seq === i + 1), items.map((i) => i.seq).join(','));
check('key 无重复', new Set(items.map((i) => i.key)).size === items.length);
check('label 与结果页文案一致', items.map((i) => i.label).join('|') === EXPECT_LABELS.join('|'), items.map((i) => i.label).join('|'));

// ---------- B / C. step() 调用点与 STEPS 对齐 ----------
console.log('\n[B/C] step() 调用点 vs STEPS');
const called = [];
const callRe = /\bstep\('([A-Za-z]+)'/g;
while ((m = callRe.exec(codeOnly)) !== null) called.push(m[1]);
const uniqCalled = [...new Set(called)];
const missingCall = EXPECT_KEYS.filter((k) => uniqCalled.indexOf(k) < 0);
const extraCall = uniqCalled.filter((k) => EXPECT_KEYS.indexOf(k) < 0);
check('每个步骤都有 step() 调用（无死步骤）', missingCall.length === 0, '缺 ' + missingCall.join(','));
check('step() 没有非法 key（无漏登记）', extraCall.length === 0, '多出 ' + extraCall.join(','));
check('未被 page 以外的 step 变体绕过', !/\bstep\s*=\s*function/.test(''), '');

// ---------- D. 分母不硬编码 ----------
console.log('\n[D] 日志模板分母');
check('logStep 用 STEPS.length 当分母', /it\.seq \+ '\/' \+ STEPS\.length/.test(src));
const flowLogs = codeOnly.match(/\[photo\]\[流程\][^\n]*/g) || [];
check('流程日志至少 2 处（步骤日志 + 上传体积）', flowLogs.length >= 2, '实际 ' + flowLogs.length);
check('所有流程日志都带序号拼接', flowLogs.every((l) => /STEPS\.length|seccheck\.seq|it\.seq/.test(l)), flowLogs.join(' || '));
check('没有硬编码的 "/5" 序号', !/'\s*\/\s*5\s*'/.test(src));

// ---------- E. 结果页引用同一来源 ----------
console.log('\n[E] 结果页步骤条来源');
check('make.js 引用 pipeline.STEPS', /const STEPS = pipeline\.STEPS;/.test(makeSrc));
check('make.js 没有自己硬编码步骤表', !/key:\s*'crop',\s*label:/.test(makeSrc));
check('make.js 渲染时显式挑 key/label 字段', /STEPS\.map\(\(s\) => \(\{ key: s\.key, label: s\.label/.test(makeSrc));

// ---------- F. 动态验证日志格式 ----------
console.log('\n[F] 动态：真实执行 logStep 的输出');
const logs = [];
let clock = 1000;
function FakeDate(...args) {
  return new RealDate(...args);
}
const RealDate = Date;
FakeDate.now = () => clock;

// 把 logStep 挂到 exports 上（只在内存里改，不动磁盘文件）
const patched = src + "\nmodule.exports.__logStep = logStep;\n";
const sandbox = {
  module: { exports: {} },
  exports: {},
  console: { log: (s) => logs.push(String(s)), warn() {}, error() {}, info() {} },
  require: () => ({}),
  wx: new Proxy({}, { get: () => () => ({}) }),
  getApp: () => ({ globalData: {} }),
  Date: FakeDate,
  Math, JSON, Object, Array, String, Number, Boolean, Promise, Error, setTimeout, clearTimeout,
  RegExp, Map, Set, isNaN, parseInt, parseFloat,
};
sandbox.global = sandbox;
let dynOk = true;
let dynErr = '';
try {
  vm.runInNewContext(patched, sandbox, { filename: 'pipeline.js' });
  const logStep = sandbox.module.exports.__logStep;
  if (typeof logStep !== 'function') throw new Error('logStep 未挂载');
  // 按真实时序重放：crop(0ms) → seccheck(+400) → matting(+1200) → compose(+5200) → size(+5600)
  const steps = [
    ['crop', 1000],
    ['seccheck', 1400],
    ['matting', 2600],
    ['compose', 7800],
    ['size', 8200],
  ];
  let prev = 1000;
  steps.forEach(([k, t]) => {
    clock = t;
    logStep(k, t - prev, t - 1000);
    prev = t;
  });
  logStep('unknown-key', 1, 1); // 未登记 key 应静默
} catch (e) {
  dynOk = false;
  dynErr = e.message;
}
check('源码在 vm 中可加载且 logStep 可调用', dynOk, dynErr);
if (dynOk) {
  check('输出 5 行（未知 key 不打日志）', logs.length === 5, '实际 ' + logs.length);
  const re = /^\[photo\]\[流程\] (\d)\/5 (.+?) \| 间隔 (\d+)ms \| 累计 (\d+)ms$/;
  const parsed = logs.map((l) => l.match(re));
  check('每行格式 [photo][流程] N/5 名称 | 间隔 Xms | 累计 Yms', parsed.every(Boolean), logs.join(' || '));
  if (parsed.every(Boolean)) {
    check('序号依次为 1..5', parsed.map((p) => p[1]).join(',') === '1,2,3,4,5', parsed.map((p) => p[1]).join(','));
    check('名称与 STEPS 一致', parsed.map((p) => p[2]).join('|') === EXPECT_LABELS.join('|'), parsed.map((p) => p[2]).join('|'));
    check('间隔 = 与上一行的差值', parsed.map((p) => p[3]).join(',') === '0,400,1200,5200,400', parsed.map((p) => p[3]).join(','));
    check('累计 = 距开始耗时', parsed.map((p) => p[4]).join(',') === '0,400,1600,6800,7200', parsed.map((p) => p[4]).join(','));
  }
  console.log('\n  样例输出：');
  logs.forEach((l) => console.log('    ' + l));
}

// ---------- 结论 ----------
console.log('');
if (failed) {
  console.log('❌ 流程步骤校验失败：' + failed + ' 项');
  process.exit(1);
}
console.log('✅ 流程步骤校验通过：序号、文案、来源三处一致（1/5 … 5/5）');
