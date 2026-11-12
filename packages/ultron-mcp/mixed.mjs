import {z} from 'zod';

export const mixedCommands = ['ultronAutomatorAct', 'ultronAutomatorAssert', 'ultronWebAct', 'ultronWebAssert'];

/** Raw driver parameters shared by single tools and version 2 scenario validation. */
export function mixedParameters(matcher, timeout, session = {}) {
  return Object.fromEntries(
    mixedCommands.map((command) => {
      const automator = command.startsWith('ultronAutomator');
      const assertion = command.endsWith('Assert');
      const fields = automator
        ? {
            strategy: z.enum([
              'clazz',
              'res',
              'text',
              'textContains',
              'textStartsWith',
              'textEndsWith',
              'desc',
              'descContains',
              'descStartsWith',
              'descEndsWith',
              'pkg',
            ]),
            locator: z.string(),
            packageName: z.string().optional(),
            index: z.number().int().min(0).max(2147483647).optional(),
            ...(assertion
              ? {
                  assertion: z
                    .enum([
                      'exists',
                      'notExists',
                      'textEquals',
                      'textContains',
                      'enabled',
                      'checked',
                      'selected',
                      'displayed',
                    ])
                    .optional(),
                  expected: z.boolean().optional(),
                }
              : {
                  action: z.enum([
                    'click',
                    'longClick',
                    'clearText',
                    'replaceText',
                    'text',
                    'enabled',
                    'checked',
                    'selected',
                    'displayed',
                  ]),
                }),
          }
        : {
            webViewMatcher: matcher,
            locator: z
              .object({
                using: z.enum([
                  'ID',
                  'CSS_SELECTOR',
                  'XPATH',
                  'CLASS_NAME',
                  'NAME',
                  'TAG_NAME',
                  'LINK_TEXT',
                  'PARTIAL_LINK_TEXT',
                ]),
                value: z.string(),
              })
              .strict(),
            frames: z
              .array(
                z.union([
                  z.object({index: z.number().int().min(0).max(2147483647)}).strict(),
                  z.object({idOrName: z.string().trim().min(1)}).strict(),
                ]),
              )
              .max(16)
              .optional(),
            ...(assertion
              ? {
                  assertion: z.enum(['exists', 'notExists', 'textEquals', 'textContains']).optional(),
                }
              : {
                  action: z.enum(['click', 'clearText', 'typeText', 'text', 'scrollIntoView']),
                }),
          };
      const schema = z
        .object({...session, ...fields, text: z.string().optional(), timeoutMs: timeout})
        .strict()
        .superRefine((args, ctx) => {
          const kind = assertion ? (args.assertion ?? 'exists') : args.action;
          const needsText = assertion
            ? ['textEquals', 'textContains'].includes(kind)
            : kind === (automator ? 'replaceText' : 'typeText');
          if (needsText !== (args.text !== undefined)) {
            ctx.addIssue({code: 'custom', path: ['text'], message: 'text must be supplied only for text operations'});
          }
          if (args.expected !== undefined && !['enabled', 'checked', 'selected', 'displayed'].includes(kind)) {
            ctx.addIssue({
              code: 'custom',
              path: ['expected'],
              message: 'expected is only valid for boolean assertions',
            });
          }
          if (!automator && args.webViewMatcher.scope !== undefined) {
            ctx.addIssue({
              code: 'custom',
              path: ['webViewMatcher', 'scope'],
              message: 'WebView matchers do not support scope',
            });
          }
        });
      return [command, schema];
    }),
  );
}
