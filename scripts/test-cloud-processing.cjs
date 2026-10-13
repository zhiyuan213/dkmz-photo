const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { cloudProcessingSizes } = require('../miniprogram/utils/processing-size');

test('small registration output uses higher resolution source for processing', () => {
  const sizes = cloudProcessingSizes({ width: 1500, height: 2100 }, 295, 413);
  assert.ok(sizes[0].height > 800);
  assert.ok(sizes.every((s) => Math.max(s.width, s.height) <= 960));
  assert.ok(Math.abs(sizes[0].width / sizes[0].height - 295 / 413) < 0.002);
});

test('small source is not artificially enlarged for processing', () => {
  const sizes = cloudProcessingSizes({ width: 295, height: 413 }, 295, 413);
  assert.deepEqual(sizes, [{ width: 295, height: 413 }]);
});

function pipelineHarness(sizeOfUpload) {
  const context = {
    module: { exports: {} }, console,
    require: (name) => name === './processing-size' ? { cloudProcessingSizes } : {},
    uploads: [], removed: [], sizeOfUpload,
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/pipeline.js'), 'utf8'), context);
  vm.runInContext(`
    cropAndScale = async (path, box, width, height, quality) => {
      const upload = { width, height, quality };
      uploads.push(upload);
      return upload;
    };
    fileToBase64 = async (upload) => 'x'.repeat(sizeOfUpload(upload));
    removeTempFile = (path) => removed.push(path);
    base64ToTempFile = async () => 'finished.jpg';
    runCloud = cloudMatting;
  `, context);
  return context;
}

test('padding coordinates use actual working resolution and retain cleanup', async () => {
  const context = pipelineHarness(() => 1000);
  let extra;
  const result = await context.runCloud({ imgPath: 'original.jpg',
    box: { width: 1500, height: 2100, top: -100 }, outW: 295, outH: 413,
    bgHex: '#438EDB' }, async (image, options) => {
    extra = options;
    return { ok: true, finished: true, image: 'image', beautyApplied: true };
  });
  assert.equal(extra.outTop, Math.ceil(100 / 2100 * result.processingSize.height));
  assert.equal(result.uploadQuality, 0.95);
  assert.equal(context.removed.length, context.uploads.length);
});

test('large uploads fall back in resolution while keeping JPEG quality', async () => {
  const context = pipelineHarness((upload) => upload.height > 640 ? 100000 : 1000);
  let calls = 0;
  const result = await context.runCloud({ imgPath: 'original.jpg',
    box: { width: 1500, height: 2100, top: 0 }, outW: 295, outH: 413,
    bgHex: '#FFFFFF' }, async (image) => {
    calls++;
    assert.ok(image.length <= 90 * 1024);
    return { ok: true, finished: true, image: 'image' };
  });
  assert.equal(calls, 1);
  assert.ok(result.processingSize.height <= 640);
  assert.ok(context.uploads.every((upload) => upload.quality >= 0.85));
  assert.equal(context.removed.length, context.uploads.length);
});

test('oversized minimum upload fails before calling paid API', async () => {
  const context = pipelineHarness(() => 100000);
  let calls = 0;
  await assert.rejects(context.runCloud({ imgPath: 'original.jpg',
    box: { width: 295, height: 413, top: 0 }, outW: 295, outH: 413 }, async () => { calls++; }),
  /上传图过大/);
  assert.equal(calls, 0);
  assert.equal(context.removed.length, context.uploads.length);
});
