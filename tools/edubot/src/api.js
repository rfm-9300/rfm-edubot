export const REQUEST_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const SESSION_ROUTES = ['/app/auth/login', '/app/auth/google', '/admin/auth/login', '/admin/auth/google'];
const PUBLIC_ROUTES = ['/health', '/ready', '/app/auth/config', '/admin/auth/config'];
const PROVIDER_ROUTES = ['/admin/api/instagram/callback', '/admin/api/instagram/deauthorize', '/admin/api/instagram/data-deletion'];
const LOOPBACK = ['localhost', '127.0.0.1', '[::1]'];

export class ApiError extends Error {
  constructor(status, code, detail) {
    const reason = code ? `: ${code}${detail ? ` (${detail})` : ''}` : '';
    super(`edubot API returned HTTP ${status}${reason}`);
    this.status = status;
    this.code = code;
  }
}

function originFrom(value) {
  if (!value) throw new Error('Set EDUBOT_URL to the dashboard origin.');
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error('EDUBOT_URL must be a valid URL.');
  }
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash ||
      !(url.protocol === 'https:' || (url.protocol === 'http:' && LOOPBACK.includes(url.hostname)))) {
    throw new Error('EDUBOT_URL must be an HTTPS origin or a loopback HTTP origin.');
  }
  return url.origin;
}

export function apiPath(path) {
  try {
    const pathname = path.split('?')[0];
    if (!['/app/api/', '/admin/api/', '/app/share/'].some((prefix) => pathname.startsWith(prefix)) ||
        path.includes('#') || /[\u0000-\u0020\u007f]/.test(path)) {
      throw new Error();
    }
    const decodedSegments = pathname.split('/').map(decodeURIComponent);
    if (decodedSegments.slice(1, -1).includes('')) throw new Error();
    if (PROVIDER_ROUTES.includes(decodedSegments.join('/').replace(/\/$/, ''))) throw new Error();
    for (const decoded of decodedSegments) {
      if (decoded === '.' || decoded === '..' || /[/\\%\u0000-\u001f\u007f]/.test(decoded)) {
        throw new Error();
      }
    }
    return path;
  } catch {
    throw new Error('Use an edubot API or share path with URL-encoded values and no dot segments.');
  }
}

function errorText(value) {
  if (typeof value === 'string' && /^[a-z0-9_. -]{1,100}$/i.test(value)) return value;
}

export function isJson(response) {
  const mime = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
  return mime === 'application/json' || mime?.endsWith('+json');
}

export async function jsonResponse(response) {
  if (response.status === 204 || response.status === 205) return null;
  if (!isJson(response)) {
    await response.body?.cancel();
    throw new Error('Expected a JSON response from the edubot API.');
  }
  try {
    return await response.json();
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error('The edubot API returned invalid JSON.');
    throw error;
  }
}

function bearer(value, name) {
  if (value !== undefined && value !== '' &&
      (typeof value !== 'string' || !/^[a-zA-Z0-9._~-]+$/.test(value))) throw new Error(`Invalid ${name}.`);
  return value;
}

export function createApi({
  url = process.env.EDUBOT_URL,
  token = process.env.EDUBOT_TOKEN,
  adminToken = process.env.EDUBOT_ADMIN_TOKEN,
  vaultToken = process.env.EDUBOT_VAULT_TOKEN,
} = {}) {
  const origin = originFrom(url);
  let dashboardToken = bearer(token, 'EDUBOT_TOKEN');
  bearer(adminToken, 'EDUBOT_ADMIN_TOKEN');
  if (vaultToken && !/^[a-zA-Z0-9+/]+={0,2}$/.test(vaultToken)) throw new Error('Invalid EDUBOT_VAULT_TOKEN.');

  async function request(path, { method = 'GET', query = {}, body, signal, vaultSession = vaultToken, accept = 'application/json' } = {}) {
    if (!REQUEST_METHODS.includes(method)) throw new Error(`Unsupported method: ${method}`);
    const publicRequest = (method === 'GET' && PUBLIC_ROUTES.includes(path)) ||
      (method === 'POST' && SESSION_ROUTES.includes(path));
    const target = new URL(publicRequest ? path : apiPath(path), origin);
    const headers = { accept };
    const scope = target.pathname.startsWith('/admin/api/') ? 'backoffice' :
      target.pathname.startsWith('/app/api/') ? 'dashboard' : 'public';
    if (scope !== 'public') {
      const credential = scope === 'backoffice' ? adminToken : dashboardToken;
      if (!credential) throw new Error(`Set ${scope === 'backoffice' ? 'EDUBOT_ADMIN_TOKEN' : 'EDUBOT_TOKEN'} to a ${scope} session token.`);
      headers.authorization = `Bearer ${credential}`;
    }
    if (vaultSession && (target.pathname === '/admin/api/vault' || target.pathname.startsWith('/admin/api/vault/'))) {
      headers['X-Vault-Auth'] = vaultSession;
    }
    for (const [key, value] of Object.entries(query)) {
      for (const item of Array.isArray(value) ? value : [value]) {
        if (item !== undefined) target.searchParams.append(key, String(item));
      }
    }
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers['content-type'] = 'application/json';
    const timeout = AbortSignal.timeout(30_000);
    const response = await fetch(target, {
      method,
      headers,
      body: body === undefined || multipart ? body : JSON.stringify(body),
      redirect: 'manual',
      signal: signal ? AbortSignal.any([timeout, signal]) : timeout,
    });
    if (response.ok) return response;
    let failure;
    if (isJson(response)) {
      failure = await response.json().catch(() => undefined);
    } else {
      await response.body?.cancel();
    }
    throw new ApiError(response.status, errorText(failure?.error), errorText(failure?.detail));
  }

  async function selectToken(nextToken, signal) {
    const candidate = bearer(nextToken, 'dashboard session token');
    const selected = candidate
      ? await createApi({ url: origin, token: candidate, adminToken, vaultToken }).json('/app/api/me', { signal })
      : null;
    dashboardToken = candidate;
    return selected;
  }

  return {
    origin,
    request,
    hasBackoffice: Boolean(adminToken),
    async selectDashboardSession(path, signal) {
      const session = await jsonResponse(await request(path, { method: 'POST', signal }));
      if (!session?.token) throw new Error('The dashboard did not return a session token.');
      return selectToken(session.token, signal);
    },
    restoreDashboardSession: (signal) => selectToken(token, signal),
    async json(path, options) {
      return jsonResponse(await request(path, options));
    },
  };
}
