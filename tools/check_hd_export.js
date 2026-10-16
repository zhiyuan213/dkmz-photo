/** 仅保存高清成片；相册失败后重试不会重复消耗导出权益。 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/result/result.js'), 'utf8');
let definition;
let consumeCount = 0;
let failOnce = true;
const saved = [];
const wx = {
  saveImageToPhotosAlbum({ filePath, success, fail, complete }) {
    if (failOnce) {
      failOnce = false;
      fail({ errMsg: 'temporary error' });
    } else {
      saved.push(filePath);
      success({});
    }
    if (complete) complete({});
  },
  showToast() {}, showModal() {},
};
const api = {
  isCloudEnabled: () => true,
  consumeExport: () => { consumeCount++; return Promise.resolve({ ok: true, source: 'free' }); },
};
vm.runInNewContext(source, {
  Page: (value) => { definition = value; },
  wx,
  require: (id) => id.includes('api.js') ? api
    : id.includes('config.js') ? { withConfig: (value) => value, isReviewMode: () => false }
    : { META: {} },
  console, Date, Math, Promise, setTimeout,
});
const page = Object.assign({}, definition, {
  data: Object.assign({}, definition.data, { size: { id: 'one-inch' } }),
  _result: { path: 'hd.jpg', previewPath: 'watermark.jpg' },
  specOk: true,
  setData(value) { Object.assign(this.data, value); },
});
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
(async () => {
  page.onSave();
  await flush();
  assert.deepEqual(saved, []);
  assert.equal(page.data.saved, false);
  page.onSave();
  await flush();
  assert.deepEqual(saved, ['hd.jpg']);
  assert.equal(consumeCount, 1);
  assert.equal(page.data.saved, true);
  console.log('✅ 仅保存高清图，失败重试不重复扣除权益');
})().catch((error) => { console.error(error); process.exitCode = 1; });
