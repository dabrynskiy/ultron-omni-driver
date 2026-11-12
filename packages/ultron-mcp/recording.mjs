import {randomUUID} from 'node:crypto';
import {appendFile, mkdir, rename, writeFile} from 'node:fs/promises';
import {resolve, join} from 'node:path';

import {mixedCommands} from './mixed.mjs';

/** One explicitly delimited recording, pinned to one driver and Android session. */
export class ScenarioRecorder {
  #active;
  #last = null;

  constructor(directory = process.env.ULTRON_RECORDINGS_DIR ?? resolve('.ultron-recordings')) {
    this.directory = resolve(directory);
  }

  get driver() {
    return this.#active?.driver;
  }
  get active() {
    return !!this.#active;
  }

  assertDriver(driver) {
    const a = this.#active;
    if (a && a.state !== 'recording') {
      throw new Error('Recording is interrupted. Finish as failed or discard it first.');
    }
    if (a && (a.driver !== driver || a.sessionId !== (driver.sessionId ?? null))) {
      throw new Error('Recording is pinned to another session. Finish or discard it before changing sessions.');
    }
  }

  async start(driver, name) {
    if (this.#active) {
      throw new Error('A recording is already active. Finish or discard it first.');
    }
    const id = randomUUID();
    const directory = join(this.directory, id);
    const a = {
      id,
      name,
      driver,
      sessionId: driver.sessionId ?? null,
      directory,
      startedAt: new Date().toISOString(),
      state: 'recording',
      schemaVersion: 1,
      events: [],
      persistenceError: null,
    };
    // Reserve before awaiting I/O so concurrent starts cannot create overlapping recordings.
    this.#active = a;
    try {
      await mkdir(directory, {recursive: true, mode: 0o700});
      await this.#append(a, {type: 'start', id, name, startedAt: a.startedAt});
      await this.#draft(a);
      return this.status();
    } catch (error) {
      this.#active = undefined;
      throw error;
    }
  }

  #steps(a) {
    return a.events
      .flatMap((event) =>
        event.steps.flatMap((step, index) => (event.statuses?.[index] === 'success' ? [{...step}] : [])),
      )
      .map((step, index) => ({...step, id: `step-${String(index + 1).padStart(3, '0')}`}));
  }

  #issues(a) {
    return a.events
      .filter((event) => !event.statuses || event.statuses.some((status) => !['success', 'skipped'].includes(status)))
      .map((event) => ({eventId: event.id, statuses: event.statuses ?? ['pending'], errors: event.errors ?? []}));
  }

  status() {
    const a = this.#active;
    if (!a) {
      return {active: false, last: this.#last};
    }
    return {
      active: true,
      recordingId: a.id,
      name: a.name,
      state: a.state,
      stepCount: this.#steps(a).length,
      issues: this.#issues(a),
      persistenceError: a.persistenceError,
      directory: a.directory,
      journalPath: join(a.directory, 'events.jsonl'),
      draftPath: join(a.directory, 'draft.json'),
    };
  }

  async #append(a, event) {
    await appendFile(
      join(a.directory, 'events.jsonl'),
      JSON.stringify({at: new Date().toISOString(), ...event}) + '\n',
      {mode: 0o600},
    );
  }

  async #draft(a) {
    const body = {
      recordingId: a.id,
      name: a.name,
      sessionId: a.sessionId,
      state: a.state,
      startedAt: a.startedAt,
      verification: 'not_replayed',
      events: a.events,
      scenario: {schemaVersion: a.schemaVersion, steps: this.#steps(a)},
      issues: this.#issues(a),
    };
    const temporary = join(a.directory, `draft-${randomUUID()}.tmp`);
    await writeFile(temporary, JSON.stringify(body, null, 2) + '\n', {mode: 0o600, flag: 'wx'});
    await rename(temporary, join(a.directory, 'draft.json'));
  }

  async begin(driver, steps, schemaVersion = 1) {
    const a = this.#active;
    if (!a) {
      return null;
    }
    this.assertDriver(driver);
    if (a.persistenceError) {
      throw new Error(
        'Recording persistence failed. No further mutations will run; inspect status and finish as failed or discard.',
      );
    }
    if (this.#steps(a).length + steps.length > 100) {
      throw new Error('Recording exceeds the batch limit of 100 steps. Finish this recording before continuing.');
    }
    a.schemaVersion = Math.max(
      a.schemaVersion,
      schemaVersion,
      steps.some(({command}) => mixedCommands.includes(command)) ? 2 : 1,
    );
    const event = {id: randomUUID(), steps: structuredClone(steps)};
    a.events.push(event);
    try {
      // Write intent before sending mutations. A crash leaves a pending, non-exportable draft.
      await this.#append(a, {type: 'command', ...event});
      await this.#draft(a);
    } catch (error) {
      a.persistenceError = String(error);
      throw new Error('Could not persist recording intent; the operation was not sent to Android.', {cause: error});
    }
    return {recording: a, event};
  }

  async complete(token, statuses, errors = []) {
    if (!token) {
      return undefined;
    }
    const {recording: a, event} = token;
    event.statuses = statuses;
    event.errors = errors.map((error) => (error == null ? null : String(error).slice(0, 4096)));
    try {
      await this.#append(a, {type: 'result', eventId: event.id, statuses, errors: event.errors});
      await this.#draft(a);
      return {recordingId: a.id, eventId: event.id, statuses};
    } catch (error) {
      a.persistenceError = String(error);
      // Keep the real device result: a disk error must not suggest that a completed action can be retried.
      return {
        recordingId: a.id,
        eventId: event.id,
        status: 'persistence_error',
        message:
          'Device execution finished, but saving its recording failed. Do not repeat the action to repair the recording.',
      };
    }
  }

  async observe(tool, status) {
    const a = this.#active;
    if (!a || a.persistenceError) {
      return;
    }
    try {
      await this.#append(a, {type: 'tool', tool, status});
    } catch (error) {
      a.persistenceError = String(error);
    }
  }

  async finish(outcome) {
    const a = this.#active;
    if (!a) {
      throw new Error('No active recording');
    }
    const steps = this.#steps(a);
    if (
      outcome === 'passed' &&
      (a.state !== 'recording' || !steps.length || this.#issues(a).length || a.persistenceError)
    ) {
      throw new Error(
        'Recording cannot be exported as passed: it is empty or contains failed, unknown, pending or unsaved operations. Finish as failed or discard; failed attempts are never silently removed.',
      );
    }
    a.state = outcome === 'passed' ? 'recorded' : 'failed';
    await this.#append(a, {type: 'finish', outcome});
    await this.#draft(a);
    let scenarioPath = null;
    if (outcome === 'passed') {
      scenarioPath = join(a.directory, 'scenario.json');
      const temporary = join(a.directory, `scenario-${randomUUID()}.tmp`);
      await writeFile(temporary, JSON.stringify({schemaVersion: a.schemaVersion, steps}, null, 2) + '\n', {
        mode: 0o600,
        flag: 'wx',
      });
      await rename(temporary, scenarioPath);
    }
    this.#last = {
      recordingId: a.id,
      state: a.state,
      outcome,
      stepCount: steps.length,
      scenarioPath,
      draftPath: join(a.directory, 'draft.json'),
      journalPath: join(a.directory, 'events.jsonl'),
      verification: 'not_replayed',
    };
    this.#active = undefined;
    return {...this.#last, ...(outcome === 'passed' ? {scenario: {schemaVersion: a.schemaVersion, steps}} : {})};
  }

  async discard() {
    const a = this.#active;
    if (!a) {
      throw new Error('No active recording');
    }
    a.state = 'discarded';
    await this.#append(a, {type: 'discard'});
    await this.#draft(a);
    this.#last = {recordingId: a.id, state: a.state, directory: a.directory};
    this.#active = undefined;
    return this.#last;
  }

  async interrupt() {
    const a = this.#active;
    if (!a) {
      return;
    }
    a.state = 'interrupted';
    await this.#append(a, {type: 'interrupt'});
    await this.#draft(a);
  }
}
