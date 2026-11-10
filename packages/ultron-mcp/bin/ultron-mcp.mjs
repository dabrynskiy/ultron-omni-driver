#!/usr/bin/env node
import {parseArgs} from 'node:util';
import {readFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';
import {createStandaloneServer} from '../standalone.mjs';
import {parseBootstrapConfig} from '../lifecycle.mjs';

const {values} = parseArgs({options: {
  url: {type: 'string'},
  'bootstrap-config': {type: 'string'},
  'timeout-ms': {type: 'string', default: '75000'},
  help: {type: 'boolean', short: 'h'},
}});
if (values.help) {
  process.stdout.write('ultron-mcp [--url http://127.0.0.1:8300 | --bootstrap-config /path/device.json] [--timeout-ms 75000]\nConnect directly to Android, or lazily install/start a prebuilt instrumentation APK. Appium is not required.\n');
} else {
  const timeoutMs = Number(values['timeout-ms']);
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {throw new Error('--timeout-ms must be a positive integer');}
  if (values.url && values['bootstrap-config']) {throw new Error('--url and --bootstrap-config are mutually exclusive');}
  let bootstrap;
  if (values['bootstrap-config']) {
    const configPath = resolve(values['bootstrap-config']);
    bootstrap = parseBootstrapConfig(JSON.parse(await readFile(configPath, 'utf8')), dirname(configPath));
  }
  const server = createStandaloneServer({url: values.url, timeoutMs, bootstrap});
  let stopping;
  const stop = (exitCode = 0) => {
    if (!stopping) {
      process.exitCode = exitCode;
      stopping = server.shutdown().catch(error => {
        process.exitCode = 1;
        process.stderr.write(`[ultron-mcp] ${error.message}\n`);
      }).finally(() => server.close());
    }
    return stopping;
  };
  process.once('SIGINT', () => {void stop(130);});
  process.once('SIGTERM', () => {void stop(143);});
  // The SDK's stdio transport does not close itself on stdin EOF.
  process.stdin.once('end', () => {void stop();});
  await server.connect(new StdioServerTransport());
}
