import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

// Explicitly opt in with the forwarded URL of a manually started ApiDemos instrumentation server.
const url = process.env.ULTRON_ANDROID_URL;
if (!url) {
  throw new Error('Set ULTRON_ANDROID_URL to the forwarded ApiDemos Android server URL');
}
const client = new Client({name: 'direct-android-smoke', version: '1'});
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [fileURLToPath(new URL('../bin/ultron-mcp.mjs', import.meta.url)), '--url', url],
  stderr: 'pipe',
});
const call = async (name, args = {}) => {
  const response = await client.callTool({name, arguments: args});
  assert.notEqual(response.isError, true, JSON.stringify(response));
  return response;
};
const matcher = (text) => ({class: 'ViewMatchers', name: 'withText', args: [text]});
const condition = {class: 'LlmViewMatchers', name: 'propertyEquals', args: ['displayed', true]};
let owned = false;
try {
  await client.connect(transport);
  assert.equal((await client.listTools()).tools.length, 19);
  const status = await call('ultron_session', {action: 'status'});
  assert.deepEqual(status.structuredContent.sessions, [], 'This smoke test needs an unused Android server');
  await call('ultron_session', {
    action: 'create',
    appPackage: 'io.appium.android.apis',
    appActivity: '.ApiDemos',
    autoLaunch: true,
  });
  owned = true;
  const snapshot = await call('ultron_snapshot');
  assert.equal(snapshot.structuredContent.status, 'captured');
  assert.ok(snapshot.structuredContent.nodeCount > 0);
  const checked = await call('ultron_check_matcher', {backend: 'espresso', matcher: matcher('App')});
  assert.equal(checked.structuredContent.status, 'unique');
  const blocked = await client.callTool({
    name: 'ultron_act',
    arguments: {
      preflight: true,
      action: 'click',
      matcher: {
        class: 'LlmViewMatchers',
        name: 'propertyEquals',
        args: ['enabled', true],
      },
    },
  });
  assert.equal(blocked.isError, true);
  assert.equal(blocked.structuredContent.operation.status, 'not_executed');
  assert.equal(blocked.structuredContent.preflight.status, 'ambiguous');
  await call('ultron_assert', {matcher: matcher('App'), condition, timeoutMs: 3000});
  await call('ultron_act', {matcher: matcher('App'), action: 'click', timeoutMs: 3000, preflight: true});
  await call('ultron_assert', {matcher: matcher('Activity'), condition, timeoutMs: 3000});
  assert.equal((await call('ultron_screenshot')).content[0].type, 'image');
  const element = await call('ultron_element', {using: 'text', value: 'Activity', action: 'text'});
  assert.equal(element.structuredContent.value, 'Activity');
  await call('ultron_back');
  await call('ultron_assert', {matcher: matcher('App'), condition, timeoutMs: 3000});
  process.stdout.write(
    `Direct Android MCP passed: session, mixed snapshot (${snapshot.structuredContent.nodeCount} nodes), click, assertions, screenshot, element read and Back. No Appium server used.\n`,
  );
} finally {
  if (owned) {
    await call('ultron_session', {action: 'close'});
  }
  await client.close();
  await transport.close();
}
