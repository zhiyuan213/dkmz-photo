const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness(call) {
  const toasts = [];
  const context = { module: { exports: {} }, console,
    require: () => ({ call }), wx: { showToast: (message) => toasts.push(message) } };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/config.js'), 'utf8'), context);
  return { config: context.module.exports, toasts };
}

test('concurrent lifecycle requests share one validated backend request', async () => {
  let resolve, calls = 0;
  const { config } = harness(() => { calls++; return new Promise((r) => { resolve = r; }); });
  assert.equal(config.isReviewMode(), false);
  const first = config.load(), second = config.load();
  resolve({ ok: true, reviewMode: true });
  await Promise.all([first, second]);
  assert.equal(calls, 1);
  assert.equal(config.isReviewMode(), true);
});

test('refresh reads mode changes and rejects non-boolean config', async () => {
  const responses = [{ ok: true, reviewMode: true }, { ok: true, reviewMode: false }, { ok: true, reviewMode: 'true' }];
  const { config } = harness(async () => responses.shift());
  await config.load();
  await config.load(true);
  assert.equal(config.isReviewMode(), false);
  await assert.rejects(config.load(true));
  assert.equal(config.isReviewMode(), false);
});

test('configuration failure blocks saving even after review mode was previously active', async () => {
  let fail = false, saves = 0;
  const { config, toasts } = harness(async () => {
    if (fail) throw Error('offline');
    return { ok: true, reviewMode: true };
  });
  await config.load();
  fail = true;
  const page = config.withConfig({ data: {}, onSave() { saves++; } });
  page.setData = function (data) { Object.assign(this.data, data); };
  await page.onSave();
  assert.equal(saves, 0);
  assert.equal(config.isReviewMode(), false);
  assert.equal(toasts.length, 1);
});

test('page waits for configuration before initializing and hides paid action in review mode', async () => {
  let initialized, purchases = 0;
  const { config } = harness(async () => ({ ok: true, reviewMode: true }));
  const page = config.withConfig({ data: {}, onLoad() { initialized = config.isReviewMode(); }, onBuy() { purchases++; } });
  page.setData = function (data) { Object.assign(this.data, data); };
  assert.equal(page.data.configLoading, true);
  await page.onLoad();
  assert.equal(initialized, true);
  assert.equal(page.data.configLoading, false);
  await page.onBuy();
  assert.equal(purchases, 0);
});

test('normal mode saving reaches original handler after fresh config', async () => {
  let saves = 0;
  const { config } = harness(async () => ({ ok: true, reviewMode: false }));
  const page = config.withConfig({ data: {}, onSave() { saves++; } });
  page.setData = function (data) { Object.assign(this.data, data); };
  await page.onSave();
  assert.equal(saves, 1);
});

test('home uses native sharing with a valid home route', () => {
  let page;
  const context = { Page: (value) => { page = value; }, require: (name) => name.includes('config')
    ? { withConfig: (value) => value, isReviewMode: () => false }
    : { META: {}, sourceInfo: () => ({ sourceText: '' }) } };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/index/index.js'), 'utf8'), context);
  assert.equal(page.onShareAppMessage().path, '/pages/index/index');
  const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/index/index.wxml'), 'utf8');
  assert.match(template, /open-type="share"/);
});


test('purchase display follows backend processing limits after refresh', async () => {
  let daily = 30;
  const { config } = harness(async () => ({ ok: true, reviewMode: false,
    processingLimits: { enabled: true, perMinute: 10, per24Hours: daily } }));
  await config.load();
  assert.equal(config.getProcessingLimits().per24Hours, 30);
  daily = 25;
  await config.load(true);
  assert.equal(config.getProcessingLimits().per24Hours, 25);
  assert.equal(config.getProcessingLimits().perMinute, 10);
});


test('cloud processing obtains fresh wx.login code and does not trust caller openid', async () => {
  let request;
  const context = { module: { exports: {} }, console: { log() {}, warn() {} }, wx: {
    login: ({ success }) => success({ code: 'fresh-code' }),
    cloud: { init() {}, callContainer: (options) => {
      request = options;
      options.success({ data: { ok: true, image: 'result', finished: true } });
    } },
  } };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/api.js'), 'utf8'), context);
  await context.module.exports.cloudSegment('image', true, { bgHex: '#FFFFFF' });
  assert.equal(request.data.loginCode, 'fresh-code');
  assert.equal(request.data.beauty, true);
  assert.equal(request.data.image, 'image');
  assert.equal(request.data.openid, undefined);
});
