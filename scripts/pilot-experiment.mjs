import { join } from 'node:path';

// Only named, preregistered rounds; never accept an arbitrary output path.
export function pilotExperiment(repo, round = process.env.SYSTEM_ONE_PILOT_ROUND ?? 'v1') {
  if (!['v1', 'v2', 'v3'].includes(round)) throw new Error('Pilot round must be v1, v2 or v3');
  return join(repo, 'tmp', `system-one-bootstrap-pilot-${round}`);
}
