// 锦鲤池车速联动调参 —— 本地桥接服务器。
//
// wallpaper-speed-tuner.html 是给人看/填参数的,但普通网页拿不到
// ws://localhost:9222 的连接权限(Chrome 的 DevTools 远程调试端口有 Origin
// 校验,专门挡网页脚本连它,只认没有浏览器 Origin 的客户端)。这个脚本就是
// 那个"没有浏览器 Origin 的客户端"——页面跟它做同源 fetch,它再去连 9222。
//
// 用法:
//   adb 连着车、留白开着、壁纸是锦鲤池,然后:
//   node tools/wallpaper-speed-tuner-server.mjs
//   浏览器打开 http://localhost:8935/
//
// 端口转发每次推送前自动重连(见 refreshForward),app 重启、pid 变了也不用管。

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const PORT = 8935;
const DEVTOOLS_PORT = 9222;
const APP_ID = 'com.liubai.blank.debug';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * 每次推送前重新转发。devtools 的 socket 名字带着 app 进程的 pid
 * (webview_devtools_remote_<pid>),app 一重启 pid 就变,旧转发指向的 socket
 * 不存在了——手动管这个最容易忘,干脆每次都重连一遍,反正 adb forward 是幂等的。
 */
async function refreshForward() {
  const { stdout } = await execFileAsync('adb', ['shell', 'pidof', APP_ID]);
  const pid = stdout.trim();
  if (!pid) throw new Error(`${APP_ID} 没在跑,先在车机上打开留白`);
  await execFileAsync('adb', ['forward', `tcp:${DEVTOOLS_PORT}`,
      `localabstract:webview_devtools_remote_${pid}`]);
}

async function findKoiTarget() {
  const res = await fetch(`http://localhost:${DEVTOOLS_PORT}/json`);
  const list = await res.json();
  return list.find(t => t.title === 'Koi Pond Wallpaper')
      || list.find(t => (t.url || '').includes('appassets.androidplatform.net'));
}

function evalOnTarget(wsUrl, expr) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const timer = setTimeout(() => { ws.close(); reject(new Error('连接超时')); }, 5000);
    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr } }));
    });
    ws.addEventListener('message', (ev) => {
      clearTimeout(timer);
      ws.close();
      resolve(JSON.parse(ev.data));
    });
    ws.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error('WebSocket 连接失败,adb forward 是不是断了'));
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/') {
    const html = fs.readFileSync(path.join(__dirname, 'wallpaper-speed-tuner.html'));
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }

  if (url.pathname === '/push') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    try {
      // 白名单——留白那边现在只接了这三个 livelyPropertyListener 属性
      // (WallpaperHost.pushSpeedToWeb/pushBatteryToWeb/pushChargingToWeb)
      const ALLOWED_PROPS = ['fishSpeed', 'fishCount', 'charging'];
      const prop = url.searchParams.get('prop');
      const value = Number(url.searchParams.get('value'));
      if (!ALLOWED_PROPS.includes(prop)) {
        res.end(JSON.stringify({ ok: false, error: `不认识的属性: ${prop}` }));
        return;
      }
      if (!Number.isFinite(value)) {
        res.end(JSON.stringify({ ok: false, error: 'value 不是数字' }));
        return;
      }
      await refreshForward();
      const target = await findKoiTarget();
      if (!target) {
        res.end(JSON.stringify({ ok: false, error: '没找到锦鲤池壁纸页面——先在车机上把壁纸切成锦鲤池' }));
        return;
      }
      const expr = `if(window.livelyPropertyListener){window.livelyPropertyListener('${prop}',${value});'ok'}else 'no-listener'`;
      const result = await evalOnTarget(target.webSocketDebuggerUrl, expr);
      const val = result?.result?.result?.value;
      if (result.error) {
        res.end(JSON.stringify({ ok: false, error: result.error.message }));
      } else if (val === 'no-listener') {
        res.end(JSON.stringify({ ok: false, error: '页面里没有 livelyPropertyListener,页面可能还没加载完' }));
      } else {
        res.end(JSON.stringify({ ok: true, prop, value }));
      }
    } catch (e) {
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  if (url.pathname === '/read') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    try {
      await refreshForward();
      const target = await findKoiTarget();
      if (!target) {
        res.end(JSON.stringify({ ok: false, error: '没找到锦鲤池壁纸页面' }));
        return;
      }
      const expr = `JSON.stringify({fishSpeed:config.fishSpeed,fishCount:config.fishCount,`
          + `charging:config.charging,kois:kois.length,foods:foods.length})`;
      const result = await evalOnTarget(target.webSocketDebuggerUrl, expr);
      const val = result?.result?.result?.value;
      res.end(JSON.stringify({ ok: true, state: JSON.parse(val) }));
    } catch (e) {
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(PORT, () => {
  console.log(`调参页面: http://localhost:${PORT}/`);
});
