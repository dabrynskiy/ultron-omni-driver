import {Server} from '@modelcontextprotocol/sdk/server/index.js';
import {CallToolRequestSchema, ListToolsRequestSchema} from '@modelcontextprotocol/sdk/types.js';
import {z} from 'zod';

import {AndroidHttpClient} from './direct-client.mjs';
import UltronMcpPlugin from './index.mjs';
import {DeviceLifecycle} from './lifecycle.mjs';

const json = (value) => ({
  content: [{type: 'text', text: JSON.stringify(value)}],
  structuredContent: value,
  isError: false,
});
const readOnly = {readOnlyHint: true, destructiveHint: false, openWorldHint: false};

export function createStandaloneServer({url, timeoutMs, bootstrap, lifecycleOptions, recordingsDir} = {}) {
  if (bootstrap && url) {
    throw new Error('bootstrap and url are mutually exclusive');
  }
  const client = new AndroidHttpClient(url, timeoutMs);
  const lifecycle = bootstrap ? new DeviceLifecycle(client, bootstrap, lifecycleOptions) : null;
  const tools = new Map();
  const addTool = (tool) => tools.set(tool.name, tool);
  const plugin = new UltronMcpPlugin({recordingsDir});
  plugin.register(
    {addTool},
    {
      getDriver(sessionId) {
        return client.sessionId && (!sessionId || sessionId === client.sessionId) ? client : null;
      },
    },
  );
  addTool({
    name: 'ultron_session',
    description: lifecycle
      ? 'Manage the configured Android device. status reports local lifecycle state without starting Android. create prepares the device using configured defaults. Device tools also start it lazily. close stops owned instrumentation and removes its forward; the next working call starts again without reinstalling. External session attach is unavailable in managed mode.'
      : 'Manage the direct Android server session, without Appium. status lists existing sessions; create requires appPackage, and autoLaunch=true also requires appActivity; attach selects an existing session. close stops the instrumentation HTTP server, requiring a manual restart. APK installation/signing and adb forward are manual prerequisites.',
    parameters: z
      .object({
        action: z.enum(['status', 'create', 'attach', 'close']),
        sessionId: z.string().min(1).optional(),
        appPackage: z.string().min(1).optional(),
        appActivity: z.string().min(1).optional(),
        autoLaunch: z.boolean().optional(),
      })
      .strict()
      .refine((args) => !!lifecycle || args.action !== 'create' || !!args.appPackage, {
        message: 'create requires appPackage',
      })
      .refine((args) => !!lifecycle || args.action !== 'create' || !args.autoLaunch || !!args.appActivity, {
        message: 'autoLaunch=true requires appActivity',
      })
      .refine((args) => args.action !== 'attach' || !!args.sessionId, {message: 'attach requires sessionId'}),
    annotations: {readOnlyHint: false, destructiveHint: true, openWorldHint: false},
    async execute(args, context) {
      if (args.action !== 'status' && plugin.recorder.active) {
        throw new Error('Finish or discard the recording before changing the session');
      }
      if (lifecycle) {
        if (args.action === 'status') {
          return json(lifecycle.status());
        }
        if (args.action === 'attach') {
          throw new Error('Managed mode owns its instrumentation; attach is only available with --url');
        }
        if (args.sessionId && args.sessionId !== client.sessionId) {
          throw new Error('The requested session is not selected');
        }
        if (args.action === 'close') {
          return json(await lifecycle.close());
        }
        if (client.sessionId) {
          throw new Error('A session is already selected. Close it before creating another.');
        }
        const {action: _action, sessionId: _sessionId, ...options} = args;
        return json(await lifecycle.ensureReady(options, {signal: context?.signal}));
      }
      switch (args.action) {
        case 'status':
          return json({
            server: await client.request('/status'),
            sessions: await client.request('/sessions'),
            selectedSessionId: client.sessionId,
          });
        case 'create':
          return json(await client.create(args));
        case 'attach':
          return json(await client.attach(args.sessionId));
        case 'close': {
          if (args.sessionId && args.sessionId !== client.sessionId) {
            throw new Error('The requested session is not selected');
          }
          return json(await client.close());
        }
      }
    },
  });
  addTool({
    name: 'ultron_screenshot',
    description: 'Capture the device screen as a PNG MCP image.',
    parameters: z.object({}).strict(),
    annotations: readOnly,
    async execute() {
      const data = await client.request(client.path('/screenshot'));
      if (typeof data !== 'string' || !data) {
        throw new Error('Android server returned an invalid screenshot');
      }
      return {content: [{type: 'image', mimeType: 'image/png', data}], isError: false};
    },
  });

  const server = new Server(
    {name: 'ultron-mcp', version: '0.1.0'},
    {
      capabilities: {tools: {}},
      instructions:
        (lifecycle
          ? 'The configured Android device is prepared automatically before the first device tool: install prebuilt APK once per process, forward a dynamic port, start instrumentation and create a session. ultron_session status does not start Android. close releases owned resources; the next device call starts again. MCP disconnect also cleans up. '
          : 'Connect directly to a manually started Android instrumentation HTTP server through adb forward. Start with ultron_session status/create/attach. Closing MCP leaves Android running; ultron_session close stops instrumentation. ') +
        'Use ultron_snapshot, act, assert for Views and explicit ultron_compose_act/assert for Compose. Start ultron_recording_start before a test case to save actions/assertions automatically; finish explicitly after checking expected results. Recordings store literal input data. A successful click does not prove a test passed. Observe after a lost mutation response before repeating.',
    },
  );
  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [...tools.values()].map((tool) => ({
      name: tool.name,
      description: tool.description,
      inputSchema: z.toJSONSchema(tool.parameters),
      annotations: lifecycle ? {...tool.annotations, readOnlyHint: false, destructiveHint: true} : tool.annotations,
    })),
  }));
  let pending = Promise.resolve();
  let shuttingDown = false;
  server.shutdown = async () => {
    shuttingDown = true;
    await lifecycle?.dispose();
    await plugin.recorder.interrupt();
  };
  server.onclose = () => {
    server.shutdown().catch((error) => process.stderr.write(`[ultron-mcp] ${error.message}\n`));
  };
  server.setRequestHandler(CallToolRequestSchema, (request, context) => {
    const call = pending.then(async () => {
      try {
        if (shuttingDown) {
          throw new Error('MCP is shutting down');
        }
        context.signal.throwIfAborted();
        const tool = tools.get(request.params.name);
        if (!tool) {
          throw new Error('Unknown Ultron tool');
        }
        const args = tool.parameters.parse(request.params.arguments ?? {});
        const offline =
          (tool.name === 'ultron_check_matcher' && args.snapshot) ||
          ['ultron_recording_status', 'ultron_recording_finish', 'ultron_recording_discard'].includes(tool.name);
        if (lifecycle && tool.name !== 'ultron_session' && !offline) {
          if (args.sessionId && args.sessionId !== client.sessionId) {
            throw new Error('The requested session is not selected');
          }
          await lifecycle.ensureReady({}, {signal: context.signal});
        }
        context.signal.throwIfAborted();
        const output = await tool.execute(args, context);
        if (['ultron_screenshot', 'ultron_session'].includes(tool.name)) {
          await plugin.recorder.observe(tool.name, output.isError ? 'failure' : 'success');
        }
        return output;
      } catch (error) {
        return {isError: true, content: [{type: 'text', text: error instanceof Error ? error.message : String(error)}]};
      }
    });
    pending = call.then(
      () => {},
      () => {},
    );
    return call;
  });
  return server;
}
