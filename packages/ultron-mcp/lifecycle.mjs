import {execFile, spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {constants} from 'node:fs';
import {access, mkdir, open, unlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {promisify} from 'node:util';

import {z} from 'zod';

const exec = promisify(execFile);
const packageName = z.string().regex(/^[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+$/);
const className = z.string().regex(/^[A-Za-z_.$][\w.$]*$/);
const positiveMs = z.number().int().positive().max(2147483647);
export const bootstrapConfigSchema = z
  .object({
    driverApk: z.string().min(1),
    deviceSerial: z.string().min(1).optional(),
    adbPath: z.string().min(1).optional(),
    appPackage: packageName,
    appActivity: className.optional(),
    autoLaunch: z.boolean().default(false),
    instrumentationComponent: z
      .string()
      .regex(/^[A-Za-z_][\w.]*\/[A-Za-z_.$][\w.$]*$/)
      .default('io.appium.espressoserver.test/androidx.test.runner.AndroidJUnitRunner'),
    testClass: className.default('io.appium.espressoserver.EspressoServerRunnerTest'),
    devicePort: z.number().int().min(1).max(65535).default(6791),
    startupTimeoutMs: positiveMs.default(60000),
    installTimeoutMs: positiveMs.default(120000),
    adbTimeoutMs: positiveMs.default(15000),
    expectedServerVersion: z.string().min(1).optional(),
  })
  .strict()
  .refine((value) => !value.autoLaunch || !!value.appActivity, {
    message: 'autoLaunch=true requires appActivity',
    path: ['appActivity'],
  });

export function parseBootstrapConfig(raw, baseDirectory = process.cwd()) {
  const config = bootstrapConfigSchema.parse(raw);
  config.driverApk = resolve(baseDirectory, config.driverApk);
  // An explicit executable name can still be resolved through PATH.
  if (config.adbPath?.includes('/') || config.adbPath?.includes('\\')) {
    config.adbPath = resolve(baseDirectory, config.adbPath);
  }
  return config;
}

/** Owns one device, one forward and one instrumentation process. Never retries UI commands. */
export class DeviceLifecycle {
  constructor(
    client,
    rawConfig,
    {
      logger = (message) => process.stderr.write(`[ultron-mcp] ${message}\n`),
      lockDirectory = join(tmpdir(), 'ultron-mcp-devices'),
    } = {},
  ) {
    this.client = client;
    this.config = parseBootstrapConfig(rawConfig);
    const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    this.adbPath =
      this.config.adbPath ||
      (sdk ? join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb') : 'adb');
    this.logger = logger;
    this.lockDirectory = lockDirectory;
    this.phase = 'idle';
    this.serial = this.config.deviceSerial ?? null;
    this.installed = false;
    this.forwardPort = null;
    this.instrumentation = null;
    this.lock = null;
    this.preparing = null;
    this.disposed = false;
    this.lastError = null;
    this.logTail = '';
  }

  status() {
    if (this.phase === 'ready' && this.instrumentation?.exited) {
      this.phase = 'failed';
      this.lastError = this.processError().message;
    }
    return {
      managed: true,
      phase: this.phase,
      deviceSerial: this.serial,
      installed: this.installed,
      url: this.forwardPort ? this.client.url : null,
      selectedSessionId: this.client.sessionId,
      ...(this.lastError ? {error: this.lastError} : {}),
    };
  }

  async adb(args, {signal, timeout = this.config.adbTimeoutMs, device = true} = {}) {
    const command = [...(device ? ['-s', this.serial] : []), ...args];
    try {
      const {stdout} = await exec(this.adbPath, command, {
        signal,
        timeout,
        maxBuffer: 8 * 1024 * 1024,
        windowsHide: true,
      });
      return stdout.trim();
    } catch (error) {
      // Keep APK/signature/unauthorized diagnostics, but never put subprocess output on MCP stdout.
      throw new Error(`ADB ${args.slice(0, 3).join(' ')} failed: ${error.stderr?.trim() || error.message}`, {
        cause: error,
      });
    }
  }

  async acquireLock() {
    await mkdir(this.lockDirectory, {recursive: true, mode: 0o700});
    const key = createHash('sha256')
      .update(`${process.env.ADB_SERVER_SOCKET ?? ''}\0${this.serial}`)
      .digest('hex');
    const path = join(this.lockDirectory, `${key}.lock`);
    let handle;
    try {
      handle = await open(path, 'wx', 0o600);
    } catch (error) {
      if (error.code !== 'EEXIST') {
        throw error;
      }
      throw new Error(
        `Device ${this.serial} is locked by another Ultron MCP process. Lock: ${path}. If that process crashed, verify it has stopped before removing this lock.`,
        {cause: error},
      );
    }
    this.lock = {handle, path};
    await handle.writeFile(JSON.stringify({pid: process.pid, deviceSerial: this.serial}));
  }

  async ensureReady(overrides = {}, {signal: requestSignal} = {}) {
    if (this.disposed) {
      throw new Error('Managed MCP is shutting down');
    }
    const status = this.status();
    if (status.phase === 'ready') {
      return {sessionId: this.client.sessionId};
    }
    if (status.phase === 'failed') {
      throw new Error(`${this.lastError} Call ultron_session close before starting again.`);
    }
    if (this.preparing) {
      return this.preparing;
    }
    const options = {...this.config, ...overrides};
    bootstrapConfigSchema.parse(options);
    if (options.appPackage !== this.config.appPackage) {
      throw new Error('Managed mode cannot change appPackage: the APK is built for the configured target');
    }
    this.controller = new AbortController();
    this.phase = 'preparing';
    const signal = requestSignal ? AbortSignal.any([requestSignal, this.controller.signal]) : this.controller.signal;
    this.preparing = this.prepare(options, signal);
    try {
      return await this.preparing;
    } finally {
      this.preparing = null;
    }
  }

  async prepare(options, signal) {
    try {
      await access(this.config.driverApk, constants.R_OK);
      const devices = (await this.adb(['devices'], {device: false, signal}))
        .split(/\r?\n/)
        .map((line) => line.match(/^(\S+)\s+(device|offline|unauthorized|no permissions)\b/))
        .filter(Boolean);
      if (!this.serial) {
        if (devices.length !== 1 || devices[0][2] !== 'device') {
          throw new Error('Configure deviceSerial, or connect exactly one authorized Android device');
        }
        this.serial = devices[0][1];
      }
      if (!devices.some((device) => device[1] === this.serial && device[2] === 'device')) {
        throw new Error(`Device ${this.serial} is unavailable, offline or unauthorized`);
      }
      await this.acquireLock();
      const app = await this.adb(['shell', 'pm', 'path', options.appPackage], {signal});
      if (!/^package:/m.test(app)) {
        throw new Error(`Target app ${options.appPackage} is not installed`);
      }
      const processes = await this.adb(['shell', 'dumpsys', 'activity', 'processes'], {signal});
      if (!processes.includes('ACTIVITY MANAGER RUNNING PROCESSES')) {
        throw new Error('Cannot inspect active instrumentation; refusing to replace an unknown device session');
      }
      if (/ActiveInstrumentation\{/.test(processes)) {
        throw new Error('The device already has active instrumentation; close its owning session first');
      }
      if (!this.installed) {
        this.logger(`Installing driver APK on ${this.serial}`);
        const output = await this.adb(['install', '-r', this.config.driverApk], {
          signal,
          timeout: this.config.installTimeoutMs,
        });
        if (!/\bSuccess\b/.test(output)) {
          throw new Error(`Driver installation failed: ${output}`);
        }
        this.installed = true;
      }
      const registered = await this.adb(['shell', 'pm', 'list', 'instrumentation'], {signal});
      if (
        !registered
          .split(/\r?\n/)
          .some(
            (line) =>
              line.trim() === `instrumentation:${options.instrumentationComponent} (target=${options.appPackage})`,
          )
      ) {
        throw new Error(`APK does not register ${options.instrumentationComponent} for target ${options.appPackage}`);
      }
      // Complete this bounded allocation even if cancelled, so cleanup learns which port it owns.
      signal.throwIfAborted();
      const port = await this.adb(['forward', 'tcp:0', `tcp:${options.devicePort}`]);
      if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
        throw new Error(`ADB returned an invalid forward port: ${port}`);
      }
      this.forwardPort = Number(port);
      this.client.url = `http://127.0.0.1:${port}`;
      signal.throwIfAborted();
      this.logTail = '';
      const child = spawn(
        this.adbPath,
        [
          '-s',
          this.serial,
          'shell',
          'am',
          'instrument',
          '-w',
          '-e',
          'disableAnalytics',
          'true',
          '-e',
          'class',
          options.testClass,
          options.instrumentationComponent,
        ],
        {stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true},
      );
      const state = (this.instrumentation = {child, exited: false, error: null, stopUnconfirmed: false});
      state.finished = new Promise((resolve) => {
        child.once('error', (error) => {
          state.error = error;
          state.exited = true;
          resolve();
        });
        child.once('exit', (code, signal) => {
          state.error ??= new Error(`instrumentation exited (code=${code}, signal=${signal})`);
          state.exited = true;
          resolve();
        });
      });
      for (const stream of [child.stdout, child.stderr]) {
        stream.on('data', (data) => {
          this.logTail = (this.logTail + data.toString()).slice(-8000);
        });
      }
      this.logger(`Waiting for instrumentation on ${this.client.url}`);
      await this.waitUntilReady(signal);
      signal.throwIfAborted();
      if (state.exited) {
        throw this.processError();
      }
      // Never attach to or overwrite an existing Android session automatically.
      const session = await this.client.create(options, {signal});
      signal.throwIfAborted();
      if (state.exited) {
        throw this.processError();
      }
      this.phase = 'ready';
      this.lastError = null;
      this.logger(`Device ${this.serial} ready (session ${session.sessionId})`);
      return session;
    } catch (error) {
      this.lastError = error.message;
      this.phase = 'failed';
      const errors = await this.cleanup();
      if (errors.length) {
        this.lastError += `; cleanup: ${errors.join('; ')}`;
      }
      throw new Error(this.lastError, {cause: error});
    }
  }

  processError() {
    return new Error(
      `Android ${this.instrumentation?.error?.message ?? 'instrumentation stopped'}. ${this.logTail}`.trim(),
    );
  }

  async waitUntilReady(signal) {
    const deadline = Date.now() + this.config.startupTimeoutMs;
    let lastError;
    while (Date.now() < deadline) {
      signal.throwIfAborted();
      if (this.instrumentation.exited) {
        throw this.processError();
      }
      let status;
      try {
        status = await this.client.request('/status', 'GET', undefined, false, {
          signal,
          timeoutMs: Math.min(1000, Math.max(1, deadline - Date.now())),
        });
      } catch (error) {
        lastError = error.message;
      }
      if (status?.ready === true) {
        if (status.build?.packageName !== this.config.appPackage) {
          throw new Error('Android server target package does not match bootstrap configuration');
        }
        if (this.config.expectedServerVersion && status.build?.version !== this.config.expectedServerVersion) {
          throw new Error(
            `Android server version ${status.build?.version} does not match ${this.config.expectedServerVersion}`,
          );
        }
        return;
      }
      await delay(Math.min(100, Math.max(1, deadline - Date.now())), undefined, {signal});
    }
    throw new Error(
      `Instrumentation did not become ready within ${this.config.startupTimeoutMs}ms. ${lastError ?? ''} ${this.logTail}`.trim(),
    );
  }

  async cleanup() {
    const errors = [];
    const attempt = async (run) => {
      try {
        await run();
      } catch (error) {
        errors.push(error.message);
      }
    };
    let deleteError;
    if (this.client.sessionId) {
      try {
        await this.client.request(this.client.path(), 'DELETE', undefined, false, {timeoutMs: 2000});
      } catch (error) {
        deleteError = error;
      }
      this.client.sessionId = null;
    }
    if (this.instrumentation) {
      const state = this.instrumentation;
      let stopped = state.exited && !state.stopUnconfirmed;
      if (!state.exited || state.stopUnconfirmed) {
        // Killing the host adb client alone does not reliably stop Android instrumentation.
        state.stopUnconfirmed = true;
        await attempt(async () => {
          await this.adb(['shell', 'am', 'force-stop', this.config.appPackage], {timeout: 5000});
          state.stopUnconfirmed = false;
          stopped = true;
        });
        state.child.kill('SIGTERM');
        await Promise.race([state.finished, delay(1000, undefined, {ref: false})]);
        if (!state.exited) {
          state.child.kill('SIGKILL');
          await state.finished;
        }
      }
      // Keep ownership if Android could not be stopped, even after the adb client exits.
      if (stopped) {
        this.instrumentation = null;
      }
    }
    if (deleteError && !this.instrumentation) {
      this.logger(`Session DELETE failed; instrumentation stopped: ${deleteError.message}`);
    }
    if (this.forwardPort) {
      await attempt(async () => {
        await this.adb(['forward', '--remove', `tcp:${this.forwardPort}`], {timeout: 5000});
        this.forwardPort = null;
      });
    }
    if (this.lock && !errors.length) {
      await attempt(async () => {
        await this.lock.handle.close();
        await unlink(this.lock.path);
        this.lock = null;
      });
    }
    return errors;
  }

  async close() {
    this.controller?.abort(new Error('Managed session closed'));
    if (this.preparing) {
      await this.preparing.catch(() => {});
    }
    const errors = await this.cleanup();
    this.phase = errors.length ? 'failed' : 'idle';
    this.lastError = errors.length ? errors.join('; ') : null;
    if (errors.length) {
      throw new Error(`Managed cleanup failed: ${this.lastError}`);
    }
    return {closed: true, restartInstrumentationRequired: false, installed: this.installed};
  }

  async dispose() {
    this.disposed = true;
    this.disposing ??= this.close();
    return this.disposing;
  }
}
