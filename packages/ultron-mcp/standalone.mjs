import {Server} from '@modelcontextprotocol/sdk/server/index.js';
import {CallToolRequestSchema, ListToolsRequestSchema} from '@modelcontextprotocol/sdk/types.js';
import {z} from 'zod';
import UltronMcpPlugin from './index.mjs';
import {AndroidHttpClient} from './direct-client.mjs';
import {freshMatcherCheck} from './snapshot-match.mjs';
import {DeviceLifecycle} from './lifecycle.mjs';

const json = value => ({content: [{type: 'text', text: JSON.stringify(value)}], structuredContent: value, isError: false});
const readOnly = {readOnlyHint: true, destructiveHint: false, openWorldHint: false};

export function createStandaloneServer({url, timeoutMs, bootstrap, lifecycleOptions} = {}) {
  if (bootstrap && url) {throw new Error('bootstrap and url are mutually exclusive');}
  const client = new AndroidHttpClient(url, timeoutMs);
  const lifecycle = bootstrap ? new DeviceLifecycle(client, bootstrap, lifecycleOptions) : null;
  const tools = new Map();
  const addTool = tool => tools.set(tool.name, tool);
  new UltronMcpPlugin().register({addTool}, {
    getDriver(sessionId) {return client.sessionId && (!sessionId || sessionId === client.sessionId) ? client : null;},
  });
  addTool({
    name: 'ultron_session',
    description: lifecycle
      ? 'Manage the configured Android device. status reports local lifecycle state without starting Android. create prepares the device using configured defaults. Device tools also start it lazily. close stops owned instrumentation and removes its forward; the next working call starts again without reinstalling. External session attach is unavailable in managed mode.'
      : 'Manage the direct Android server session, without Appium. status lists existing sessions; create requires appPackage, and autoLaunch=true also requires appActivity; attach selects an existing session. close stops the instrumentation HTTP server, requiring a manual restart. APK installation/signing and adb forward are manual prerequisites.',
    parameters: z.object({action: z.enum(['status', 'create', 'attach', 'close']),
      sessionId: z.string().min(1).optional(), appPackage: z.string().min(1).optional(),
      appActivity: z.string().min(1).optional(), autoLaunch: z.boolean().optional(),
    }).strict().refine(args => !!lifecycle || args.action !== 'create' || !!args.appPackage, {message: 'create requires appPackage'})
      .refine(args => !!lifecycle || args.action !== 'create' || !args.autoLaunch || !!args.appActivity, {message: 'autoLaunch=true requires appActivity'})
      .refine(args => args.action !== 'attach' || !!args.sessionId, {message: 'attach requires sessionId'}),
    annotations: {readOnlyHint: false, destructiveHint: true, openWorldHint: false},
    async execute(args, context) {
      if (lifecycle) {
        if (args.action === 'status') {return json(lifecycle.status());}
        if (args.action === 'attach') {throw new Error('Managed mode owns its instrumentation; attach is only available with --url');}
        if (args.sessionId && args.sessionId !== client.sessionId) {throw new Error('The requested session is not selected');}
        if (args.action === 'close') {return json(await lifecycle.close());}
        if (client.sessionId) {throw new Error('A session is already selected. Close it before creating another.');}
        const {action: _action, sessionId: _sessionId, ...options} = args;
        return json(await lifecycle.ensureReady(options, {signal: context?.signal}));
      }
      switch (args.action) {
        case 'status': return json({server: await client.request('/status'), sessions: await client.request('/sessions'), selectedSessionId: client.sessionId});
        case 'create': return json(await client.create(args));
        case 'attach': return json(await client.attach(args.sessionId));
        case 'close': {
          if (args.sessionId && args.sessionId !== client.sessionId) {throw new Error('The requested session is not selected');}
          return json(await client.close());
        }
      }
    },
  });
  addTool({
    name: 'ultron_screenshot', description: 'Capture the device screen as a PNG MCP image.',
    parameters: z.object({}).strict(), annotations: readOnly,
    async execute() {
      const data = await client.request(client.path('/screenshot'));
      if (typeof data !== 'string' || !data) {throw new Error('Android server returned an invalid screenshot');}
      return {content: [{type: 'image', mimeType: 'image/png', data}], isError: false};
    },
  });
  for (const [name, path, description] of [
    ['ultron_back', '/back', 'Press Android Back and observe the resulting mixed UI tree.'],
    ['ultron_hide_keyboard', '/appium/device/hide_keyboard', 'Hide the Android keyboard and observe the mixed UI tree.'],
  ]) {
    addTool({name, description, parameters: z.object({}).strict(),
      annotations: {readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false},
      async execute() {
        await client.request(client.path(path), 'POST', {});
        return json({snapshot: await client.executeScript('mobile: ultronSnapshot')});
      },
    });
  }
  addTool({
    name: 'ultron_element',
    description: 'Use the selected espresso/compose backend to find and operate on an element via standard Android HTTP commands. Set driver with ultron_settings. Compose selectors include -android viewtag, text and accessibility id. Returns the element/value and a fresh mixed snapshot after a mutation. A read boolean false is a value, not a passed assertion.',
    parameters: z.object({using: z.string().min(1), value: z.string(),
      action: z.enum(['find', 'click', 'clear', 'typeText', 'replaceText', 'text', 'displayed', 'enabled', 'selected']),
      text: z.string().optional(),
      preflight: z.boolean().optional().describe('Default false. Require a unique locator match in a fresh dump before finding or acting. Unsupported/partial/ambiguous checks block this call.'),
    }).strict().refine(args => !['typeText', 'replaceText'].includes(args.action) || args.text !== undefined,
      {message: 'text is required for text input'}),
    annotations: {readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true},
    async execute(args) {
      let checked;
      if (args.preflight) {
        const {driver: backend} = await client.getSettings();
        checked = await freshMatcherCheck(client, {backend, using: args.using, value: args.value});
        if (checked.preflight.status !== 'unique') {
          return {...json({operation: {status: 'not_executed', error: 'Locator preflight did not establish one target'}, ...checked}), isError: true};
        }
      }
      const element = await client.request(client.path('/element'), 'POST', {using: args.using, value: args.value});
      const id = element?.['element-6066-11e4-a52e-4f735466cecf'] ?? element?.ELEMENT;
      if (typeof id !== 'string') {throw new Error('Android server returned no element ID');}
      const path = `/element/${encodeURIComponent(id)}`;
      if (args.action === 'find') {return json({element});}
      if (['text', 'displayed', 'enabled', 'selected'].includes(args.action)) {
        return json({element, value: await client.request(client.path(`${path}/${args.action}`))});
      }
      const command = args.action === 'replaceText' ? `/appium/element/${encodeURIComponent(id)}/replace_value`
        : `${path}/${args.action === 'typeText' ? 'value' : args.action}`;
      await client.request(client.path(command), 'POST', args.text === undefined ? {} : {text: args.text});
      return json({element, snapshot: await client.executeScript('mobile: ultronSnapshot'), ...(checked ? {preflight: checked.preflight} : {})});
    },
  });

  const server = new Server({name: 'ultron-mcp', version: '0.1.0'}, {
    capabilities: {tools: {}},
    instructions: (lifecycle
      ? 'The configured Android device is prepared automatically before the first device tool: install prebuilt APK once per process, forward a dynamic port, start instrumentation and create a session. ultron_session status does not start Android. close releases owned resources; the next device call starts again. MCP disconnect also cleans up. '
      : 'Connect directly to a manually started Android instrumentation HTTP server through adb forward. Start with ultron_session status/create/attach. Closing MCP leaves Android running; ultron_session close stops instrumentation. ')
      + 'Use ultron_snapshot, act, assert for Views; select driver=compose then use ultron_element for Compose. Verify expected results; a successful click does not prove a test passed. Observe after a lost mutation response before repeating.',
  });
  server.setRequestHandler(ListToolsRequestSchema, async () => ({tools: [...tools.values()].map(tool => ({
    name: tool.name, description: tool.description, inputSchema: z.toJSONSchema(tool.parameters),
    annotations: lifecycle ? {...tool.annotations, readOnlyHint: false, destructiveHint: true} : tool.annotations,
  }))}));
  let pending = Promise.resolve();
  let shuttingDown = false;
  server.shutdown = async () => {
    shuttingDown = true;
    await lifecycle?.dispose();
  };
  server.onclose = () => {server.shutdown().catch(error => process.stderr.write(`[ultron-mcp] ${error.message}\n`));};
  server.setRequestHandler(CallToolRequestSchema, (request, context) => {
    const call = pending.then(async () => {
      try {
        if (shuttingDown) {throw new Error('MCP is shutting down');}
        context.signal.throwIfAborted();
        const tool = tools.get(request.params.name);
        if (!tool) {throw new Error('Unknown Ultron tool');}
        const args = tool.parameters.parse(request.params.arguments ?? {});
        const offline = tool.name === 'ultron_check_matcher' && args.snapshot;
        if (lifecycle && tool.name !== 'ultron_session' && !offline) {
          if (args.sessionId && args.sessionId !== client.sessionId) {throw new Error('The requested session is not selected');}
          await lifecycle.ensureReady({}, {signal: context.signal});
        }
        context.signal.throwIfAborted();
        return await tool.execute(args, context);
      } catch (error) {
        return {isError: true, content: [{type: 'text', text: error instanceof Error ? error.message : String(error)}]};
      }
    });
    pending = call.then(() => {}, () => {});
    return call;
  });
  return server;
}
