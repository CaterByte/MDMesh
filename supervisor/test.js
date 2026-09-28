const t = require('node:test');
const a = require('node:assert');
const { semverGt, pickRelease, shapeStatus, imageTags, nextPhase, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp,
  assetRequest, fetchAsset, envValue } = require('./lib');

t.test('semverGt', () => {
  a.equal(semverGt('1.2.4', '1.2.3'), true);
  a.equal(semverGt('1.2.3', '1.2.3'), false);
  a.equal(semverGt('v2.0.0', 'v1.9.9'), true);
  a.equal(semverGt('1.0.0', '2.0.0'), false);
  a.equal(semverGt('bad', '1.0.0'), false);
});

t.test('pickRelease — newest stable with a manifest', () => {
  const rs = [
    { tag_name: 'v1.0.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.2.0', prerelease: true, assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.1.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.3.0', assets: [{ name: 'other' }] },
    { tag_name: 'v9.9.9', draft: true, assets: [{ name: 'manifest.json' }] },
  ];
  a.equal(pickRelease(rs, 'stable').tag_name, 'v1.1.0'); // prerelease/no-manifest/draft excluded
  a.equal(pickRelease(rs, 'beta').tag_name, 'v1.2.0');   // prerelease allowed on beta
  a.equal(pickRelease([], 'stable'), null);
});

t.test('shapeStatus', () => {
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, true);
  a.equal(shapeStatus({ current: '1.1.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, false);
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: false }).updateAvailable, false);
});

t.test('imageTags', () => {
  const m = { version: '1.1.0', components: { serverImage: 'ghcr.io/o/mdmesh-server:1.1.0', webImage: 'ghcr.io/o/mdmesh-web:1.1.0' } };
  a.deepEqual(imageTags(m), { serverImage: 'ghcr.io/o/mdmesh-server:1.1.0', webImage: 'ghcr.io/o/mdmesh-web:1.1.0', version: '1.1.0' });
  a.deepEqual(imageTags(null), { serverImage: null, webImage: null, version: null });
});

t.test('apkAsset', () => {
  const manifest = { version: '1.2.0', components: { apk: { file: 'mdmesh-agent.apk', versionCode: 120, sha256: 'abc', signatureChecksum: 'x' } } };
  const release = { assets: [{ name: 'mdmesh-agent.apk', browser_download_url: 'https://gh/dl/mdmesh-agent.apk',
    url: 'https://api.gh/repos/o/r/releases/assets/7' }, { name: 'manifest.json' }] };
  // Both URLs are kept: a private repo only serves the bytes through the asset API URL (see assetRequest).
  a.deepEqual(apkAsset(release, manifest), { version: '1.2.0', versionCode: 120, sha256: 'abc', url: 'https://gh/dl/mdmesh-agent.apk',
    apiUrl: 'https://api.gh/repos/o/r/releases/assets/7' });
  const noApi = { assets: [{ name: 'mdmesh-agent.apk', browser_download_url: 'https://gh/dl/mdmesh-agent.apk' }] };
  a.equal(apkAsset(noApi, manifest).apiUrl, null);
  a.equal(apkAsset({ assets: [] }, manifest), null);     // asset not present
  a.equal(apkAsset(release, { version: '1.2.0', components: {} }), null); // no apk block
  a.equal(apkAsset(null, null), null);
});

// A private repo 404s browser_download_url even with a token; only the asset API URL + octet-stream + token serves
// the bytes (live rehearsal, brain/reviews/live-apply-rollback.md).
t.test('assetRequest — token: asset API URL with octet-stream + Bearer; no token: browser_download_url, no auth', () => {
  const asset = { url: 'https://api.github.com/repos/o/r/releases/assets/1', browser_download_url: 'https://github.com/o/r/releases/download/v1/m.json' };
  a.deepEqual(assetRequest(asset, 'tok'), { url: asset.url,
    headers: { 'User-Agent': 'mdmesh-updater', Accept: 'application/octet-stream', Authorization: 'Bearer tok' } });
  a.deepEqual(assetRequest(asset, ''), { url: asset.browser_download_url, headers: { 'User-Agent': 'mdmesh-updater' } });
  // No API URL known (an older cached shape): the browser URL, and the token is never sent to it.
  a.deepEqual(assetRequest({ browser_download_url: asset.browser_download_url }, 'tok'),
    { url: asset.browser_download_url, headers: { 'User-Agent': 'mdmesh-updater' } });
});

/** A scripted fetch: `routes` maps URL → { status, location?, body? }; every call is recorded with its headers. */
function mockFetch(routes) {
  const calls = [];
  const fn = async (url, opts) => {
    calls.push({ url: String(url), headers: { ...(opts && opts.headers) }, redirect: opts && opts.redirect });
    const r = routes[String(url)];
    if (!r) return new Response('nope', { status: 404 });
    return new Response(r.body == null ? null : r.body, { status: r.status || 200, headers: r.location ? { location: r.location } : {} });
  };
  fn.calls = calls;
  return fn;
}

t.test('fetchAsset — follows the API 302 to the CDN and never sends the token to another origin', async () => {
  const asset = { url: 'https://api.github.com/repos/o/r/releases/assets/1', browser_download_url: 'https://github.com/dl/m.json' };
  const f = mockFetch({
    [asset.url]: { status: 302, location: 'https://objects.githubusercontent.com/signed?sig=x' },
    'https://objects.githubusercontent.com/signed?sig=x': { status: 200, body: 'BYTES' },
  });
  const r = await fetchAsset(asset, 'tok', f);
  a.equal(r.status, 200);
  a.equal(await r.text(), 'BYTES');
  a.equal(f.calls.length, 2);
  a.equal(f.calls[0].headers.Authorization, 'Bearer tok');
  a.equal(f.calls[0].headers.Accept, 'application/octet-stream');
  a.equal(f.calls[1].headers.Authorization, undefined, 'the token must not reach the CDN host');
  a.ok(f.calls.every((c) => c.redirect === 'manual'), 'redirects are followed by hand, not by fetch');
});

t.test('fetchAsset — same-origin redirect keeps the token; once dropped it stays dropped; relative Location resolves', async () => {
  const f = mockFetch({
    'https://api.github.com/a': { status: 301, location: '/b' },
    'https://api.github.com/b': { status: 302, location: 'https://cdn.example/c' },
    'https://cdn.example/c': { status: 307, location: 'https://api.github.com/d' },
    'https://api.github.com/d': { status: 200, body: 'D' },
  });
  const r = await fetchAsset({ url: 'https://api.github.com/a' }, 'tok', f);
  a.equal(await r.text(), 'D');
  a.deepEqual(f.calls.map((c) => [c.url, c.headers.Authorization || null]), [
    ['https://api.github.com/a', 'Bearer tok'],
    ['https://api.github.com/b', 'Bearer tok'],
    ['https://cdn.example/c', null],
    ['https://api.github.com/d', null],
  ]);
});

t.test('fetchAsset — no token: browser_download_url; non-redirect errors are returned; redirect loops are cut off', async () => {
  const f = mockFetch({ 'https://github.com/dl/m.json': { status: 404 } });
  const r = await fetchAsset({ url: 'https://api.github.com/x', browser_download_url: 'https://github.com/dl/m.json' }, '', f);
  a.equal(r.status, 404);
  a.equal(f.calls[0].url, 'https://github.com/dl/m.json');
  a.equal(f.calls[0].headers.Authorization, undefined);
  const loop = mockFetch({ 'https://h/a': { status: 302, location: 'https://h/a' } });
  await a.rejects(fetchAsset({ browser_download_url: 'https://h/a' }, '', loop), /too many redirects/);
  a.equal(loop.calls.length, 6);
  await a.rejects(fetchAsset({}, 'tok', mockFetch({})), /no download URL/);
});

t.test('envValue — reads KEY=value from .env text the way apply.sh get_env does (first match), tolerating quotes/CRLF', () => {
  const env = 'A=1\nCURRENT_VERSION=0.0.2\nCURRENT_VERSION=9.9.9\n# CURRENT_VERSION=bad\n';
  a.equal(envValue(env, 'CURRENT_VERSION'), '0.0.2');
  a.equal(envValue('CURRENT_VERSION="0.3.1"\r\n', 'CURRENT_VERSION'), '0.3.1');
  a.equal(envValue("CURRENT_VERSION='0.3.1'  \n", 'CURRENT_VERSION'), '0.3.1');
  a.equal(envValue('XCURRENT_VERSION=1\n', 'CURRENT_VERSION'), null);
  a.equal(envValue('CURRENT_VERSION=\n', 'CURRENT_VERSION'), null);  // empty = unset
  a.equal(envValue('', 'CURRENT_VERSION'), null);
  a.equal(envValue(null, 'CURRENT_VERSION'), null);
});

t.test('sha256Matches — the APK publish gate', () => {
  const buf = Buffer.from('hello');
  const sha = require('crypto').createHash('sha256').update(buf).digest('hex');
  a.equal(sha256Matches(buf, sha), true);
  a.equal(sha256Matches(buf, sha.toUpperCase()), true);   // case-insensitive
  a.equal(sha256Matches(buf, 'deadbeef'), false);         // mismatch → refuse
  a.equal(sha256Matches(buf, ''), false);                 // missing → refuse
  a.equal(sha256Matches(Buffer.from('hellö'), sha), false);
});

t.test('apply phase state machine', () => {
  a.equal(nextPhase('authorizing'), 'backup');
  a.equal(nextPhase('backup'), 'pull');
  a.equal(nextPhase('healthcheck'), 'done');
  a.equal(nextPhase('done'), null);     // end of happy path
  a.equal(nextPhase('rollback'), null); // not on the happy path
  a.equal(isTerminal('done'), true);
  a.equal(isTerminal('rolled_back'), true);
  a.equal(isTerminal('failed'), true);
  a.equal(isTerminal('pull'), false);
  a.equal(isTerminal('rollback'), false);
});

t.test('recoveryPage — marks the page with whether apply/rollback is supported', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  a.equal(html.split('<body>').length, 2, 'recovery.html must have exactly one bare <body> tag to mark');
  const off = recoveryPage(html, false), on = recoveryPage(html, true);
  a.ok(off.includes('<body data-apply="0">'));
  a.ok(on.includes('<body data-apply="1">'));
  a.ok(!off.includes('<body>') && !on.includes('<body>'));
  // The page itself hides the Roll back card and shows the manual steps under data-apply="0" (CSS, no JS needed).
  a.match(html, /body\[data-apply="0"\] #rbcard\{display:none\}/);
  a.match(html, /body:not\(\[data-apply="0"\]\) #manual\{display:none\}/);
  a.ok(html.includes('git pull &amp;&amp; ./setup.sh') && html.includes('git pull &amp;&amp; sudo ./install/install-native.sh'));
});

t.test('recovery.html escapes status strings before they reach innerHTML', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  const m = html.match(/^function esc\(x\)\{.*\}$/m);
  a.ok(m, 'recovery.html defines a one-line function esc(x){…}');
  const esc = new Function(m[0] + '; return esc;')();
  a.equal(esc('<img src=x onerror="a()">&\''), '&lt;img src=x onerror=&quot;a()&quot;&gt;&amp;&#39;');
  a.equal(esc(null), '');
  a.equal(esc(42), '42');
  // Every server/network-derived string concatenated into markup goes through esc() (or v(), which wraps it).
  a.match(html, /function v\(x\)\{return x==null\?'—':esc\(x\)\}/);
  for (const raw of ["'+s.error+'", "'+a.error+'", '(PH[a.phase]||a.phase)', "(a.fromVersion||'?')", "' → '+a.toVersion",
                     "'+(b.error||", "'+e+'"]) {
    a.ok(!html.includes(raw), 'unescaped interpolation left in recovery.html: ' + raw);
  }
});

t.test('isPublishTemp — only publishApk\'s own temp names', () => {
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp', 'agent.apk'), true);
  a.equal(isPublishTemp('agent.apk.0123456789ABCDEF.tmp', 'agent.apk'), false); // randomBytes().toString('hex') is lowercase
  a.equal(isPublishTemp('agent.apk.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agent.apk.0123456789abcde.tmp', 'agent.apk'), false);  // 15 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef0.tmp', 'agent.apk'), false); // 17 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp.x', 'agent.apk'), false);
  a.equal(isPublishTemp('other.apk.0123456789abcdef.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agentXapk.0123456789abcdef.tmp', 'agent.apk'), false);  // the '.' in the name is literal
  a.equal(isPublishTemp('agent.apk.backup', 'agent.apk'), false);
  a.equal(isPublishTemp('a+b(1).apk.0123456789abcdef.tmp', 'a+b(1).apk'), true);  // regex metacharacters in the name
});

// --- Process-level: the real server.js against a local fake GitHub (no network). Skipped without minisign; the
// supervisor image (where CI runs this file) ships it. ---
const cp = require('child_process');
const HAS_MINISIGN = cp.spawnSync('minisign', ['-v']).status === 0;

// publishApk's temp files (PUBLISH_APK_TO.<16 hex>.tmp) sit in Tomcat's public files/ dir; one left by a process that
// died mid-copy would be served under /files/. The supervisor removes them when it starts (and before each publish,
// below): only that exact shape, only a regular file or a link (the link itself, never its target).
t.test('stale publish temp files are removed at start; nothing else in the files dir is touched', { timeout: 20000 }, async (tt) => {
  const fs = require('fs'), os = require('os'), path = require('path'), http = require('http');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-tmp-'));
  let child = null;
  tt.after(async () => {
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = new Promise((r) => child.once('exit', r));
      child.kill();
      await exited;
    }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
  fs.mkdirSync(files);
  fs.writeFileSync(outside, 'OUTSIDE');
  fs.writeFileSync(path.join(files, 'agent.apk'), 'CURRENT APK');
  fs.writeFileSync(path.join(files, 'agent.apk.0123456789abcdef.tmp'), 'PARTIAL COPY');     // stale: removed
  fs.symlinkSync(outside, path.join(files, 'agent.apk.fedcba9876543210.tmp'));              // stale link: unlinked, target kept
  fs.mkdirSync(path.join(files, 'agent.apk.aaaaaaaaaaaaaaaa.tmp'));                         // a directory: left alone
  for (const keep of ['agent.apk.tmp', 'agent.apk.backup', 'agent.apk.0123.tmp', 'other.apk.0123456789abcdef.tmp']) {
    fs.writeFileSync(path.join(files, keep), 'KEEP');
  }
  const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
  child = cp.spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    stdio: ['ignore', 'pipe', 'pipe'],
    // No GITHUB_REPO: the startup poll does nothing, so nothing is published and only the start-up cleanup can act.
    env: { ...process.env, GITHUB_REPO: '', GITHUB_TOKEN: '', SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1',
      APPLY_SUPPORTED: '0', AUTO_UPDATE: '0', MANIFEST_PUBKEY: path.join(dir, 'none.pub'), APK_CACHE_DIR: path.join(dir, 'apk'),
      PUBLISH_APK_TO: path.join(files, 'agent.apk'), AUTO_FILE: path.join(dir, 'auto.json'),
      RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });
  let log = '';
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('supervisor never listened:\n' + log)), 10000);
    const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
    const onData = (d) => { log += d; if (log.includes('supervisor on')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', onExit);
  });
  a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.0123.tmp', 'agent.apk.aaaaaaaaaaaaaaaa.tmp', 'agent.apk.backup',
    'agent.apk.tmp', 'other.apk.0123456789abcdef.tmp'], 'only the stale temp file and link are gone');
  a.equal(fs.readFileSync(outside, 'utf8'), 'OUTSIDE', 'the stale link\'s target is untouched');
  a.equal(fs.readFileSync(path.join(files, 'agent.apk'), 'utf8'), 'CURRENT APK');
});

t.test('/update/status reports the mirrored APK as available once the warm-up download lands, without re-polling; '
  + 'the published copy never goes through a link planted at a temp name, and stale publish temps are removed first',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-apk-'));
    let gh = null, child = null;
    // One hook, in order: the supervisor and the fake GitHub are fully down before the temp dir they use is removed.
    tt.after(async () => {
      if (child && child.exitCode === null && child.signalCode === null) {
        const exited = new Promise((r) => child.once('exit', r));
        child.kill();
        await exited;
      }
      if (gh) { gh.closeAllConnections(); await new Promise((r) => gh.close(r)); }
      fs.rmSync(dir, { recursive: true, force: true });
    });

    // A signed release: throwaway key pair, a manifest naming the APK by sha256, the detached signature beside it.
    const apk = crypto.randomBytes(4096);
    const manifest = { version: '9.9.9', channel: 'stable', components: { apk: {
      file: 'mdmesh-agent.apk', versionCode: 999, sha256: crypto.createHash('sha256').update(apk).digest('hex') } } };
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest));
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'k.pub'), '-s', path.join(dir, 'k.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', path.join(dir, 'k.key'), '-m', path.join(dir, 'manifest.json')], { stdio: 'ignore' });

    // Fake GitHub: the releases API (counted) and the three release assets.
    let releaseCalls = 0;
    gh = http.createServer((req, res) => {
      const base = `http://127.0.0.1:${gh.address().port}`;
      if (req.url.startsWith('/repos/o/r/releases')) {
        releaseCalls++;
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify([{ tag_name: 'v9.9.9', html_url: base + '/rel', assets: ['manifest.json', 'manifest.json.minisig', 'mdmesh-agent.apk']
          .map((name) => ({ name, browser_download_url: `${base}/dl/${name}` })) }]));
      } else if (req.url === '/dl/mdmesh-agent.apk') {
        // A stale publish temp that appears after start-up (not seen by the start-up cleanup): the publish removes it.
        fs.writeFileSync(path.join(dir, 'files', 'agent.apk.1111111111111111.tmp'), 'STALE');
        res.end(apk);
      }
      else if (req.url === '/dl/manifest.json' || req.url === '/dl/manifest.json.minisig') res.end(fs.readFileSync(path.join(dir, req.url.slice(4))));
      else { res.statusCode = 404; res.end(); }
    });
    await new Promise((r) => gh.listen(0, '127.0.0.1', r));

    // server.js calls the fixed https://api.github.com origin; a preload points only that origin at the fake.
    const preload = path.join(dir, 'fake-github.js');
    fs.writeFileSync(preload, "const f = globalThis.fetch;\n"
      + "globalThis.fetch = (u, o) => f(String(u).replace('https://api.github.com', process.env.FAKE_GITHUB), o);\n");
    // On a native install PUBLISH_APK_TO is in Tomcat's files/ directory, and older versions ran the supervisor as root
    // there. A link planted at the predictable temp name the publish step used to write through must be left alone,
    // target included.
    const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
    fs.mkdirSync(files);
    fs.writeFileSync(outside, 'NOT AN APK');
    fs.symlinkSync(outside, path.join(files, 'agent.apk.tmp'));
    const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
    child = cp.spawn(process.execPath, ['--require', preload, path.join(__dirname, 'server.js')], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r', GITHUB_TOKEN: '',
        SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1', CURRENT_VERSION: '9.9.9', APPLY_SUPPORTED: '0', AUTO_UPDATE: '0',
        UPDATE_CHANNEL: 'stable', POLL_INTERVAL_HOURS: '6',
        MANIFEST_PUBKEY: path.join(dir, 'k.pub'), APK_CACHE_DIR: path.join(dir, 'apk'), PUBLISH_APK_TO: path.join(files, 'agent.apk'),
        AUTO_FILE: path.join(dir, 'auto.json'), RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });

    // Wait for the startup poll's warm-up download to land. server.js logs "[apk] mirrored" in the same synchronous
    // block that publishes the file, so any request answered after this line sees the post-download state.
    let log = '';
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('APK never mirrored; supervisor output:\n' + log)), 10000);
      const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
      const onData = (d) => { log += d; if (log.includes('[apk] mirrored')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
      child.stdout.on('data', onData);
      child.stderr.on('data', onData);
      child.on('exit', onExit);
    });

    const status = await (await fetch(`http://127.0.0.1:${port}/update/status`)).json();
    a.equal(status.verified, true, 'the fake release verifies against the throwaway key');
    a.deepEqual({ versionCode: status.apk && status.apk.versionCode, available: status.apk && status.apk.available },
      { versionCode: 999, available: true }, '/update/status must say available once the APK is being served');
    a.equal(releaseCalls, 1, 'the refresh comes from the download itself, not from another poll');

    // publishApk runs in the same synchronous block as the "[apk] mirrored" line, so it has finished by now.
    a.ok(fs.lstatSync(path.join(files, 'agent.apk')).isFile(), 'PUBLISH_APK_TO is a regular file, not the planted link');
    a.ok(fs.readFileSync(path.join(files, 'agent.apk')).equals(apk), 'the verified APK is published to PUBLISH_APK_TO');
    a.equal(fs.readFileSync(outside, 'utf8'), 'NOT AN APK', 'the planted link was not written through');
    a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.tmp'],
      'no temp file is left behind, and the stale one that appeared before the publish is gone');
  });

// --- Shared harness for the process-level tests below: a free port, and server.js spawned with a clean env. ---
function freePort() {
  const http = require('http');
  return new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
}
/** Spawn server.js with `env` (merged over a minimal safe base) and resolve once `until` appears in its output.
 *  Returns { child, port, log() }. The caller's tt.after must kill it (use stopChild). */
async function spawnSupervisor(dir, env, until, extraArgs = []) {
  const path = require('path');
  const port = await freePort();
  const child = cp.spawn(process.execPath, [...extraArgs, path.join(__dirname, 'server.js')], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, GITHUB_REPO: '', GITHUB_TOKEN: '', SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1',
      APPLY_SUPPORTED: '0', AUTO_UPDATE: '0', UPDATE_CHANNEL: 'stable', POLL_INTERVAL_HOURS: '6', CURRENT_VERSION: '0.0.0',
      MANIFEST_PUBKEY: path.join(dir, 'none.pub'), APK_CACHE_DIR: path.join(dir, 'apk'), PUBLISH_APK_TO: '',
      AUTO_FILE: path.join(dir, 'auto.json'), RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token'),
      COMPOSE_PROJECT_DIR: path.join(dir, 'no-project'), ...env } });
  let log = '';
  const onData = (d) => { log += d; };
  child.stdout.on('data', onData);
  child.stderr.on('data', onData);
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`never saw ${until}; supervisor output:\n` + log)), 10000);
      const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
      const check = () => { if (until.test(log)) { clearTimeout(timer); child.off('exit', onExit); child.stdout.off('data', check); child.stderr.off('data', check); resolve(); } };
      child.stdout.on('data', check);
      child.stderr.on('data', check);
      child.on('exit', onExit);
      check();
    });
  } catch (e) {
    await stopChild(child); // never leave a supervisor running (it would keep the test runner alive)
    throw e;
  }
  return { child, port, log: () => log };
}
async function stopChild(child) {
  if (child && child.exitCode === null && child.signalCode === null) {
    const exited = new Promise((r) => child.once('exit', r));
    child.kill();
    await exited;
  }
}
async function waitFor(fn, what, ms = 10000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error('timed out waiting for ' + what);
    await new Promise((r) => setTimeout(r, 50));
  }
}

t.test('private repo: with GITHUB_TOKEN the manifest, signature and APK come through the asset API URL, and the token '
  + 'never reaches the CDN it redirects to', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
  const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-priv-'));
  let gh = null, cdn = null, sup = null;
  tt.after(async () => {
    await stopChild(sup && sup.child);
    for (const s of [gh, cdn]) if (s) { s.closeAllConnections(); await new Promise((r) => s.close(r)); }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const TOKEN = 'ghp_TESTTOKEN_' + crypto.randomBytes(6).toString('hex');
  const apk = crypto.randomBytes(2048);
  const manifest = { version: '9.9.9', channel: 'stable', components: { apk: {
    file: 'mdmesh-agent.apk', versionCode: 999, sha256: crypto.createHash('sha256').update(apk).digest('hex') } } };
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest));
  cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'k.pub'), '-s', path.join(dir, 'k.key')], { stdio: 'ignore' });
  cp.execFileSync('minisign', ['-S', '-s', path.join(dir, 'k.key'), '-m', path.join(dir, 'manifest.json')], { stdio: 'ignore' });
  const bytes = { 'manifest.json': fs.readFileSync(path.join(dir, 'manifest.json')),
    'manifest.json.minisig': fs.readFileSync(path.join(dir, 'manifest.json.minisig')), 'mdmesh-agent.apk': apk };

  // The CDN is a different origin (localhost vs 127.0.0.1): it refuses any request that carries the token.
  const cdnHits = [];
  cdn = http.createServer((req, res) => {
    cdnHits.push({ url: req.url, auth: req.headers.authorization || null });
    if (req.headers.authorization) { res.statusCode = 400; res.end('auth header forwarded'); return; }
    const name = decodeURIComponent(req.url.replace(/^\/signed\//, '').replace(/\?.*$/, ''));
    if (bytes[name]) res.end(bytes[name]); else { res.statusCode = 404; res.end(); }
  });
  await new Promise((r) => cdn.listen(0, '127.0.0.1', r));
  // Private-repo GitHub: browser_download_url is always 404; the asset API URL needs the token + octet-stream, then 302s.
  const apiHits = [];
  gh = http.createServer((req, res) => {
    const base = `http://127.0.0.1:${gh.address().port}`;
    if (req.url.startsWith('/repos/o/r/releases')) {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify([{ tag_name: 'v9.9.9', html_url: base + '/rel', assets: Object.keys(bytes)
        .map((name) => ({ name, url: `${base}/api/assets/${name}`, browser_download_url: `${base}/dl/${name}` })) }]));
    } else if (req.url.startsWith('/api/assets/')) {
      apiHits.push({ auth: req.headers.authorization || null, accept: req.headers.accept });
      if (req.headers.authorization !== 'Bearer ' + TOKEN || req.headers.accept !== 'application/octet-stream') { res.statusCode = 404; res.end(); return; }
      res.writeHead(302, { location: `http://localhost:${cdn.address().port}/signed/${req.url.slice('/api/assets/'.length)}?X-Amz-Signature=abc` });
      res.end();
    } else { res.statusCode = 404; res.end(); }
  });
  await new Promise((r) => gh.listen(0, '127.0.0.1', r));
  const preload = path.join(dir, 'fake-github.js');
  fs.writeFileSync(preload, "const f = globalThis.fetch;\n"
    + "globalThis.fetch = (u, o) => f(String(u).replace('https://api.github.com', process.env.FAKE_GITHUB), o);\n");

  sup = await spawnSupervisor(dir, { FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r', GITHUB_TOKEN: TOKEN,
    CURRENT_VERSION: '9.9.0', MANIFEST_PUBKEY: path.join(dir, 'k.pub') }, /\[apk\] mirrored/, ['--require', preload]);
  const status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
  a.equal(status.verified, true, 'the private release verifies:\n' + sup.log());
  a.equal(status.updateAvailable, true);
  a.equal(status.apk && status.apk.available, true, 'the APK was mirrored through the API URL');
  a.equal(apiHits.length, 3, 'manifest, signature and APK all went through the asset API URL');
  a.equal(cdnHits.length, 3);
  a.ok(cdnHits.every((h) => h.auth === null), 'no request to the CDN carried the token');
  a.ok(!sup.log().includes(TOKEN), 'the token never appears in the log');
});

t.test('an unverifiable release logs why (HTTP status / minisign), without secrets, and says so in /update/status',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path'), http = require('http');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-bad-'));
    let gh = null, sup = null;
    tt.after(async () => {
      await stopChild(sup && sup.child);
      if (gh) { gh.closeAllConnections(); await new Promise((r) => gh.close(r)); }
      fs.rmSync(dir, { recursive: true, force: true });
    });
    // Signed with key A, verified against key B.
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ version: '9.9.9' }));
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'a.pub'), '-s', path.join(dir, 'a.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'b.pub'), '-s', path.join(dir, 'b.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', path.join(dir, 'a.key'), '-m', path.join(dir, 'manifest.json')], { stdio: 'ignore' });
    let sigStatus = 200;
    gh = http.createServer((req, res) => {
      const base = `http://127.0.0.1:${gh.address().port}`;
      if (req.url.startsWith('/repos/o/r/releases')) {
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify([{ tag_name: 'v9.9.9', assets: ['manifest.json', 'manifest.json.minisig']
          .map((name) => ({ name, browser_download_url: `${base}/dl/${name}` })) }]));
      } else if (req.url === '/dl/manifest.json') res.end(fs.readFileSync(path.join(dir, 'manifest.json')));
      else if (req.url === '/dl/manifest.json.minisig') {
        if (sigStatus !== 200) { res.statusCode = sigStatus; res.end(); return; }
        res.end(fs.readFileSync(path.join(dir, 'manifest.json.minisig')));
      } else { res.statusCode = 404; res.end(); }
    });
    await new Promise((r) => gh.listen(0, '127.0.0.1', r));
    const preload = path.join(dir, 'fake-github.js');
    fs.writeFileSync(preload, "const f = globalThis.fetch;\n"
      + "globalThis.fetch = (u, o) => f(String(u).replace('https://api.github.com', process.env.FAKE_GITHUB), o);\n");

    sup = await spawnSupervisor(dir, { FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r',
      MANIFEST_PUBKEY: path.join(dir, 'b.pub') }, /\[verify\]/, ['--require', preload]);
    a.match(sup.log(), /\[verify\] .*minisign/i, 'a wrong-key signature is logged as a minisign failure');
    let status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    a.equal(status.verified, false);
    a.match(status.error, /minisign/i, '/update/status says why');
    await stopChild(sup.child);

    sigStatus = 404;
    sup = await spawnSupervisor(dir, { FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r',
      MANIFEST_PUBKEY: path.join(dir, 'b.pub') }, /\[verify\]/, ['--require', preload]);
    a.match(sup.log(), /\[verify\] .*manifest\.json\.minisig.*HTTP 404/, 'a missing asset is logged with its HTTP status');
    status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    a.match(status.error, /HTTP 404/);
  });

// --- Stub docker + curl for driving apply.sh / rollback.sh without a daemon. The stub docker logs every call (one line
// per call: its args) to $STUB_LOG and, for `compose exec -T postgres psql|pg_dump`, reads/writes stdio like the real
// thing. Failure knobs (env): STUB_FAIL_RE (egrep on the args → exit 1), STUB_PSQL_FAIL=1 (psql prints an ERROR and
// exits 3, as psql -v ON_ERROR_STOP=1 does). curl succeeds unless STUB_CURL_FAIL=1. ---
function makeStubs(dir) {
  const fs = require('fs'), path = require('path');
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(bin, { recursive: true });
  fs.writeFileSync(path.join(bin, 'docker'), `#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
if [ -n "\${STUB_FAIL_RE:-}" ] && echo "$*" | grep -Eq "$STUB_FAIL_RE"; then echo "stub: forced failure: $*" >&2; exit 1; fi
case "$*" in
  *" pg_dump "*) echo "-- stub dump"; echo "SELECT 1;";;
  *" psql "*)
    cat > "$STUB_LOG.stdin"
    if [ "\${STUB_PSQL_FAIL:-0}" = 1 ]; then echo 'psql:<stdin>:12: ERROR:  relation "x" does not exist' >&2; exit 3; fi;;
esac
exit 0
`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'curl'), '#!/usr/bin/env bash\n[ "${STUB_CURL_FAIL:-0}" = 1 ] && exit 7\nexit 0\n', { mode: 0o755 });
  return bin;
}
/** A deploy dir (project/.env) + backups dir + stubs under a fresh temp dir. */
function makeDeploy(envText) {
  const fs = require('fs'), os = require('os'), path = require('path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-sh-'));
  const project = path.join(dir, 'project'), backups = path.join(dir, 'backups');
  fs.mkdirSync(project); fs.mkdirSync(backups);
  fs.writeFileSync(path.join(project, '.env'), envText);
  const bin = makeStubs(dir);
  const log = path.join(dir, 'docker.log');
  fs.writeFileSync(log, '');
  const env = { ...process.env, PATH: bin + path.delimiter + process.env.PATH, STUB_LOG: log, COMPOSE_PROJECT_DIR: project,
    BACKUP_DIR: backups, HEALTH_TIMEOUT: '1', HEALTH_URL: 'http://stub/health' };
  return { dir, project, backups, log, env,
    envFile: () => fs.readFileSync(path.join(project, '.env'), 'utf8'),
    calls: () => fs.readFileSync(log, 'utf8').split('\n').filter(Boolean) };
}
function runScript(name, args, env) {
  const r = cp.spawnSync('bash', [require('path').join(__dirname, name), ...args], { env, encoding: 'utf8', timeout: 30000 });
  return { code: r.status, out: r.stdout, err: r.stderr, all: r.stdout + r.stderr };
}

t.test('supervisor start: CURRENT_VERSION comes from the project .env (what apply/rollback write), not the stale container env',
  { timeout: 20000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
    let sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();

    // A restart after an apply: the container env still says 0.0.1, .env says 0.0.2.
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.0.2', 'the version apply.sh wrote to .env wins over the container env');
    await stopChild(sup.child);

    // No CURRENT_VERSION in .env (or no .env): the container env is the fallback.
    fs.writeFileSync(path.join(d.project, '.env'), 'SERVER_VERSION=0.0.2\n');
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.0.1');
    await stopChild(sup.child);

    // Native (APPLY_SUPPORTED=0): nothing writes a project .env there; the unit's env is the only source, even if a
    // stray .env exists at the project path.
    fs.writeFileSync(path.join(d.project, '.env'), 'CURRENT_VERSION=7.7.7\n');
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '0', CURRENT_VERSION: '0.4.0', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.4.0');
  });

t.test('rollback resets /update/status current to the version it restored', { timeout: 30000 }, async (tt) => {
  const fs = require('fs'), path = require('path');
  // State after a successful apply 0.0.1 → 0.0.2: .env bumped, backup snapshot of the old versions + dump.
  const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
  fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n');
  fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
  let sup = null;
  tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
  sup = await spawnSupervisor(d.dir, { ...d.env, APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', SERVER_BASE: 'http://127.0.0.1:9' },
    /supervisor on/);
  const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
  a.equal((await status()).current, '0.0.2');
  const r = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
    headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
  a.equal(r.status, 202);
  const s = await waitFor(async () => { const x = await status(); return isTerminal(x.apply && x.apply.phase) && x; }, 'rollback to finish', 20000);
  a.equal(s.apply.phase, 'rolled_back', sup.log());
  a.equal(s.current, '0.0.1', 'current follows the rollback');
  a.equal(s.apply.toVersion, '0.0.1', 'the rollback view names the version it restored');
  a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
});

t.test('rollback.sh restores CURRENT_VERSION from the snapshot (not SERVER_VERSION), and apply.sh snapshots it', () => {
  const fs = require('fs'), path = require('path');
  // A :latest quick-start (SERVER_VERSION=latest, CURRENT_VERSION=0.0.0) applied 0.0.2; rolling back must restore
  // CURRENT_VERSION=0.0.0, not "latest" (which is no version at all and would hide every future update).
  const d = makeDeploy('SERVER_VERSION=latest\nWEB_VERSION=latest\nCURRENT_VERSION=0.0.0\n');
  try {
    let r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_CURL_FAIL: '0' });
    a.equal(r.code, 0, r.all);
    const stamp = fs.readFileSync(path.join(d.backups, 'latest'), 'utf8').trim();
    a.match(fs.readFileSync(path.join(d.backups, stamp + '.env'), 'utf8'), /^CURRENT_VERSION=0\.0\.0$/m, 'apply.sh snapshots CURRENT_VERSION');
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m);
    r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.0$/m);
    a.match(d.envFile(), /^SERVER_VERSION=latest$/m);
    // An older snapshot without CURRENT_VERSION still falls back to its SERVER_VERSION.
    fs.writeFileSync(path.join(d.backups, stamp + '.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\n');
    r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('with AUTO_UPDATE on, a rollback is not undone by auto-applying the release just rolled away from',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async (tt) => {
    const fs = require('fs'), path = require('path'), http = require('http');
    const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
    fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n');
    fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
    // The signed 0.0.2 release is the latest one on the channel.
    fs.writeFileSync(path.join(d.dir, 'manifest.json'), JSON.stringify({ version: '0.0.2', channel: 'stable', components: {} }));
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(d.dir, 'k.pub'), '-s', path.join(d.dir, 'k.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', path.join(d.dir, 'k.key'), '-m', path.join(d.dir, 'manifest.json')], { stdio: 'ignore' });
    let polls = 0;
    const gh = http.createServer((req, res) => {
      const base = `http://127.0.0.1:${gh.address().port}`;
      if (req.url.startsWith('/repos/o/r/releases')) {
        polls++;
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify([{ tag_name: 'v0.0.2', assets: ['manifest.json', 'manifest.json.minisig']
          .map((name) => ({ name, browser_download_url: `${base}/dl/${name}` })) }]));
      } else if (req.url.startsWith('/dl/')) res.end(fs.readFileSync(path.join(d.dir, req.url.slice(4))));
      else { res.statusCode = 404; res.end(); }
    });
    await new Promise((r) => gh.listen(0, '127.0.0.1', r));
    const preload = path.join(d.dir, 'fake-github.js');
    fs.writeFileSync(preload, "const f = globalThis.fetch;\n"
      + "globalThis.fetch = (u, o) => f(String(u).replace('https://api.github.com', process.env.FAKE_GITHUB), o);\n");
    let sup = null;
    tt.after(async () => {
      await stopChild(sup && sup.child);
      gh.closeAllConnections(); await new Promise((r) => gh.close(r));
      fs.rmSync(d.dir, { recursive: true, force: true });
    });
    sup = await spawnSupervisor(d.dir, { ...d.env, FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r',
      MANIFEST_PUBKEY: path.join(d.dir, 'k.pub'), APPLY_SUPPORTED: '1', AUTO_UPDATE: '1', CURRENT_VERSION: '0.0.1',
      SERVER_BASE: 'http://127.0.0.1:9' }, /supervisor on/, ['--require', preload]);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    await waitFor(async () => (await status()).verified, 'the startup poll');
    a.equal((await status()).updateAvailable, false, 'running 0.0.2 already');

    const r = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
      headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
    a.equal(r.status, 202);
    // The rollback's own poll sees 0.0.2 as an update again; it must stay offered, not be auto-applied.
    await waitFor(async () => polls >= 2, 'the post-rollback poll', 20000);
    await new Promise((res) => setTimeout(res, 500)); // the poll's verify + setStatus (an auto-apply would start there)
    const after = await status();
    a.equal(after.apply.trigger, 'rollback', 'no apply started after the rollback:\n' + sup.log());
    a.equal(after.apply.phase, 'rolled_back');
    a.equal(after.current, '0.0.1');
    a.equal(after.updateAvailable, true, '0.0.2 is still offered for a manual Update');
    a.ok(!d.calls().some((c) => c.includes('pg_dump')), 'apply.sh never ran');
  });
