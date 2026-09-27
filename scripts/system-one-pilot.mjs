#!/usr/bin/env node
// Five-case supervised pilot. No automatic POST retries and no direct model calls.
import { readFile, writeFile, readdir, mkdir, open, rename, unlink, stat } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { dirname, resolve, join, relative, isAbsolute } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { StringDecoder } from 'node:string_decoder';
import { pilotExperiment } from './pilot-experiment.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const EXPERIMENT = pilotExperiment(ROOT);
const PROPERTIES = join(EXPERIMENT, 'application-pilot.properties');
const INTENTS = join(EXPERIMENT, 'intents');
const LOCAL_API = 'http://127.0.0.1:8124/api/agent-evaluation/system-one-labeling/runs';
const BUDGET_CNY = 10;
const MAX_LABELS = 5;
const RUN_ID = /^system-one-label-[a-f0-9-]{36}$/;

export function parseProperties(text) {
  const result = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || /^\s*[#!]/.test(line)) continue;
    const match = line.match(/^\s*([A-Za-z0-9_.-]+)\s*=\s*(.*?)\s*$/);
    if (!match || line.endsWith('\\')) throw new Error('Pilot properties must use plain, single-line key=value entries');
    if (result.has(match[1])) throw new Error(`Duplicate pilot property: ${match[1]}`);
    if (/sk-[A-Za-z0-9_-]{8,}/.test(match[2])) throw new Error('Literal credentials are forbidden in pilot properties');
    if (/password|secret|api[-.]?key/i.test(match[1]) && match[2]
        && match[2] !== '${OPENROUTER_API_KEY}') {
      throw new Error('Credential properties may only reference OPENROUTER_API_KEY');
    }
    result.set(match[1], match[2]);
  }
  return result;
}

async function credential() {
  let key = process.env.OPENROUTER_API_KEY?.trim();
  if (!key) {
    const dotenv = await readFile(join(ROOT, '.env'), 'utf8');
    const entries = dotenv.split(/\r?\n/).filter(line => /^\s*(?:export\s+)?OPENROUTER_API_KEY\s*=/.test(line));
    if (entries.length !== 1) throw new Error('Expected one OPENROUTER_API_KEY assignment in local .env');
    const value = entries[0].replace(/^\s*(?:export\s+)?OPENROUTER_API_KEY\s*=\s*/, '').trim();
    if (value.startsWith('"') || value.startsWith("'")) {
      const match = value.match(/^(['"])(.*?)\1\s*(?:#.*)?$/);
      if (!match) throw new Error('Invalid quoted OPENROUTER_API_KEY assignment');
      key = match[2];
    } else {
      key = value.replace(/\s+#.*$/, '').trim();
    }
  }
  if (!/^sk-or-[A-Za-z0-9_-]+$/.test(key ?? '')) throw new Error('OPENROUTER_API_KEY is missing or malformed');
  return key;
}

function redact(text, key = '') {
  return (key ? text.replaceAll(key, '[REDACTED]') : text)
    .replace(/\bsk-[A-Za-z0-9_-]+/g, '[REDACTED]')
    .replace(/(Authorization\s*[:=]\s*Bearer\s+)[^\s"']+/gi, '$1[REDACTED]');
}

async function atomicJson(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  await rename(temporary, path);
}

async function jsonFiles(directory) {
  let entries;
  try { entries = await readdir(directory, { withFileTypes: true }); }
  catch (error) { if (error.code === 'ENOENT') return []; throw error; }
  if (entries.some(entry => entry.isSymbolicLink())) throw new Error('Symlinks are not permitted in pilot ledger directories');
  const files = entries.filter(entry => entry.isFile() && entry.name.endsWith('.json')).sort((a, b) => a.name.localeCompare(b.name));
  return Promise.all(files.map(async entry => JSON.parse(await readFile(join(directory, entry.name), 'utf8'))));
}

async function configuration() {
  const properties = parseProperties(await readFile(PROPERTIES, 'utf8'));
  if (properties.get('server.port') !== '8124' || properties.get('server.address') !== '127.0.0.1') {
    throw new Error('Pilot server must explicitly bind 127.0.0.1:8124');
  }
  const configured = properties.get('agent.evaluation.system-one.labeling.storage-directory');
  if (!configured || configured.includes('${')) throw new Error('Pilot label storage must be an explicit path');
  const labels = resolve(ROOT, configured);
  const offset = relative(EXPERIMENT, labels);
  if (!offset || offset.startsWith('..') || isAbsolute(offset)) throw new Error('Pilot label storage must be inside the experiment directory');
  return { properties, labels };
}

export function inspectLabels(labels) {
  const seen = new Set();
  let knownCostCny = 0;
  for (const label of labels) {
    if (label.split !== 'DEVELOPMENT'
        || label.evidence?.provenance?.samplingFrame !== 'BOOTSTRAP_DEVELOPMENT_ONLY') {
      throw new Error('Pilot stopped: label is not from the isolated bootstrap development experiment');
    }
    if (!label.sampleId || seen.has(label.sampleId)) throw new Error('Missing or duplicate sample ID in label ledger');
    seen.add(label.sampleId);
    if (!['COMPLETED', 'REVIEW_REQUIRED', 'APPROVED'].includes(label.status)) {
      throw new Error(`Pilot stopped: label status ${label.status ?? 'missing'}`);
    }
    const evidence = label.evidence;
    const attempts = evidence?.attempts;
    if (!Array.isArray(attempts) || !attempts.length) throw new Error('Pilot stopped: missing attempt ledger');
    for (const attempt of attempts) {
      if (!attempt.finishedAt || !Number.isFinite(Date.parse(attempt.finishedAt))
          || attempt.usageMeasured !== true || !Number.isFinite(attempt.estimatedCostCny)
          || attempt.estimatedCostCny < 0 || attempt.status !== 'SUCCEEDED' || attempt.errorType) {
        throw new Error('Pilot stopped: pending, failed, or unmetered attempt');
      }
      knownCostCny += attempt.estimatedCostCny;
    }
    const stages = new Set(attempts.map(attempt => attempt.stage));
    if (!['AGENTIC_SINGLE_AGENT', 'AGENTIC_MULTI_AGENT', 'JUDGE'].every(stage => stages.has(stage))) {
      throw new Error('Pilot stopped: incomplete generation/Judge ledger');
    }
    const single = evidence.single;
    const multi = evidence.multi;
    const validAnswer = execution => execution && !execution.error
      && typeof execution.answer === 'string' && execution.answer.trim()
      && execution.usageAvailable === true && Number.isFinite(execution.estimatedCostCny)
      && execution.estimatedCostCny >= 0;
    if (!validAnswer(single) || !validAnswer(multi) || !evidence.judgment
        || single.variant !== 'AGENTIC_SINGLE_AGENT' || single.executionMode !== 'SINGLE_AGENT'
        || multi.variant !== 'AGENTIC_MULTI_AGENT' || multi.executionMode !== 'ADAPTIVE_MULTI_AGENT'
        || multi.trace?.steps?.some(step => step.phase === 'GENERATE')) {
      throw new Error('Pilot stopped: invalid single/multi counterfactual pair or missing Judge');
    }
  }
  if (!Number.isFinite(knownCostCny)) throw new Error('Invalid aggregate ledger cost');
  return { labelCount: labels.length, knownCostCny, remainingCny: Math.max(0, BUDGET_CNY - knownCostCny) };
}

async function readLedger(labelsDirectory) {
  const labels = (await Promise.all(['development', 'holdout'].map(split => jsonFiles(join(labelsDirectory, split))))).flat();
  const ledger = inspectLabels(labels);
  const runs = await jsonFiles(join(labelsDirectory, 'runs'));
  let reportedRunCost = 0;
  for (const run of runs) {
    if (!['COMPLETED', 'COMPLETED_BUDGET_LIMIT'].includes(run.status) || !run.completedAt) {
      throw new Error(`Pilot stopped: previous run is unfinished or failed (${run.status ?? 'missing'})`);
    }
    if (run.costAccountingComplete !== true || !Number.isFinite(run.totalCostCny) || run.totalCostCny < 0) {
      throw new Error('Pilot stopped: previous run billing is incomplete');
    }
    reportedRunCost += run.totalCostCny;
  }
  if (reportedRunCost > ledger.knownCostCny + 1e-7) throw new Error('Pilot stopped: run cost is not reconciled by label attempts');
  return ledger;
}

async function localRequest(url, options = {}) {
  // Exactly one attempt. An ambiguous POST remains protected by its persisted intent.
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15_000), redirect: 'error' });
  if (!response.ok) throw new Error(`Local evaluation HTTP ${response.status}`);
  return response.json();
}

async function submitOne() {
  const { labels } = await configuration();
  await mkdir(INTENTS, { recursive: true, mode: 0o700 });
  const lockPath = join(EXPERIMENT, 'submit.lock');
  const lock = await open(lockPath, 'wx', 0o600);
  try {
    const intents = await jsonFiles(INTENTS);
    if (intents.some(intent => intent.state !== 'ACCEPTED' || !RUN_ID.test(intent.runId ?? ''))) {
      throw new Error('Pending/uncertain submit intent exists; reconcile manually before any new POST');
    }
    const ledger = await readLedger(labels);
    if (ledger.labelCount >= MAX_LABELS) throw new Error('Five-label pilot limit reached');
    if (ledger.remainingCny <= 0) throw new Error('Pilot budget exhausted');
    // Check all accepted run IDs even when server persistence has not yet appeared locally.
    for (const intent of intents) {
      const run = await localRequest(`${LOCAL_API}/${intent.runId}`);
      if (!['COMPLETED', 'COMPLETED_BUDGET_LIMIT'].includes(run.status)
          || !run.completedAt || run.costAccountingComplete !== true) {
        throw new Error('Previous submitted run is unfinished, failed, or has unknown billing');
      }
    }
    const body = { maximumCases: 1, maximumCostCny: Math.floor(ledger.remainingCny * 1e8) / 1e8 };
    if (body.maximumCostCny <= 0) throw new Error('Remaining budget is below accounting precision');
    const intentPath = join(INTENTS, `intent-${Date.now()}-${randomUUID()}.json`);
    const intent = { state: 'PENDING', createdAt: new Date().toISOString(), request: body, ledgerBefore: ledger };
    await writeFile(intentPath, JSON.stringify(intent, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    let run;
    try {
      run = await localRequest(LOCAL_API, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (!RUN_ID.test(run.runId ?? '')) throw new Error('POST response did not include a valid runId');
      await atomicJson(intentPath, { ...intent, state: 'ACCEPTED', runId: run.runId });
    } catch {
      throw new Error('POST outcome is uncertain; pending intent retained. No automatic retry is permitted');
    }
    console.log(JSON.stringify({ runId: run.runId, status: run.status, maximumCostCny: body.maximumCostCny }));
  } finally {
    await lock.close();
    await unlink(lockPath);
  }
}

async function status() {
  const accepted = (await jsonFiles(INTENTS)).filter(intent => intent.state === 'ACCEPTED');
  const last = accepted.at(-1);
  if (!last || !RUN_ID.test(last.runId ?? '')) throw new Error('No accepted pilot run ID is available');
  const run = await localRequest(`${LOCAL_API}/${last.runId}`);
  console.log(redact(JSON.stringify({ runId: run.runId, status: run.status,
    completedCases: run.completedCases, totalCases: run.totalCases, totalCostCny: run.totalCostCny,
    costAccountingComplete: run.costAccountingComplete, completedAt: run.completedAt, error: run.error })));
}

async function accountSnapshot(action) {
  const key = await credential();
  const body = await new Promise((resolveBody, reject) => {
    // curl honors HTTPS_PROXY; the Authorization header only travels through stdin, never argv.
    const child = spawn('curl', ['-q', '--request', 'GET', '--silent', '--show-error', '--fail', '--max-time', '20',
      '--config', '-', 'https://openrouter.ai/api/v1/key'], { stdio: ['pipe', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => { output += chunk; if (output.length > 1_000_000) child.kill(); });
    child.stderr.resume();
    child.once('error', () => reject(new Error('Could not start read-only account request')));
    child.once('close', code => code === 0 ? resolveBody(output) : reject(new Error(`Account read failed (curl exit ${code})`)));
    child.stdin.on('error', () => {});
    child.stdin.end(`header = "Authorization: Bearer ${key}"\n`);
  });
  let data;
  try { data = JSON.parse(body).data; } catch { throw new Error('Account endpoint returned invalid JSON'); }
  if (!data || typeof data !== 'object') throw new Error('Account endpoint returned no usage object');
  const sanitized = {};
  for (const field of ['usage', 'usage_daily', 'limit', 'limit_remaining']) {
    const value = data[field];
    if (value != null && (typeof value !== 'number' || !Number.isFinite(value))) throw new Error('Account usage field is not numerical');
    sanitized[field] = value ?? null;
  }
  if (data.is_free_tier != null && typeof data.is_free_tier !== 'boolean') throw new Error('Invalid account tier field');
  sanitized.is_free_tier = data.is_free_tier ?? null;
  await mkdir(EXPERIMENT, { recursive: true, mode: 0o700 });
  await writeFile(join(EXPERIMENT, `${action}.json`), JSON.stringify(sanitized, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  console.log(`Saved ${action}.json (usage fields only)`);
}

function pipeRedacted(readable, log, key) {
  const decoder = new StringDecoder('utf8');
  let buffered = '';
  let discarding = false;
  const consume = text => {
    buffered += text;
    let newline;
    while ((newline = buffered.indexOf('\n')) !== -1) {
      const line = buffered.slice(0, newline + 1);
      buffered = buffered.slice(newline + 1);
      if (!discarding) log.write(redact(line, key));
      discarding = false;
    }
    if (buffered.length > 1_000_000) {
      buffered = '';
      discarding = true;
      log.write('[oversized log line omitted]\n');
    }
  };
  readable.on('data', chunk => consume(decoder.write(chunk)));
  readable.on('end', () => {
    consume(decoder.end());
    if (!discarding && buffered) log.write(redact(buffered, key));
  });
}

async function serve() {
  const { properties } = await configuration();
  const key = await credential();
  const jar = join(ROOT, 'target/fhs-ai-agent-0.0.1-SNAPSHOT.jar');
  if (!(await stat(jar)).isFile()) throw new Error('Packaged application JAR is unavailable');
  const log = createWriteStream(join(EXPERIMENT, 'server.log'), { flags: 'a', mode: 0o600 });
  const child = spawn('java', ['-jar', jar, `--spring.config.additional-location=file:${PROPERTIES}`,
    ...[...properties].map(([name, value]) => `--${name}=${value}`)], {
    cwd: ROOT, env: { ...process.env, OPENROUTER_API_KEY: key }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  pipeRedacted(child.stdout, log, key);
  pipeRedacted(child.stderr, log, key);
  const interrupt = () => child.kill('SIGINT');
  const terminate = () => child.kill('SIGTERM');
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', terminate);
  console.log(`Pilot server child PID ${child.pid ?? 'pending'}; redacted logs: ${join(EXPERIMENT, 'server.log')}`);
  await new Promise((resolveExit, reject) => {
    log.once('error', () => { child.kill('SIGTERM'); reject(new Error('Could not write pilot log')); });
    child.once('error', () => reject(new Error('Could not start pilot Java process')));
    child.once('close', code => {
      process.off('SIGINT', interrupt);
      process.off('SIGTERM', terminate);
      log.end(() => { process.exitCode = code ?? 0; resolveExit(); });
    });
  });
}

async function main() {
  const [action, ...extra] = process.argv.slice(2);
  if (extra.length) throw new Error('This pilot accepts one command and no path/budget overrides');
  if (action === 'serve') await serve();
  else if (action === 'submit-one') await submitOne();
  else if (action === 'status') await status();
  else if (action === 'account-before' || action === 'account-after') await accountSnapshot(action);
  else throw new Error('Usage: node scripts/system-one-pilot.mjs account-before|account-after|serve|submit-one|status');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });
}
