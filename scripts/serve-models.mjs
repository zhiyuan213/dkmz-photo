/**
 * 本地静态服务器：把 models/ 目录里的 onnx 通过 HTTP 提供出来
 *
 * 用途：开发者工具里勾选「详情 → 本地设置 → 不校验合法域名、web-view、TLS 版本以及 HTTPS 证书」
 * 之后，小程序就能从 http://127.0.0.1:5178/model_fp16.onnx 拉模型，
 * 省掉「上传 CDN / 配 downloadFile 合法域名」这一步，先把技术可行性验证完。
 *
 * 真机调试时 127.0.0.1 不可用，需换成局域网 IP 或公网 HTTPS 地址。
 *
 * 用法：
 *   node scripts/serve-models.mjs            # 默认 5178 端口
 *   node scripts/serve-models.mjs 8080       # 指定端口
 */
import { createServer } from 'node:http';
import { createReadStream, statSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, normalize } from 'node:path';
import { networkInterfaces } from 'node:os';

const __dir = dirname(fileURLToPath(import.meta.url));
const ROOT = normalize(join(__dir, '..', 'models'));
const PORT = Number(process.argv[2] || 5178);

const MIME = {
  '.onnx': 'application/octet-stream',
  '.json': 'application/json; charset=utf-8',
};

const server = createServer((req, res) => {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const filePath = normalize(join(ROOT, urlPath));

  // 防目录穿越
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403).end('forbidden');
    return;
  }

  let st;
  try {
    st = statSync(filePath);
  } catch (e) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('404 not found: ' + urlPath + '\n可用文件：' + listAvailable());
    return;
  }
  if (st.isDirectory()) {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('目录索引：\n' + listAvailable());
    return;
  }

  const ext = filePath.slice(filePath.lastIndexOf('.'));
  res.writeHead(200, {
    'content-type': MIME[ext] || 'application/octet-stream',
    'content-length': st.size,
    'cache-control': 'no-cache',
  });
  console.log(`[${new Date().toTimeString().slice(0, 8)}] 200 ${urlPath} (${(st.size / 1048576).toFixed(1)}MB)`);
  createReadStream(filePath).pipe(res);
});

function listAvailable() {
  try {
    return readdirSync(ROOT).join('\n');
  } catch (e) {
    return '(读取失败)';
  }
}

server.listen(PORT, () => {
  const ips = Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
  console.log('模型静态服务已启动');
  console.log(`  本机（开发者工具用）: http://127.0.0.1:${PORT}/model_fp16.onnx`);
  console.log(`  局域网（真机用）    : ${ips.map((ip) => `http://${ip}:${PORT}/model_fp16.onnx`).join('  ') || '无'}`);
  console.log(`  可用文件            : ${listAvailable().split('\n').join(', ')}`);
  console.log('  提示：真机需在 mp 后台把域名加进「downloadFile 合法域名」且必须是 HTTPS');
});
