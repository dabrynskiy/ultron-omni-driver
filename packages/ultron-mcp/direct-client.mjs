const routes = {
  'mobile: ultronSnapshot': ['POST', 'ultron_snapshot'],
  'mobile: ultronComposeAct': ['POST', 'ultron_compose_act'],
  'mobile: ultronComposeAssert': ['POST', 'ultron_compose_assert'],
  'mobile: ultronAct': ['POST', 'ultron_act'],
  'mobile: ultronAssert': ['POST', 'ultron_assert'],
  'mobile: ultronRecycler': ['POST', 'ultron_recycler'],
  'mobile: ultronBatch': ['POST', 'ultron_batch'],
  'mobile: ultronBack': ['POST', 'ultron_back'],
  'mobile: ultronHideKeyboard': ['POST', 'ultron_hide_keyboard'],
  'mobile: ultronElement': ['POST', 'ultron_element'],
  'mobile: ultronLastResult': ['GET', 'ultron_last_result'],
};

/** Direct Android NanoHTTPD client. No Appium server, WebDriver client or automatic retries. */
export class AndroidHttpClient {
  sessionId = null;

  constructor(url = 'http://127.0.0.1:8300', timeoutMs = 75000) {
    const parsed = new URL(url);
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      throw new Error('Android server URL must use http or https');
    }
    this.url = parsed.toString().replace(/\/$/, '');
    this.timeoutMs = timeoutMs;
  }

  async request(path, method = 'GET', body, envelope = false, {timeoutMs = this.timeoutMs, signal} = {}) {
    const response = await fetch(this.url + path, {
      method,
      headers: {'Content-Type': 'application/json; charset=utf-8'},
      ...(body !== undefined ? {body: JSON.stringify(body)} : {}),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
      redirect: 'error',
    });
    const payload = await response.json();
    if (!response.ok || payload?.value?.error) {
      throw new Error(payload?.value?.message ?? `Android HTTP request failed: ${response.status}`);
    }
    if (!Object.hasOwn(payload, 'value')) {
      throw new Error('Unexpected Android HTTP response: missing value');
    }
    return envelope ? payload : payload.value;
  }

  path(suffix = '') {
    if (!this.sessionId) {
      throw new Error('Create or attach a session with ultron_session first');
    }
    return `/session/${encodeURIComponent(this.sessionId)}${suffix}`;
  }

  async create({appPackage, appActivity, autoLaunch = false}, requestOptions = {}) {
    if (this.sessionId) {
      throw new Error('A session is already selected. Close it before creating another.');
    }
    // Do not overwrite a device session owned by another client.
    const existing = await this.request('/sessions', 'GET', undefined, false, requestOptions);
    if (!Array.isArray(existing) || existing.length) {
      throw new Error('The Android server already has a session. Use ultron_session action=attach with its ID.');
    }
    const caps = {appPackage, autoLaunch, ...(appActivity ? {appActivity} : {})};
    const response = await this.request(
      '/session',
      'POST',
      {
        capabilities: {alwaysMatch: caps, firstMatch: [{}]},
      },
      true,
      requestOptions,
    );
    const id = response.sessionId ?? response.value?.sessionId;
    if (typeof id !== 'string' || !id) {
      throw new Error('Android server did not return a session ID');
    }
    this.sessionId = id;
    return {sessionId: id};
  }

  async attach(sessionId) {
    const sessions = await this.request('/sessions');
    if (!Array.isArray(sessions) || !sessions.some((session) => session.id === sessionId)) {
      throw new Error('Session not found on this Android server');
    }
    await this.request(`/session/${encodeURIComponent(sessionId)}`);
    this.sessionId = sessionId;
    return {sessionId};
  }

  async close() {
    await this.request(this.path(), 'DELETE');
    this.sessionId = null;
    return {closed: true, restartInstrumentationRequired: true};
  }

  getSettings() {
    return this.request(this.path('/appium/settings'));
  }
  updateSettings(settings) {
    return this.request(this.path('/appium/settings'), 'POST', {settings});
  }

  executeScript(script, args = [{}]) {
    const route = routes[script];
    if (!route) {
      throw new Error(`Unsupported direct command: ${script}`);
    }
    const [method, command] = route;
    return this.request(
      this.path(`/appium/execute_mobile/${command}`),
      method,
      method === 'POST' ? args[0] : undefined,
    );
  }
}
