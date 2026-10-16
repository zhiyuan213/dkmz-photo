#!/usr/bin/env node
/**
 * 端上请求体体积预算校验（静态检查，不需要真机）。
 *
 * 为什么需要它：微信云托管对小程序 CallContainer 的**文本**请求体有 100KiB 硬上限
 * ——官方《微信云托管开发常识》原文「CallContainer 请求大小限制 100K，请求中不建议
 * 包含图片，可通过对象存储处理」；《调用云托管服务》亦写「请求大小限制 100KiB
 * （文本 100KiB，二进制 20MiB）」。一旦越界，前端只看到
 * `cloud.callContainer:fail -600001`，**后端完全收不到请求，容器日志里也没有任何记录**，
 * 排查成本极高（2026-10-15 就因为给送检原图加体积时没算总量，白白查了一轮）。
 *
 * 送检整图与抠图工作图现在分别请求；本脚本分别校验两次最坏体积：
 *   node tools/check_payload_budget.js
 *
 * 改动 pipeline.js / api.js 里任何一个体积常量后，都要跑一次。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

/** 从源码里静态取出 `const NAME = 数字 [* 数字];` 并求值 */
function num(src, name, file) {
  const m = src.match(new RegExp('const\\s+' + name + '\\s*=\\s*([0-9_*\\s]+);'));
  if (!m) throw new Error('未在 ' + file + ' 找到常量 ' + name);
  const expr = m[1].replace(/_/g, '').trim();
  if (!/^[0-9*\s]+$/.test(expr)) throw new Error(name + ' 的表达式无法静态求值：' + expr);
  return expr
    .split('*')
    .map((s) => Number(s.trim()))
    .reduce((a, b) => a * b, 1);
}

const pipeline = read('miniprogram/utils/pipeline.js');
const api = read('miniprogram/utils/api.js');

const BUDGET = num(pipeline, 'CLOUD_BODY_BUDGET', 'pipeline.js');
const RESERVE = num(pipeline, 'CLOUD_BODY_RESERVE', 'pipeline.js');
const UPLOAD = num(pipeline, 'CLOUD_PAYLOAD_LIMIT', 'pipeline.js');
const CHECK = num(pipeline, 'CHECK_PAYLOAD_LIMIT', 'pipeline.js');
const CALL_LIMIT = num(api, 'CALL_BODY_LIMIT', 'api.js');

/** 官方硬上限：CallContainer 文本请求体 100KiB */
const HARD = 100 * 1024;

const checkWorst = CHECK + RESERVE;
const uploadWorst = UPLOAD + RESERVE;

const kb = (n) => (n / 1024).toFixed(1) + 'KB';
console.log('裁剪工作图上限    ' + kb(UPLOAD));
console.log('送检原图上限      ' + kb(CHECK));
console.log('送检请求 + 预留   ' + kb(checkWorst));
console.log('抠图请求 + 预留   ' + kb(uploadWorst));
console.log('本地守门阈值      ' + kb(CALL_LIMIT));
console.log('官方文本硬上限    ' + kb(HARD));

const problems = [];
if (BUDGET !== CALL_LIMIT) {
  problems.push('pipeline.js 的 CLOUD_BODY_BUDGET 与 api.js 的 CALL_BODY_LIMIT 不一致（两处都要改）');
}
if (checkWorst > BUDGET || uploadWorst > BUDGET) problems.push('某次请求 + 预留 已超过本地预算 CLOUD_BODY_BUDGET');
if (checkWorst > CALL_LIMIT || uploadWorst > CALL_LIMIT) problems.push('某次请求 + 预留 已超过本地守门阈值 CALL_BODY_LIMIT');
if (checkWorst >= HARD || uploadWorst >= HARD) problems.push('某次请求已达到/超过官方 100KiB 硬上限，请求会被链路拒绝（-600001）');
if (UPLOAD < 32 * 1024) problems.push('裁剪工作图上限低于 32KB，BDA 抠图质量会明显下降，请重新分配预算');
if (CHECK < 16 * 1024) problems.push('送检原图上限低于 16KB，微信内容安全检测的识别率可能下降，请重新分配预算');

if (problems.length) {
  console.error('\n❌ 体积预算不通过：');
  problems.forEach((p) => console.error('   · ' + p));
  process.exit(1);
}
console.log('\n✅ 两次请求分别在本地预算与官方 100KiB 上限内');
