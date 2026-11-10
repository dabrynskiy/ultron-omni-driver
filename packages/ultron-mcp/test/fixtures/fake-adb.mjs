#!/usr/bin/env node
// Test-only subprocess fixture. Real CLI tests exercise argv, stdio and child cleanup.
import {readFileSync, appendFileSync, writeFileSync, existsSync, unlinkSync} from 'node:fs';
import {dirname, join} from 'node:path';

const directory = dirname(process.argv[1]);
const config = JSON.parse(readFileSync(join(directory, 'fixture.json'), 'utf8'));
const args = process.argv.slice(2);
appendFileSync(join(directory, 'adb.jsonl'), JSON.stringify(args) + '\n');
if (args[0] === '-s') {
  if (args[1] !== 'test-device') {throw new Error('Incorrect device selection');}
  args.splice(0, 2);
}
const command = args.join(' ');
const pidPath = join(directory, 'instrumentation.pid');
if (command === 'devices') {
  process.stdout.write(config.devices ?? 'List of devices attached\ntest-device\tdevice\n');
} else if (command === 'shell pm path test.app') {
  process.stdout.write(config.missingApp ? '' : 'package:/data/app/test.app/base.apk\n');
} else if (command === 'shell dumpsys activity processes') {
  process.stdout.write('ACTIVITY MANAGER RUNNING PROCESSES\n' + (config.busy ? 'ActiveInstrumentation{foreign}\n' : ''));
} else if (args[0] === 'install') {
  if (config.installFailure) {process.stderr.write('INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match\n'); process.exit(1);}
  if (args[1] !== '-r' || !existsSync(args[2])) {throw new Error('Invalid install arguments');}
  process.stdout.write('Success\n');
} else if (command === 'shell pm list instrumentation') {
  process.stdout.write(`instrumentation:io.appium.espressoserver.test/androidx.test.runner.AndroidJUnitRunner (target=${config.wrongTarget ? 'wrong.app' : 'test.app'})\n`);
} else if (args[0] === 'forward') {
  if (args[1] === 'tcp:0' && args[2] === 'tcp:6791') {process.stdout.write(String(config.port));}
  else if (args[1] === '--remove') {
    if (config.removeFailure) {process.stderr.write('device offline\n'); process.exit(1);}
  } else {throw new Error('Invalid forward arguments');}
} else if (command.startsWith('shell am instrument -w ')) {
  if (config.crash) {process.stdout.write('INSTRUMENTATION_FAILED: Process crashed\n'); process.exit(1);}
  writeFileSync(pidPath, String(process.pid));
  process.stdout.write('Instrumentation fixture running\n');
  const timer = setInterval(() => {}, 1000);
  process.on('SIGTERM', () => {
    clearInterval(timer);
    if (existsSync(pidPath)) {unlinkSync(pidPath);}
  });
} else if (command === 'shell am force-stop test.app') {
  if (config.stopFailure) {process.stderr.write('device offline\n'); process.exit(1);}
  if (existsSync(pidPath)) {
    process.kill(Number(readFileSync(pidPath, 'utf8')), 'SIGTERM');
  }
} else {throw new Error(`Unexpected adb command: ${command}`);}
