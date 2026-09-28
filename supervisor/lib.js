// Pure helpers for the updater supervisor — no I/O of their own (fetchAsset takes its fetch as a parameter),
// unit-tested (supervisor/test.js).

function parseSemver(v) {
  const m = String(v || '').replace(/^v/, '').match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? [+m[1], +m[2], +m[3]] : null;
}

function semverGt(a, b) {
  const x = parseSemver(a), y = parseSemver(b);
  if (!x || !y) return false;
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i];
  return false;
}

/** Newest non-draft release for the channel that actually ships a signed manifest. */
function pickRelease(releases, channel) {
  const allowPrerelease = channel === 'beta';
  return (releases || [])
    .filter((r) => !r.draft
      && (allowPrerelease || !r.prerelease)
      && (r.assets || []).some((a) => a.name === 'manifest.json'))
    .sort((a, b) => (semverGt((a.tag_name || '').replace(/^v/, ''), (b.tag_name || '').replace(/^v/, '')) ? -1 : 1))[0]
    || null;
}

/** Build the status object the console + recovery page read. Update only offered if VERIFIED. */
function shapeStatus({ current, manifest, verified, checkedAt, error }) {
  const latest = manifest && manifest.version;
  return {
    current,
    latest: latest || null,
    updateAvailable: !!(verified && latest && semverGt(latest, current)),
    verified: !!verified,
    channel: (manifest && manifest.channel) || null,
    checkedAt: checkedAt || null,
    error: error || null,
  };
}

/** Pull the image refs + target version a verified manifest applies. */
function imageTags(manifest) {
  const c = (manifest && manifest.components) || {};
  return {
    serverImage: c.serverImage || null,
    webImage: c.webImage || null,
    version: (manifest && manifest.version) || null,
  };
}

/** Resolve the downloadable APK for a verified release: the manifest's apk block + the GitHub asset's
 *  download URL (matched by file name). Returns null if any piece is missing. */
function apkAsset(release, manifest) {
  const apk = manifest && manifest.components && manifest.components.apk;
  if (!apk || !apk.file || apk.versionCode == null || !apk.sha256) return null;
  const asset = ((release && release.assets) || []).find((a) => a.name === apk.file);
  if (!asset || !asset.browser_download_url) return null;
  return {
    version: (manifest && manifest.version) || null,
    versionCode: apk.versionCode,
    sha256: apk.sha256,
    url: asset.browser_download_url,
    apiUrl: asset.url || null, // the asset API URL: the only one a private repo serves bytes from (assetRequest)
  };
}

/** Where and how to download a release asset. A PRIVATE repo 404s `browser_download_url` even with a token; only the
 *  asset API URL (`asset.url`) with `Accept: application/octet-stream` and the token serves the bytes. So: token and
 *  API URL → the API URL with auth; otherwise the browser URL, never with the token. */
function assetRequest(asset, token) {
  const headers = { 'User-Agent': 'mdmesh-updater' };
  if (token && asset && asset.url) {
    return { url: asset.url, headers: { ...headers, Accept: 'application/octet-stream', Authorization: 'Bearer ' + token } };
  }
  return { url: (asset && asset.browser_download_url) || null, headers };
}

/** Download a release asset (manifest, signature or APK) per assetRequest, following redirects by hand. The asset API
 *  answers 302 to a short-lived signed CDN URL on another host: the token must never go there, so Authorization is
 *  dropped as soon as a redirect changes origin, and never re-added. (Node's fetch strips it too on a cross-origin
 *  redirect, but that is an implementation detail; this makes the rule explicit and testable.) `fetchImpl` is
 *  injected so tests can script the responses. Returns the final Response; the caller checks `ok`. */
async function fetchAsset(asset, token, fetchImpl = globalThis.fetch, maxRedirects = 5) {
  const req = assetRequest(asset, token);
  if (!req.url) throw new Error('asset has no download URL');
  let url = req.url;
  let headers = req.headers;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const r = await fetchImpl(url, { headers, redirect: 'manual' });
    const loc = r.status >= 300 && r.status < 400 && r.headers.get('location');
    if (!loc) return r;
    const next = new URL(loc, url);
    if (next.origin !== new URL(url).origin && headers.Authorization) {
      const { Authorization, ...rest } = headers; // eslint-disable-line no-unused-vars
      headers = rest;
    }
    url = next.href;
  }
  throw new Error('too many redirects');
}

/** The value of `key` in .env text, read like apply.sh's get_env (the first `KEY=` line), with surrounding quotes,
 *  trailing whitespace and a CR removed. Empty or absent → null. */
function envValue(text, key) {
  const line = String(text || '').split('\n').find((l) => l.startsWith(key + '='));
  if (line == null) return null;
  const v = line.slice(key.length + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
  return v || null;
}

// Apply is a linear state machine the console + recovery page poll. The happy path advances
// authorizing → backup → pull → recreate → healthcheck → done. On any failure apply.sh jumps to
// `rollback` (transient) and ends at `rolled_back` or, if rollback itself fails, `failed`.
const APPLY_PHASES = ['authorizing', 'backup', 'pull', 'recreate', 'healthcheck', 'done'];
const APPLY_TERMINAL = ['done', 'rolled_back', 'failed'];

/** Next happy-path phase after `p`, or null at/after the end. */
function nextPhase(p) {
  const i = APPLY_PHASES.indexOf(p);
  return i < 0 || i >= APPLY_PHASES.length - 1 ? null : APPLY_PHASES[i + 1];
}

/** Fold one line of apply.sh/rollback.sh output into the live apply view: `PHASE <p>` sets the phase, `ERR <msg>`
 *  appends to the error (kept, not replaced: a failed rollback reports both why the apply failed and what state the
 *  stack is left in); anything else (compose output) returns the view unchanged. */
function applyLine(ap, line) {
  if (line.startsWith('PHASE ')) return { ...ap, phase: line.slice(6).trim() };
  if (line.startsWith('ERR ')) {
    const e = line.slice(4).trim();
    return { ...ap, error: ap && ap.error ? ap.error + ' | ' + e : e };
  }
  return ap;
}

/** True once an apply has reached a state the UI should stop polling on. */
function isTerminal(p) {
  return APPLY_TERMINAL.includes(p);
}

/** True iff `buf` hashes to `expectedSha` (hex, case-insensitive). The gate that decides whether a
 *  downloaded APK is allowed to be published to devices — keep it pure + tested. */
function sha256Matches(buf, expectedSha) {
  if (!expectedSha) return false;
  const got = require('crypto').createHash('sha256').update(buf).digest('hex');
  return got.toLowerCase() === String(expectedSha).toLowerCase();
}

/** True iff `name` is one of publishApk's temp files for the published file `base`: `<base>.<16 lowercase hex>.tmp`
 *  (server.js names them with crypto.randomBytes(8).toString('hex')). Only these are ever cleaned up from the
 *  public files directory. */
function isPublishTemp(name, base) {
  const prefix = base + '.';
  return name.startsWith(prefix) && /^[0-9a-f]{16}\.tmp$/.test(name.slice(prefix.length));
}

// Marks the recovery page with whether one-click apply/rollback works on this deployment, server-side, so the
// page hides Roll back (and shows the manual update steps) from the first paint — no JS or status fetch needed.
function recoveryPage(html, applySupported) {
  return html.replace('<body>', '<body data-apply="' + (applySupported ? '1' : '0') + '">');
}

module.exports = {
  parseSemver, semverGt, pickRelease, shapeStatus,
  imageTags, nextPhase, isTerminal, APPLY_PHASES, APPLY_TERMINAL,
  apkAsset, sha256Matches, recoveryPage, isPublishTemp, assetRequest, fetchAsset, envValue, applyLine,
};
