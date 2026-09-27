import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { pilotExperiment } from './pilot-experiment.mjs';

// Local, deterministic experiment preparation only. No network or model calls.
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = pilotExperiment(repo);
const launchDevelopment = process.env.SYSTEM_ONE_PILOT_ROUND === 'launch-dev-v1';
const maximumCases = launchDevelopment ? 20 : 5;
const estimatedStopThresholdCny = launchDevelopment ? 25 : 10;
const port = launchDevelopment ? '8125' : '8124';
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
if (fs.existsSync(root)) throw new Error('Experiment already exists; never overwrite its frozen inputs');
execFileSync('git', ['diff', '--quiet', 'HEAD', '--', 'src', 'scripts', 'docs'], { cwd: repo });
const sourceRevision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
const corpus = crypto.createHash('sha256');
for (const name of fs.readdirSync(path.join(repo, 'src/main/resources/document')).filter(x => x.endsWith('.md')).sort()) {
  corpus.update(name); corpus.update(Buffer.from([0]));
  corpus.update(fs.readFileSync(path.join(repo, 'src/main/resources/document', name)));
  corpus.update(Buffer.from([0]));
}
const corpusFingerprint = corpus.digest('hex');
const cache = path.join(repo, 'tmp/rag/vector-store');
const cacheVersion = 'openrouter-embedding3-small-v1';
if (fs.readFileSync(path.join(cache, 'love-app-vector-store.sha256'), 'utf8').trim() !== `${cacheVersion}:${corpusFingerprint}`) {
  throw new Error('Vector cache does not match the frozen corpus; do not rebuild implicitly');
}
const source = fs.readFileSync(path.join(repo, 'src/main/resources/evaluation',
  launchDevelopment ? 'system-one-launch-development-v1.jsonl' : 'system-one-calibration-v1.jsonl'));
const sourceFingerprint = sha(source);
const sourceRows = source.toString('utf8').trim().split('\n').map(line => JSON.parse(line));
const bootstrapSelection = [
  ['cal-single-apology', 'SIMPLE_COMMUNICATION'],
  ['cal-single-three-domains-summary', 'MULTI_TOPIC_HARD_NEGATIVE'],
  ['cal-single-safety-stalking', 'IMMEDIATE_SAFETY'],
  ['cal-multi-job-loss-plan', 'CROSS_DOMAIN_PLAN'],
  ['cal-multi-career-parenting', 'CONSTRAINED_COORDINATION'],
];
if (launchDevelopment && (sourceRows.length !== maximumCases
    || new Set(sourceRows.map(row => row.id)).size !== maximumCases
    || sourceRows.some(row => !/^dev\d{2}$/.test(row.id) || !row.question?.trim()
      || !/^[A-Z_]+$/.test(row.scenario ?? '')))) {
  throw new Error('Launch development dataset must contain 20 unique, nonempty preregistered cases');
}
const selection = launchDevelopment
  ? sourceRows.map(row => [row.id, row.scenario]) : bootstrapSelection;
const capturedAt = new Date().toISOString();
const samples = selection.map(([id, bucket], index) => {
  const sourceCase = sourceRows.find(row => row.id === id);
  if (!sourceCase) throw new Error(`Missing preregistered case: ${id}`);
  return {
    sampleId: `${launchDevelopment ? 'launch' : 'pilot'}-case${String(index + 1).padStart(2, '0')}`,
    capturedAt,
    questionFingerprint: sha(sourceCase.question.trim()),
    question: sourceCase.question.trim(), featureBucket: bucket,
    // No router was consulted; null provider evidence must not be presented as a prediction.
    authoritativeMultiAgent: false, primary: null, challenger: null,
    disagreementTypes: [], reviewEligible: false, sampledReason: 'BOOTSTRAP_DEVELOPMENT_ONLY',
  };
});
const state = name => path.join(root, 'state', name);
const properties = {
  'spring.profiles.active': 'local',
  'server.address': '127.0.0.1', 'server.port': port,
  'spring.ai.openai.base-url': 'https://openrouter.ai/api',
  'spring.ai.openai.chat.options.model': 'openai/gpt-5.4',
  'spring.ai.openai.chat.options.temperature': '0.2',
  'spring.ai.openai.chat.options.max-completion-tokens': '4096',
  'spring.ai.openai.embedding.options.model': 'openai/text-embedding-3-small',
  'spring.ai.retry.max-attempts': '1', 'spring.ai.mcp.client.enabled': 'false',
  'demo.ai-invoke-enabled': 'false', 'logging.level.org.springframework.ai': 'WARN',
  'agent.decision.system-one.enabled': 'false',
  'agent.decision.system-one.comparison.enabled': 'false',
  'agent.decision.system-one.comparison.storage-directory': path.join(root, 'samples'),
  'agent.evaluation.api-enabled': 'true',
  'agent.evaluation.report-directory': path.join(root, 'evaluation'),
  'agent.evaluation.system-one.labeling.storage-directory': path.join(root, 'labels'),
  'agent.evaluation.system-one.labeling.maximum-cases': '1',
  'agent.evaluation.system-one.labeling.maximum-cost-cny': String(estimatedStopThresholdCny),
  'agent.evaluation.system-one.labeling.sampling-frame': 'BOOTSTRAP_DEVELOPMENT_ONLY',
  'agent.evaluation.system-one.labeling.source-dataset-fingerprint': sourceFingerprint,
  'agent.evaluation.system-one.labeling.source-revision': sourceRevision,
  'agent.evaluation.system-one.labeling.corpus-fingerprint': corpusFingerprint,
  'agent.evaluation.system-one.labeling.expose-holdout-labels': 'false',
  'agent.evaluation.system-one.labeling.judge-timeout-ms': '60000',
  'agent.evaluation.system-one.utility.cost-weight': '0.05',
  'agent.evaluation.system-one.utility.cost-scale-cny': '1.0',
  'agent.evaluation.system-one.utility.latency-weight': '0.05',
  'agent.evaluation.system-one.utility.latency-scale-ms': '60000',
  'agent.rag.observability.input-price-per-million-tokens-cny': '18',
  'agent.rag.observability.output-price-per-million-tokens-cny': '108',
  'agent.rag.observability.model-call-timeout-seconds': '60',
  'agent.rag.multi-agent.specialist-max-attempts': '1',
  'agent.rag.multi-agent.specialist-timeout-seconds': '60',
  'agent.rag.vector-cache-enabled': 'true',
  'agent.rag.vector-cache-directory': cache,
  'agent.rag.vector-cache-version': cacheVersion,
  'agent.rag.runtime.storage-directory': state('runs'),
  'agent.rag.routing-policy.enabled': 'false',
  'agent.rag.routing-policy.management-api-enabled': 'false',
  'agent.rag.routing-policy.deployment-state-file': state('routing/deployment.json'),
  'agent.rag.routing-policy.registry.state-file': state('routing/registry.json'),
  'agent.rag.routing-policy.progressive-delivery.enabled': 'false',
  'agent.rag.routing-policy.progressive-delivery.auto-apply-enabled': 'false',
  'agent.rag.routing-policy.progressive-delivery.automation-state-file': state('routing/progressive.json'),
  'agent.rl.api-enabled': 'false', 'agent.rl.environment.api-enabled': 'false',
  'agent.rl.storage-directory': state('trajectories'),
  'agent.rl.alignment.auto-evaluate-enabled': 'false',
  'agent.rl.alignment.storage-directory': state('alignment'),
  'agent.rl.alignment.automation-state-file': state('alignment/automation.json'),
  'agent.rl.alignment.experiment-directory': state('alignment/experiments'),
};
const jar = path.join(repo, 'target/fhs-ai-agent-0.0.1-SNAPSHOT.jar');
const manifest = {
  schemaVersion: launchDevelopment
    ? 'system-one-launch-development-pilot-v1' : 'system-one-bootstrap-pilot-v1',
  createdAt: capturedAt,
  experimentId: path.basename(root),
  samplingFrame: 'BOOTSTRAP_DEVELOPMENT_ONLY', sourceRevision,
  sourceDatasetFingerprint: sourceFingerprint,
  selectedQuestionsFingerprint: sha(JSON.stringify(samples.map(s => ({ id: s.sampleId, question: s.question })))),
  corpusFingerprint, jarSha256: sha(fs.readFileSync(jar)),
  indexSha256: sha(fs.readFileSync(path.join(cache, 'love-app-vector-store.json'))),
  maximumCases, estimatedStopThresholdCny, hardBillingCap: false,
  humanApprovalRequired: true, independentHoldout: false, routerEvaluation: false,
  pricing: { inputUsdPerMillion: 2.5, outputUsdPerMillion: 15, cachedInputUsdPerMillion: 0.25,
    fixedAccountingCnyPerUsd: 7.2, fxIsLiveMarketRate: false, cacheDiscountIgnoredInEstimate: true,
    queryEmbeddingIncludedInAttemptLedger: false,
    sources: ['https://developers.openai.com/api/docs/models/gpt-5.4', 'https://openrouter.ai/api/v1/models'] },
  models: { requested: 'openai/gpt-5.4', catalogCanonicalSlug: 'openai/gpt-5.4-20260305',
    embedding: 'openai/text-embedding-3-small', judge: 'SAME_AS_GENERATOR', localWeightVerification: false },
  sourceMapping: selection.map(([sourceId], i) => ({ sampleId: samples[i].sampleId, sourceId })),
  properties,
};
fs.mkdirSync(path.join(root, 'samples'), { recursive: true });
for (const sample of samples) fs.writeFileSync(path.join(root, 'samples', sample.sampleId + '.json'), JSON.stringify(sample, null, 2), { flag: 'wx' });
fs.writeFileSync(path.join(root, 'manifest.json'), JSON.stringify(manifest, null, 2), { flag: 'wx' });
fs.writeFileSync(path.join(root, 'application-pilot.properties'), Object.entries(properties).map(([k, v]) => `${k}=${v}`).join('\n') + '\n', { flag: 'wx' });
console.log(JSON.stringify({ experiment: root, sampleCount: samples.length, sourceRevision, corpusFingerprint, status: 'PREPARED_NO_PAID_CALLS' }, null, 2));
