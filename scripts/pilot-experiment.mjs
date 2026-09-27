import { join } from 'node:path';

// Only named, preregistered rounds; never accept an arbitrary output path.
export function pilotExperiment(repo, round = process.env.SYSTEM_ONE_PILOT_ROUND ?? 'v1') {
  if (round === 'launch-dev-v1') return join(repo, 'tmp', 'system-one-launch-development-v1');
  if (!['v1', 'v2', 'v3'].includes(round)) throw new Error('Unknown preregistered pilot round');
  return join(repo, 'tmp', `system-one-bootstrap-pilot-${round}`);
}
