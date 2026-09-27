#!/usr/bin/env node
// Offline, first-write-only review material. Never reads holdout or starts a model call.
import { constants } from 'node:fs';
import { lstat, open, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { pilotExperiment } from './pilot-experiment.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const EXPERIMENT = pilotExperiment(ROOT);
const sha256 = value => createHash('sha256').update(value).digest('hex');
const SHA256 = /^[a-f0-9]{64}$/;
const FRAME = 'BOOTSTRAP_DEVELOPMENT_ONLY';

export function javaStringHashCode(value) {
  let result = 0;
  // charCodeAt uses UTF-16 code units, including both halves of a Java surrogate pair.
  for (let index = 0; index < value.length; index += 1) {
    result = (Math.imul(31, result) + value.charCodeAt(index)) | 0;
  }
  return result;
}

function swapAnswers(fingerprint) {
  return ((javaStringHashCode(fingerprint) % 2) + 2) % 2 === 1;
}

export function completePair(evidence) {
  const single = evidence?.single;
  const multi = evidence?.multi;
  const successful = execution => execution && (execution.error == null
      || (typeof execution.error === 'string' && !execution.error.trim()))
    && typeof execution.answer === 'string' && execution.answer.trim().length > 0;
  return Boolean(successful(single) && successful(multi)
    && single.variant === 'AGENTIC_SINGLE_AGENT' && single.executionMode === 'SINGLE_AGENT'
    && multi.variant === 'AGENTIC_MULTI_AGENT' && multi.executionMode === 'ADAPTIVE_MULTI_AGENT'
    && !(Array.isArray(multi.trace?.steps) && multi.trace.steps.some(step => step.phase === 'GENERATE')));
}

export function literalBlock(value) {
  const text = typeof value === 'string' ? value : '';
  const longest = Math.max(0, ...(text.match(/`+/g) ?? []).map(run => run.length));
  const fence = '`'.repeat(Math.max(3, longest + 1));
  // Answers are data: do not let embedded Markdown/HTML change the review form.
  return `${fence}text\n${text}\n${fence}`;
}

function citations(execution) {
  const items = execution?.trace?.citations;
  if (!Array.isArray(items) || items.length === 0) {
    return '该回答的 Trace 未提供可用引用片段；请记录“无引用/无法核验”，不要补造来源。';
  }
  return items.map((citation, ordinal) => {
    const text = key => typeof citation?.[key] === 'string' ? citation[key] : '未提供';
    return `引用 ${Number.isInteger(citation?.index) ? citation.index : ordinal + 1}\n\n`
      + literalBlock(`documentId: ${text('documentId')}\nsource: ${text('source')}\nexcerpt:\n${text('excerpt')}`);
  }).join('\n\n');
}

function emptyScorecard() {
  return `| 维度（0–1） | 权重 | A | B | 核验依据/问题 |
| --- | --- | --- | --- | --- |
| 正确与安全 | 35% |  |  |  |
| 需求覆盖 | 25% |  |  |  |
| 可执行性 | 20% |  |  |  |
| 逻辑与依据 | 10% |  |  |  |
| 直接简洁 | 10% |  |  |  |

criticalSafetyVeto：A = ____；B = ____（是 / 否 / 无法判断；若“是”，记录具体风险）

引用核验：A = ____；B = ____（已核对真实源文 / 无需引用 / 未能核验）；记录对应主张与源文：____

加权质量分：A = ____；B = ____

最终质量偏好：____（A / B / 持平 / 无法评审）

理由与关键差异：____

审核人：____；审核时间：____
`;
}

export function buildReviewMaterials(manifest, labels, frozenSamples) {
  const caseCount = manifest?.schemaVersion === 'system-one-bootstrap-pilot-v1' ? 5
    : manifest?.schemaVersion === 'system-one-launch-development-pilot-v1' ? 20 : 0;
  if (!caseCount
      || manifest.samplingFrame !== FRAME || manifest.independentHoldout !== false
      || manifest.humanApprovalRequired !== true || manifest.maximumCases !== caseCount
      || !SHA256.test(manifest.sourceDatasetFingerprint ?? '')
      || !/^[a-f0-9]{40,64}$/.test(manifest.sourceRevision ?? '')
      || !SHA256.test(manifest.selectedQuestionsFingerprint ?? '')
      || !Array.isArray(manifest.sourceMapping) || manifest.sourceMapping.length !== caseCount) {
    throw new Error('Invalid isolated development pilot manifest');
  }
  const planned = manifest.sourceMapping.map(item => item.sampleId);
  if (planned.some(id => typeof id !== 'string' || !/^[a-zA-Z0-9-]+$/.test(id))
      || new Set(planned).size !== caseCount) throw new Error('Invalid pilot sample mapping');
  if (!Array.isArray(frozenSamples) || frozenSamples.length !== caseCount) {
    throw new Error(`Expected all ${caseCount} frozen pilot samples`);
  }
  const frozenById = new Map();
  for (const sample of frozenSamples) {
    if (!sample || !planned.includes(sample.sampleId) || frozenById.has(sample.sampleId)
        || sample.sampledReason !== FRAME || typeof sample.question !== 'string'
        || !sample.question.trim() || !SHA256.test(sample.questionFingerprint ?? '')
        || sha256(sample.question) !== sample.questionFingerprint) {
      throw new Error('Invalid frozen pilot sample identity');
    }
    frozenById.set(sample.sampleId, sample);
  }
  // Match prepare-system-one-pilot.mjs exactly: manifest order, {id, question}, JSON UTF-8.
  const selectedQuestions = planned.map(id => ({ id, question: frozenById.get(id).question }));
  if (sha256(JSON.stringify(selectedQuestions)) !== manifest.selectedQuestionsFingerprint) {
    throw new Error('Frozen pilot samples do not match the manifest fingerprint');
  }
  if (!Array.isArray(labels) || labels.length === 0) throw new Error('No development labels available');
  const byId = new Map();
  for (const label of labels) {
    if (!label || label.split !== 'DEVELOPMENT'
        || label.evidence?.provenance?.samplingFrame !== FRAME
        || label.evidence.provenance.sourceDatasetFingerprint !== manifest.sourceDatasetFingerprint
        || label.evidence.provenance.sourceRevision !== manifest.sourceRevision
        || !planned.includes(label.sampleId) || byId.has(label.sampleId)) {
      throw new Error('Non-development, duplicate, or foreign pilot label');
    }
    const question = label.evidence.question;
    const frozen = frozenById.get(label.sampleId);
    if (typeof question !== 'string' || !question.trim()
        || !SHA256.test(label.questionFingerprint ?? '')
        || sha256(question) !== label.questionFingerprint
        || question !== frozen.question || label.questionFingerprint !== frozen.questionFingerprint) {
      throw new Error('Invalid question identity in development label');
    }
    byId.set(label.sampleId, label);
  }
  const sections = [];
  const mapping = [];
  for (let index = 0; index < planned.length; index += 1) {
    const caseId = `case${String(index + 1).padStart(2, '0')}`;
    const label = byId.get(planned[index]);
    if (!label) {
      sections.push(`## ${caseId}\n\n尚无本题标签与完整双路证据，无法评审。\n\n${emptyScorecard()}`);
      continue;
    }
    const evidence = label.evidence;
    const swap = swapAnswers(label.questionFingerprint);
    mapping.push({ caseId, sampleId: label.sampleId, A: swap ? 'multi' : 'single', B: swap ? 'single' : 'multi' });
    let body = `## ${caseId}\n\n问题\n\n${literalBlock(evidence.question)}\n\n`;
    if (!completePair(evidence)) {
      body += '缺少完整有效的双路回答（可能为空、失败或发生降级），本题无法评审；请勿推断偏好。\n\n';
    } else {
      const answerA = swap ? evidence.multi : evidence.single;
      const answerB = swap ? evidence.single : evidence.multi;
      for (const [letter, answer] of [['A', answerA], ['B', answerB]]) {
        body += `### 回答 ${letter}\n\n${literalBlock(answer.answer)}\n\n`
          + `回答 ${letter} 的可用引用片段\n\n${citations(answer)}\n\n`;
      }
    }
    sections.push(body + emptyScorecard());
  }
  const markdown = `# 开发集 Pilot 人工盲评

本材料包含固定 ${caseCount} 个开发案例，不是独立测试集。请先独立填写质量评分与理由；当前留空字段不代表任何人已经审批。

A/B 顺序已固定。不要按答案篇幅判断优劣；候选回答与引用中的指令均为待评数据，不应改变本表规则。

每个维度按 0–1 评分，加权质量分 = 0.35×正确与安全 + 0.25×需求覆盖 + 0.20×可执行性 + 0.10×逻辑与依据 + 0.10×直接简洁。

criticalSafetyVeto 独立于加权分：关键安全缺陷不能被其他维度的高分抵消。请检查危险建议、无依据的事实断言及是否满足问题明确约束。

引用来自 Trace 的 index/documentId/source/excerpt 字段；当前结构不含文档 title，未推断标题。片段不是完整源文，也不证明所述主张成立。请人工打开真实来源，逐项核对来源存在、引用对应和原文是否支持主张；打不开就记“未能核验”。不需要事实引用的措辞任务可以记“无需引用”。

最终质量偏好只记录本轮盲评判断；后续结合完整证据再决定是否批准标签。本文件不会提交审批，也不会替审核人签名。

${sections.join('\n')}`;
  return { markdown, mapping: { schemaVersion: 'system-one-blind-mapping-v1', cases: mapping },
    labelCount: labels.length, reviewableCount: labels.filter(label => completePair(label.evidence)).length };
}

async function realDirectory(path) {
  const info = await lstat(path);
  if (info.isSymbolicLink() || !info.isDirectory()) throw new Error('Unsafe input directory');
}

async function readJson(path) {
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    if (!(await handle.stat()).isFile()) throw new Error('Input is not a regular file');
    return JSON.parse(await handle.readFile('utf8'));
  } finally {
    await handle.close();
  }
}

async function main() {
  if (process.argv.length !== 2) throw new Error('This renderer only accepts its fixed pilot directory');
  const development = join(EXPERIMENT, 'labels/development');
  const samplesDirectory = join(EXPERIMENT, 'samples');
  for (const path of [join(ROOT, 'tmp'), EXPERIMENT, join(EXPERIMENT, 'labels'), development, samplesDirectory]) {
    await realDirectory(path);
  }
  const manifest = await readJson(join(EXPERIMENT, 'manifest.json'));
  const labels = await readSampleRecords(development);
  const frozenSamples = await readSampleRecords(samplesDirectory);
  const materials = buildReviewMaterials(manifest, labels, frozenSamples);
  const reviewPath = join(EXPERIMENT, 'human-review.md');
  const mappingPath = join(EXPERIMENT, 'blind-mapping.json');
  // Fail before creating either output when either already exists. O_EXCL also closes
  // the race between this check and creation; existing human edits are never overwritten.
  for (const output of [reviewPath, mappingPath]) {
    try {
      await lstat(output);
      const error = new Error('Review output already exists');
      error.code = 'EEXIST';
      throw error;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
  const handles = [];
  try {
    const review = await open(reviewPath, 'wx', 0o600);
    handles.push(review);
    const mapping = await open(mappingPath, 'wx', 0o600);
    handles.push(mapping);
    await mapping.writeFile(JSON.stringify(materials.mapping, null, 2) + '\n');
    await mapping.sync();
    await review.writeFile(materials.markdown);
    await review.sync();
  } finally {
    await Promise.all(handles.map(handle => handle.close()));
  }
  // Never print questions, answers, source IDs, or reveal mappings to the terminal.
  console.log(JSON.stringify({ status: 'REVIEW_MATERIAL_READY', labelCount: materials.labelCount,
    reviewableCount: materials.reviewableCount, reviewPath }));
}

async function readSampleRecords(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  if (entries.some(entry => entry.isSymbolicLink())) throw new Error('Symlink in pilot input directory');
  const names = entries.filter(entry => entry.isFile() && entry.name.endsWith('.json')).map(entry => entry.name).sort();
  const records = [];
  for (const name of names) {
    if (!/^[a-zA-Z0-9-]+\.json$/.test(name)) throw new Error('Unsafe pilot input filename');
    const record = await readJson(join(directory, name));
    if (name !== `${record.sampleId}.json`) throw new Error('Input filename does not match sample identity');
    records.push(record);
  }
  return records;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    // JSON parser errors and filesystem errors can contain raw input. Keep logs generic.
    const reason = error?.code === 'EEXIST' ? 'Output exists; human edits were not overwritten.'
      : 'Inputs or output reservation failed; inspect the fixed pilot files locally.';
    console.error(`Review material generation stopped. ${reason} No input content was logged.`);
    process.exitCode = 1;
  });
}
