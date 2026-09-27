import test from 'node:test';
import assert from 'node:assert/strict';
import { pilotExperiment } from './pilot-experiment.mjs';

test('preregistered rounds have different fixed directories', () => {
  assert.equal(pilotExperiment('/repo', 'v1'), '/repo/tmp/system-one-bootstrap-pilot-v1');
  assert.equal(pilotExperiment('/repo', 'v2'), '/repo/tmp/system-one-bootstrap-pilot-v2');
  assert.equal(pilotExperiment('/repo', 'v3'), '/repo/tmp/system-one-bootstrap-pilot-v3');
});
test('rejects arbitrary paths, unknown rounds and blank selectors', () => {
  for (const round of ['', '../../v1', '/tmp', 'v4', 'v2/../v1']) {
    assert.throws(() => pilotExperiment('/repo', round));
  }
});
