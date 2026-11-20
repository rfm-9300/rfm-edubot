const DASHBOARD_PREFIX = '/app/api/';
export const REQUEST_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const SESSION_ROUTES = ['/app/auth/login', '/app/auth/google'];
const PUBLIC_ROUTES = ['/health', '/ready'];
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

export function dashboardPath(path) {
  try {
    const pathname = path.split('?')[0];
    if (!pathname.startsWith(DASHBOARD_PREFIX) || path.includes('#') || /[\u0000-\u0020\u007f]/.test(path)) {
      throw new Error();
    }
    for (const segment of pathname.split('/')) {
      const decoded = decodeURIComponent(segment);
      if (decoded === '.' || decoded === '..' || /[/\\%\u0000-\u001f\u007f]/.test(decoded)) {
        throw new Error();
      }
    }
    return path;
  } catch {
    throw new Error('Use a path under /app/api/ with URL-encoded query values and no dot segments.');
  }
}

function errorText(value) {
  if (typeof value === 'string' && /^[a-z0-9_. -]{1,100}$/i.test(value)) return value;
}

function isJson(response) {
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

export function createApi({ url = process.env.EDUBOT_URL, token = process.env.EDUBOT_TOKEN } = {}) {
  const origin = originFrom(url);
  if (token && !/^[a-zA-Z0-9._~-]+$/.test(token)) {
    throw new Error('EDUBOT_TOKEN must contain a dashboard bearer token.');
  }

  async function request(path, { method = 'GET', query = {}, body, signal, accept = 'application/json' } = {}) {
    if (!REQUEST_METHODS.includes(method)) throw new Error(`Unsupported method: ${method}`);
    const publicRequest = (method === 'GET' && PUBLIC_ROUTES.includes(path)) ||
      (method === 'POST' && SESSION_ROUTES.includes(path));
    const target = new URL(publicRequest ? path : dashboardPath(path), origin);
    const headers = { accept };
    if (!publicRequest) {
      if (!token) throw new Error('Set EDUBOT_TOKEN to a dashboard session token.');
      headers.authorization = `Bearer ${token}`;
    }
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) target.searchParams.set(key, String(value));
    }
    if (body !== undefined) headers['content-type'] = 'application/json';
    const timeout = AbortSignal.timeout(30_000);
    const response = await fetch(target, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
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

  return {
    request,
    async json(path, options) {
      return jsonResponse(await request(path, options));
    },
  };
}
