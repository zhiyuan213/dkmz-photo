/** 保存两种成片只扣一次权益；第二张保存失败后仅续存第二张。 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/result/result.js'), 'utf8');
let definition;
let consumeCount = 0;
let failHD = true;
const saved = [];
const wx = {
  saveImageToPhotosAlbum({ filePath, success, fail }) {
    if (filePath === 'hd.jpg' && failHD) {
      failHD = false;
      fail({ errMsg: 'temporary error' });
    } else {
      saved.push(filePath);
      success({});
    }
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
  _result: { path: 'spec.jpg', cleanPreviewPath: 'hd.jpg', previewPath: 'watermark.jpg' },
  specOk: true,
  setData(value) { Object.assign(this.data, value); },
});
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
(async () => {
  page.onSave();
  await flush();
  await flush();
  assert.deepEqual(saved, ['spec.jpg']);
  assert.equal(page.data.savedSpec, true);
  assert.equal(page.data.savedHD, false);
  assert.equal(page.data.saved, false);
  page.onSave();
  await flush();
  await flush();
  assert.deepEqual(saved, ['spec.jpg', 'hd.jpg']);
  assert.equal(consumeCount, 1);
  assert.equal(page.data.saved, true);
  console.log('✅ 两版保存与失败续存检查通过，权益只扣一次');
})().catch((error) => { console.error(error); process.exitCode = 1; });
