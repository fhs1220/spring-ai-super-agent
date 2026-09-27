import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectLabels, parseProperties } from './system-one-pilot.mjs';

function label() {
  const execution = (variant, executionMode) => ({ variant, executionMode,
    answer: 'test answer', error: '', usageAvailable: true, estimatedCostCny: 0.2 });
  return { sampleId: 'shadow-test', split: 'DEVELOPMENT', status: 'REVIEW_REQUIRED', evidence: {
    provenance: { samplingFrame: 'BOOTSTRAP_DEVELOPMENT_ONLY' },
    single: execution('AGENTIC_SINGLE_AGENT', 'SINGLE_AGENT'),
    multi: execution('AGENTIC_MULTI_AGENT', 'ADAPTIVE_MULTI_AGENT'),
    judgment: { confidence: 0.6 },
    attempts: ['AGENTIC_SINGLE_AGENT', 'AGENTIC_MULTI_AGENT', 'JUDGE'].map(stage => ({
      stage, finishedAt: '2026-09-27T00:00:00Z', status: 'SUCCEEDED', usageMeasured: true,
      estimatedCostCny: stage === 'JUDGE' ? 0.1 : 0.2, errorType: '',
    })),
  } };
}

test('counts Judge plus both generator charges, including review-required labels', () => {
  assert.deepEqual(inspectLabels([label()]), { labelCount: 1, knownCostCny: 0.5, remainingCny: 9.5 });
});

test('sealed or non-bootstrap sources cannot enter this development pilot', () => {
  const holdout = label();
  holdout.split = 'HOLDOUT';
  assert.throws(() => inspectLabels([holdout]), /isolated bootstrap development experiment/);
  const otherExperiment = label();
  otherExperiment.evidence.provenance.samplingFrame = 'DISAGREEMENT_ENRICHED_NOT_POPULATION';
  assert.throws(() => inspectLabels([otherExperiment]), /isolated bootstrap development experiment/);
});

test('does not rely on a non-serialized costAccountingComplete label method', () => {
  const item = label();
  item.costAccountingComplete = true;
  item.evidence.attempts[1].usageMeasured = false;
  assert.throws(() => inspectLabels([item]), /unmetered attempt/);
});

test('unknown, failed, or pending attempts cannot enable another paid submission', () => {
  for (const change of [
    item => { item.evidence.attempts[0].finishedAt = null; },
    item => { item.evidence.attempts[0].estimatedCostCny = null; },
    item => { item.evidence.attempts[0].status = 'FAILED'; },
    item => { item.status = 'IN_PROGRESS'; },
  ]) {
    const item = label();
    change(item);
    assert.throws(() => inspectLabels([item]), /Pilot stopped:/);
  }
});

test('blank answers, wrong forced modes, and multi fallback invalidate the pair', () => {
  for (const change of [
    item => { item.evidence.single.answer = ' '; },
    item => { item.evidence.multi.executionMode = 'SINGLE_AGENT'; },
    item => { item.evidence.multi.trace = { steps: [{ phase: 'GENERATE' }] }; },
  ]) {
    const item = label();
    change(item);
    assert.throws(() => inspectLabels([item]), /invalid single\/multi/);
  }
});

test('duplicate labels and absent Judge attempt cannot undercount the experiment', () => {
  assert.throws(() => inspectLabels([label(), label()]), /duplicate sample/);
  const item = label();
  item.evidence.attempts.pop();
  assert.throws(() => inspectLabels([item]), /incomplete generation\/Judge/);
});

test('known charges above the ceiling leave no remaining budget', () => {
  const item = label();
  item.evidence.attempts[0].estimatedCostCny = 11;
  assert.equal(inspectLabels([item]).remainingCny, 0);
});

test('properties remain literal arguments, while credentials and duplicate keys are rejected', () => {
  const properties = parseProperties('server.port=8124\nspring.ai.openai.api-key=${OPENROUTER_API_KEY}\n');
  assert.equal(properties.get('spring.ai.openai.api-key'), '${OPENROUTER_API_KEY}');
  assert.throws(() => parseProperties('server.port=8124\nserver.port=9000'), /Duplicate/);
  assert.throws(() => parseProperties('spring.ai.openai.api-key=sk-or-test-secret-placeholder'), /Literal credentials/);
  assert.throws(() => parseProperties('spring.ai.openai.api-key=arbitrary-literal'), /may only reference/);
});
