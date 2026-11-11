#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {parseArgs} from 'node:util';

import {checkSnapshotMatcher} from '../snapshot-match.mjs';

const {values} = parseArgs({
  options: {snapshot: {type: 'string'}, query: {type: 'string'}, help: {type: 'boolean', short: 'h'}},
});
if (values.help) {
  process.stdout.write(
    'node bin/check-matcher.mjs --snapshot dump.json --query query.json\nQuery: {backend:"espresso",matcher:{...}} or {backend:"compose",using:"-android viewtag",value:"save"}.\nExit: 0 unique, 1 not_found/ambiguous, 2 unsupported/indeterminate.\n',
  );
} else {
  try {
    if (!values.snapshot || !values.query) {
      throw new Error('--snapshot and --query are required');
    }
    const [snapshot, query] = await Promise.all(
      [values.snapshot, values.query].map(async (path) => JSON.parse(await readFile(path, 'utf8'))),
    );
    const report = checkSnapshotMatcher({...query, snapshot});
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
    process.exitCode = report.status === 'unique' ? 0 : ['not_found', 'ambiguous'].includes(report.status) ? 1 : 2;
  } catch (error) {
    process.stderr.write(error.message + '\n');
    process.exitCode = 2;
  }
}
