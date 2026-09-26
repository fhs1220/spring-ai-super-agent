(.systemOneRouting.cases
 | map({key: .caseId, value: .oracleMultiAgent})
 | from_entries) as $oracleByCase
| .cases[]
| {
    id: ("frozen-replay-" + .caseId),
    question: .question,
    expectedMultiAgent: $oracleByCase[.caseId],
    expectedSafetyGuard: (.tags | index("safety") != null),
    rationale: "Pre-registered replay label from the prior complete counterfactual run; not used for tuning."
  }
