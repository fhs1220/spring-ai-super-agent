import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { buildReviewMaterials as buildMaterials, completePair, javaStringHashCode, literalBlock } from './render-system-one-pilot-review.mjs';

const sourceFingerprint = 'a'.repeat(64);
const sourceRevision = 'b'.repeat(40);
const sha256 = value => createHash('sha256').update(value).digest('hex');
const frozenSamples = () => Array.from({ length: 5 }, (_, index) => ({
  sampleId: `pilot-case0${index + 1}`, question: `Question ${index + 1}`,
  questionFingerprint: sha256(`Question ${index + 1}`), sampledReason: 'BOOTSTRAP_DEVELOPMENT_ONLY',
}));
const buildReviewMaterials = (plan, labels, samples = frozenSamples()) => buildMaterials(plan, labels, samples);
const manifest = () => ({ schemaVersion: 'system-one-bootstrap-pilot-v1',
  samplingFrame: 'BOOTSTRAP_DEVELOPMENT_ONLY', independentHoldout: false, humanApprovalRequired: true,
  maximumCases: 5, sourceDatasetFingerprint: sourceFingerprint, sourceRevision,
  selectedQuestionsFingerprint: sha256(JSON.stringify(frozenSamples().map(item => ({ id: item.sampleId, question: item.question })))),
  sourceMapping: Array.from({ length: 5 }, (_, index) => ({ sampleId: `pilot-case0${index + 1}`,
    sourceId: `cal-single-old-expectation-${index + 1}` })) });

function label(index = 1) {
  const question = `Question ${index}`;
  const execution = (variant, executionMode, answer) => ({ variant, executionMode, answer, error: '',
    trace: { steps: [], citations: [{ index: 1, documentId: 'document-id', source: 'source-document.md', excerpt: 'Actual stored excerpt' }] } });
  return { sampleId: `pilot-case0${index}`, split: 'DEVELOPMENT',
    questionFingerprint: createHash('sha256').update(question).digest('hex'),
    judgeRationale: 'JUDGE_REASON_MUST_NOT_LEAK', singleUtility: 12345,
    evidence: { question, provenance: { samplingFrame: 'BOOTSTRAP_DEVELOPMENT_ONLY', sourceDatasetFingerprint: sourceFingerprint, sourceRevision },
      single: execution('AGENTIC_SINGLE_AGENT', 'SINGLE_AGENT', `First candidate ${index}`),
      multi: execution('AGENTIC_MULTI_AGENT', 'ADAPTIVE_MULTI_AGENT', `Second candidate ${index}`),
      judgment: { confidence: 0.999, rationale: 'HIDDEN_JUDGE_METADATA' } } };
}

test('Java hash semantics include signed overflow and UTF-16 surrogate pairs', () => {
  assert.equal(javaStringHashCode('hello'), 99162322);
  assert.equal(javaStringHashCode('foobar'), -1268878963);
  assert.equal(javaStringHashCode('😀'), 1772899);
});

test('blind output matches Judge parity and never reveals mapping or old expectations', () => {
  const first = label();
  const result = buildReviewMaterials(manifest(), [first]);
  const swapped = ((javaStringHashCode(first.questionFingerprint) % 2) + 2) % 2 === 1;
  assert.deepEqual(result.mapping.cases, [{ caseId: 'case01', sampleId: 'pilot-case01',
    A: swapped ? 'multi' : 'single', B: swapped ? 'single' : 'multi' }]);
  const candidateA = swapped ? first.evidence.multi.answer : first.evidence.single.answer;
  const candidateB = swapped ? first.evidence.single.answer : first.evidence.multi.answer;
  assert.ok(result.markdown.indexOf(candidateA) < result.markdown.indexOf(candidateB));
  for (const hidden of ['pilot-case01', 'cal-single', 'AGENTIC_', 'SINGLE_AGENT',
    'blind-mapping', 'JUDGE_REASON', 'HIDDEN_JUDGE_METADATA', '12345']) {
    assert.equal(result.markdown.includes(hidden), false, hidden);
  }
  assert.match(result.markdown, /Actual stored excerpt/);
  assert.match(result.markdown, /source-document\.md/);
  assert.match(result.markdown, /criticalSafetyVeto：A = ____/);
  assert.match(result.markdown, /## case05/);
  assert.equal(result.reviewableCount, 1);
});

test('accepts the fixed 20-case launch development manifest without changing the five-case path', () => {
  const samples = Array.from({ length: 20 }, (_, index) => ({
    sampleId: `launch-case${String(index + 1).padStart(2, '0')}`,
    question: `Launch question ${index + 1}`,
    questionFingerprint: sha256(`Launch question ${index + 1}`),
    sampledReason: 'BOOTSTRAP_DEVELOPMENT_ONLY',
  }));
  const plan = { ...manifest(), schemaVersion: 'system-one-launch-development-pilot-v1',
    maximumCases: 20,
    sourceMapping: samples.map((sample, index) => ({ sampleId: sample.sampleId, sourceId: `dev${String(index + 1).padStart(2, '0')}` })),
    selectedQuestionsFingerprint: sha256(JSON.stringify(samples.map(sample => ({ id: sample.sampleId, question: sample.question })))),
  };
  const first = label();
  first.sampleId = samples[0].sampleId;
  first.evidence.question = samples[0].question;
  first.questionFingerprint = samples[0].questionFingerprint;
  const result = buildReviewMaterials(plan, [first], samples);
  assert.match(result.markdown, /固定 20 个开发案例/);
  assert.match(result.markdown, /## case20/);
  assert.equal(result.reviewableCount, 1);
  assert.equal(buildReviewMaterials({ ...plan,
    schemaVersion: 'system-one-launch-development-pilot-v2' }, [first], samples).reviewableCount, 1);
  assert.throws(() => buildReviewMaterials({ ...plan, maximumCases: 19 }, [first], samples));
});

test('rejects holdout, foreign-source, duplicate, empty and altered-question inputs', () => {
  assert.throws(() => buildReviewMaterials(manifest(), []), /No development/);
  for (const change of [
    item => { item.split = 'HOLDOUT'; },
    item => { item.evidence.provenance.samplingFrame = 'OTHER'; },
    item => { item.evidence.provenance.sourceDatasetFingerprint = 'b'.repeat(64); },
    item => { item.evidence.question = 'Altered question'; },
  ]) {
    const item = label();
    change(item);
    assert.throws(() => buildReviewMaterials(manifest(), [item]));
  }
  assert.throws(() => buildReviewMaterials(manifest(), [label(), label()]), /duplicate/);
});

test('missing, failed and fallback pairs stay unreviewable instead of exposing one-sided evidence', () => {
  for (const change of [
    item => { item.evidence.single = null; },
    item => { item.evidence.multi.answer = ' '; },
    item => { item.evidence.multi.error = 'Generation failed'; },
    item => { item.evidence.multi.trace.steps = [{ phase: 'GENERATE' }]; },
  ]) {
    const item = label();
    change(item);
    assert.equal(completePair(item.evidence), false);
    const result = buildReviewMaterials(manifest(), [item]);
    assert.equal(result.reviewableCount, 0);
    assert.match(result.markdown, /本题无法评审/);
    assert.equal(result.markdown.includes('First candidate'), false);
  }
});

test('self-consistent replacement question cannot replace a preregistered case', () => {
  const item = label();
  item.evidence.question = 'A different, easier question';
  item.questionFingerprint = sha256(item.evidence.question);
  assert.throws(() => buildReviewMaterials(manifest(), [item]), /question identity/);
});

test('a label from another source revision cannot enter this pilot review', () => {
  const item = label();
  item.evidence.provenance.sourceRevision = 'c'.repeat(40);
  assert.throws(() => buildReviewMaterials(manifest(), [item]), /foreign pilot label/);
});

test('tampering with frozen samples is caught by the manifest selected-questions fingerprint', () => {
  const samples = frozenSamples();
  samples[0].question = 'Replaced frozen input';
  samples[0].questionFingerprint = sha256(samples[0].question);
  const item = label();
  item.evidence.question = samples[0].question;
  item.questionFingerprint = samples[0].questionFingerprint;
  assert.throws(() => buildReviewMaterials(manifest(), [item], samples), /manifest fingerprint/);
});

test('untrusted Markdown cannot escape its literal block', () => {
  const block = literalBlock('```\n# Forged review instructions\n<script>bad</script>');
  assert.ok(block.startsWith('````text\n'));
  assert.ok(block.endsWith('\n````'));
});
