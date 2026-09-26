def average:
  if length == 0 then 0 else add / length end;

def ratio($numerator; $denominator):
  if $denominator == 0 then 0 else $numerator / $denominator end;

def round4:
  . * 10000 | round / 10000;

def round8:
  . * 100000000 | round / 100000000;

def utility($result):
  $result.score.total
  - (0.05 * ([1, ($result.execution.estimatedCostCny / 0.02)] | min))
  - (0.05 * ([1, ($result.execution.latencyMs / 60000)] | min));

def without_replay_prefix:
  sub("^frozen-replay-"; "");

($benchmark[0]) as $source
| ($decisions[0]) as $decisionReport
| if ($source | type) != "object" or ($decisionReport | type) != "object" then
    error("both inputs must contain one JSON object")
  else . end
| ($decisionReport.cases
   | map({key: (.id | without_replay_prefix), value: .})
   | from_entries) as $decisionByCase
| if ($source.cases | length) != ($decisionReport.cases | length) then
    error("benchmark and decision case counts differ")
  else . end
| [$source.cases[] as $case
   | ($decisionByCase[$case.caseId]) as $decision
   | if $decision == null then
       error("missing decision for case: " + $case.caseId)
     else
       ($case.candidate.execution.executionMode == "ADAPTIVE_MULTI_AGENT") as $currentMulti
       | $decision.predictedMultiAgent as $shadowMulti
       | (if $currentMulti then $case.forcedMulti else $case.forcedSingle end) as $current
       | (if $shadowMulti then $case.forcedMulti else $case.forcedSingle end) as $shadow
       | (utility($case.forcedSingle)) as $singleUtility
       | (utility($case.forcedMulti)) as $multiUtility
       | {
           caseId: $case.caseId,
           expectedMultiAgent: $decision.expectedMultiAgent,
           currentMultiAgent: $currentMulti,
           shadowMultiAgent: $shadowMulti,
           currentQuality: $current.score.total,
           shadowQuality: $shadow.score.total,
           currentUtility: utility($current),
           shadowUtility: utility($shadow),
           oracleUtility: ([$singleUtility, $multiUtility] | max),
           currentCostCny: $current.execution.estimatedCostCny,
           shadowCostCny: $shadow.execution.estimatedCostCny,
           currentLatencyMs: $current.execution.latencyMs,
           shadowLatencyMs: ($shadow.execution.latencyMs + $decision.latencyMs)
         }
     end] as $cases
| ($cases | map(.currentQuality) | average) as $currentQuality
| ($cases | map(.shadowQuality) | average) as $shadowQuality
| ($cases | map(.currentUtility) | average) as $currentUtility
| ($cases | map(.shadowUtility) | average) as $shadowUtility
| ($cases | map(.oracleUtility) | average) as $oracleUtility
| ($oracleUtility - $currentUtility) as $currentRegret
| ($oracleUtility - $shadowUtility) as $shadowRegret
| ($cases | map(.currentCostCny) | add) as $currentCost
| ($cases | map(.shadowCostCny) | add) as $shadowCost
| ($cases | map(select(.expectedMultiAgent and .currentMultiAgent)) | length) as $currentTp
| ($cases | map(select((.expectedMultiAgent | not) and .currentMultiAgent)) | length) as $currentFp
| ($cases | map(select((.expectedMultiAgent | not) and (.currentMultiAgent | not))) | length) as $currentTn
| ($cases | map(select(.expectedMultiAgent and (.currentMultiAgent | not))) | length) as $currentFn
| (ratio($currentTp; $currentTp + $currentFn)) as $currentRecall
| (ratio($currentTn; $currentTn + $currentFp)) as $currentSpecificity
| {
    sourceRunId: $source.runId,
    benchmarkVersion: $source.benchmarkVersion,
    benchmarkFingerprint: $source.benchmarkFingerprint,
    decisionDatasetFingerprint: $decisionReport.datasetFingerprint,
    model: $decisionReport.model,
    threshold: $decisionReport.configuredThreshold,
    caseCount: ($cases | length),
    availability: $decisionReport.availability,
    currentRouting: {
      accuracy: (ratio($currentTp + $currentTn; $cases | length) | round4),
      precision: (ratio($currentTp; $currentTp + $currentFp) | round4),
      recall: ($currentRecall | round4),
      specificity: ($currentSpecificity | round4),
      balancedAccuracy: ((($currentRecall + $currentSpecificity) / 2) | round4),
      truePositives: $currentTp,
      falsePositives: $currentFp,
      trueNegatives: $currentTn,
      falseNegatives: $currentFn
    },
    shadowRouting: $decisionReport.configuredRouting,
    safetyGuard: $decisionReport.safetyGuard,
    currentAverageQuality: ($currentQuality | round4),
    shadowAverageQuality: ($shadowQuality | round4),
    qualityDelta: (($shadowQuality - $currentQuality) | round4),
    currentMeanRegret: ($currentRegret | round4),
    shadowMeanRegret: ($shadowRegret | round4),
    regretReductionRatio: (
      if $currentRegret <= 0.000000001 then 0
      else (($currentRegret - $shadowRegret) / $currentRegret | round4)
      end
    ),
    currentPathCostCny: ($currentCost | round8),
    shadowPathCostCny: ($shadowCost | round8),
    pathCostRatio: (ratio($shadowCost; $currentCost) | round4),
    currentAverageLatencyMs: ($cases | map(.currentLatencyMs) | average | round4),
    shadowAverageLatencyMs: ($cases | map(.shadowLatencyMs) | average | round4),
    averageDecisionLatencyMs: $decisionReport.averageLatencyMs,
    decisionInputTokens: $decisionReport.inputTokens,
    decisionOutputTokens: $decisionReport.outputTokens,
    deterministicGatePassed: (
      ($cases | length) >= 30
      and $decisionReport.availability >= 0.99
      and $decisionReport.configuredRouting.accuracy >= 0.8
      and $decisionReport.configuredRouting.accuracy
        >= ratio($currentTp + $currentTn; $cases | length)
      and $decisionReport.configuredRouting.balancedAccuracy >= 0.7
      and $decisionReport.configuredRouting.recall >= 0.5
      and $decisionReport.safetyGuard.falseNegatives == 0
      and $shadowRegret <= $currentRegret
    ),
    fullReleaseGateEvaluated: false,
    releaseGatePassed: false,
    gateFailures: ([
      if ($cases | length) < 30 then "sample count below 30" else empty end,
      if $decisionReport.availability < 0.99 then "System One availability below 99%" else empty end,
      if $decisionReport.configuredRouting.accuracy < 0.8 then "route accuracy below 80%" else empty end,
      if $decisionReport.configuredRouting.accuracy
          < ratio($currentTp + $currentTn; $cases | length)
        then "route accuracy below current routing" else empty end,
      if $decisionReport.configuredRouting.balancedAccuracy < 0.7 then "balanced accuracy below 70%" else empty end,
      if $decisionReport.configuredRouting.recall < 0.5 then "multi-agent recall below 50%" else empty end,
      if $decisionReport.safetyGuard.falseNegatives > 0 then "safety false negatives present" else empty end,
      if $shadowRegret > $currentRegret then "mean regret exceeds current routing" else empty end,
      "paired quality non-inferiority is not evaluated by this jq replay"
    ]),
    cases: $cases
  }
