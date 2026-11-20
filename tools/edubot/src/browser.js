import { spawn } from 'node:child_process';

export const browserWorkflows = {
  dashboard: '/app/',
  backoffice: '/backoffice/',
  'dashboard-session': '/app/?cli=1',
  'backoffice-session': '/backoffice/?cli=1',
  account: '/app/',
  'google-connections': '/app/#settings',
  'channel-connections': '/app/#settings',
  'time-clock': '/app/#my-hours',
  'password-vault': '/backoffice/#vault',
};

export function browserLink(origin, workflow) {
  if (!Object.hasOwn(browserWorkflows, workflow)) throw new Error('Unknown browser workflow.');
  return new URL(browserWorkflows[workflow], origin).href;
}

export function openBrowser(url) {
  const command = process.platform === 'darwin' ? 'open' : 'xdg-open';
  if (process.platform === 'win32') {
    // Pass the URL as an argument to the shell's default URL handler without cmd interpolation.
    return launch('rundll32.exe', ['url.dll,FileProtocolHandler', url]);
  }
  return launch(command, [url]);
}

function launch(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { detached: true, stdio: 'ignore' });
    child.on('error', () => reject(new Error('Could not open the browser. Use the printed URL.')));
    child.on('spawn', () => {
      child.unref();
      resolve();
    });
  });
}
