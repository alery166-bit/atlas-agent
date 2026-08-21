"use client";

import {
  FormEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import type { AnchorHTMLAttributes } from "react";

type LinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & {
  href: string;
  prefetch?: boolean;
};

function Link({ prefetch, ...props }: LinkProps) {
  void prefetch;
  return <a {...props} />;
}

type View =
  | "dialogue"
  | "pending"
  | "companies"
  | "tasks"
  | "reports"
  | "reportDiff"
  | "skills"
  | "dataSources"
  | "searchModels"
  | "riskRules"
  | "reportTemplates"
  | "operations"
  | "audit"
  | "acceptance"
  | "settings";
type Theme = "glacier" | "jade";

const subscribeLocationSearch = () => () => undefined;
const getLocationSearch = () => (typeof window === "undefined" ? "" : window.location.search);
const getServerLocationSearch = () => "";

const DEFAULT_API_BASE =
  process.env.NEXT_PUBLIC_ATLAS_API_BASE?.replace(/\/$/, "") ||
  "http://localhost:8080";

type Conversation = {
  conversation_id: string;
  title: string;
  task_id?: string;
  company_query?: string;
  created_at: string;
  updated_at: string;
};

type SuggestedAction = {
  code: string;
  label: string;
  method: "GET" | "POST";
  endpoint: string;
};

type StoredMessage = {
  message_id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  response_type?: string;
  parsed_intent?: string;
  company_query?: string;
  task_id?: string;
  required_inputs: Array<{ code: string; label: string; required: boolean }>;
  suggested_actions: SuggestedAction[];
  created_at: string;
};

type Workspace = {
  task: {
    task_id: string;
    task_no: string;
    status: string;
    company_query: string;
    updated_at: string;
  };
  subject_data_conflicts: Array<{
    code: string;
    field_name: string;
    master_value: string;
    latest_change_value: string;
    changed_at?: string;
    source_system?: string;
    source_record_id?: string;
  }>;
  subject_data_conflict_resolution?: {
    resolution_id: string;
    data_snapshot_id: string;
    decision: "ACCEPT_MASTER";
    note: string;
    operator_id: string;
    resolved_at: string;
  };
  risk_score?: {
    score_snapshot_id: string;
    legacy_score?: number;
    rule_calculated_score?: number;
    original_score: number;
    manual_score: number;
    original_risk_level: string;
    manual_risk_level: string;
    event_floor_score?: number;
    rule_version?: string;
    engine_version?: string;
    rule_hits?: Array<{
      rule_code: string;
      rule_name: string;
      risk_type: string;
      score: number;
      score_role: string;
      references: string[];
    }>;
  };
  evidence_progress: {
    total: number;
    confirmed: number;
    rejected: number;
    unverified: number;
    captured_content: number;
  };
  confirmation_state: string;
  readiness_blockers: string[];
  next_action: string;
  reports: ReportVersion[];
  steps: Array<{
    step_name: string;
    status: string;
    attempt_no?: number;
    error_message?: string;
  }>;
};

type AssessmentLabel = {
  label_code?: string;
  label_name: string;
  risk_type: string;
  source_type: string;
  score_contribution?: number;
  confidence?: number;
  references: string[];
};

type AssessmentRevision = {
  assessment_revision_id: string;
  score_snapshot_id: string;
  revision_no: number;
  trigger_type: "SYSTEM_CALCULATION" | "MANUAL_SCORE_ADJUSTMENT";
  legacy_score?: number;
  rule_calculated_score: number;
  event_floor_score: number;
  original_score: number;
  final_score: number;
  original_risk_level: string;
  final_risk_level: string;
  rule_version: string;
  engine_version: string;
  source_labels: AssessmentLabel[];
  rule_labels: AssessmentLabel[];
  model_labels: AssessmentLabel[];
  final_labels: AssessmentLabel[];
  actor_type: string;
  actor_id: string;
  reason_code: string;
  reason_text: string;
  created_at: string;
};

type AgentResponse = {
  conversation_id: string;
  message_id: string;
  type: string;
  assistant_message: string;
  parsed_intent: string;
  company_query?: string;
  workspace?: Workspace;
  required_inputs: StoredMessage["required_inputs"];
  suggested_actions: SuggestedAction[];
};

type TaskListItem = {
  task: Workspace["task"] & {
    original_prompt?: string;
    operator_id?: string;
    created_at?: string;
  };
  risk?: Workspace["risk_score"];
  evidence_progress: Workspace["evidence_progress"];
  confirmation_state: string;
  readiness_blockers: string[];
  next_action: string;
  latest_report?: ReportVersion;
};

type TaskPage = {
  items: TaskListItem[];
  next_cursor?: string;
};

type ReportVersion = {
  report_id: string;
  report_version_no: number;
  status: string;
  generated_at?: string;
  content_hash?: string;
};

type ReportDiff = {
  company_changes: Array<{ field: string; before_value?: string; after_value?: string }>;
  section_changes?: Array<{ field: string; before_value?: string; after_value?: string }>;
  table_row_changes?: Array<{ field: string; before_value?: string; after_value?: string }>;
  conclusion_changes?: Array<{ field: string; before_value?: string; after_value?: string }>;
  previous_report_date?: string;
  current_report_date: string;
  summary: string;
  previous_report_version_no?: number;
  current_report_version_no: number;
};

type Evidence = {
  evidence_id: string;
  risk_type: string;
  title: string;
  snippet?: string;
  source_provider: string;
  source_url?: string;
  verification_status: string;
  entity_match_status: string;
  captured_at: string;
  metadata?: Record<string, string>;
};

type EvidenceModelReviewJob = {
  review_job_id: string;
  task_id: string;
  status:
    | "QUEUED"
    | "RUNNING"
    | "CANCEL_REQUESTED"
    | "SUCCEEDED"
    | "PARTIAL_FAILED"
    | "FAILED"
    | "CANCELLED";
  total_count: number;
  processed_count: number;
  reviewed_count: number;
  failed_count: number;
  provider?: string;
  model?: string;
  model_call_count: number;
  prompt_token_count: number;
  completion_token_count: number;
  total_token_count: number;
  model_suggested_score?: number;
  model_suggested_risk_level?: string;
  model_score_evidence_ids: string[];
  advisory_rule_version?: string;
  error_message?: string;
  cancel_requested: boolean;
  created_at: string;
  started_at?: string;
  finished_at?: string;
  updated_at: string;
};

type CompanyAlias = {
  alias_id: string;
  alias_name: string;
  alias_type: string;
  relation: string;
  verification_status: string;
  source_system: string;
  source_evidence?: string;
  created_by?: string;
};

type CompanyCandidate = {
  source_system: string;
  source_entity_id: string;
  canonical_name: string;
  unified_credit_code?: string;
  registration_no?: string;
  legal_representative?: string;
  registration_status?: string;
  registered_address?: string;
  confidence?: number;
  data_as_of?: string;
  attributes?: Record<string, string>;
};

type CompanyResolution = {
  status: string;
  candidates: CompanyCandidate[];
  source_statuses: Array<{
    source_system: string;
    source_name: string;
    query_status: string;
    record_count: number;
    data_as_of?: string;
    fetched_at: string;
    error_code?: string;
    message?: string;
  }>;
};

type RuntimeComponent = {
  code: string;
  name: string;
  state: string;
  configured: boolean;
  details: Record<string, string>;
};

type SearchProviderStatus = {
  name: string;
  mode: string;
  state: string;
  configured: boolean;
  details: Record<string, string>;
};

type RuntimeCapabilities = {
  service_status: string;
  data_provider: RuntimeComponent;
  search_providers: SearchProviderStatus[];
  agent_model: RuntimeComponent;
  risk_scoring: RuntimeComponent;
  report_generation: RuntimeComponent;
};

type OperationsSnapshot = {
  from: string;
  to: string;
  total_tasks: number;
  completed_tasks: number;
  active_tasks: number;
  waiting_tasks: number;
  stalled_tasks: number;
  failed_tasks: number;
  activity_threshold_minutes: number;
  average_duration_millis?: number;
  search_calls: number;
  model_calls?: number;
  model_call_state: string;
  model_prompt_tokens: number;
  model_completion_tokens: number;
  model_total_tokens: number;
  generated_reports: number;
  failed_reports: number;
  throughput: Array<{ date: string; created: number; completed: number; failed: number }>;
  stalled: Array<{
    task_id: string;
    task_no: string;
    enterprise_name: string;
    status: string;
    current_step?: string;
    created_at: string;
    updated_at: string;
    stalled_minutes: number;
  }>;
  failures: Array<{
    task_id: string;
    task_no: string;
    enterprise_name: string;
    status: string;
    failed_step?: string;
    error_code?: string;
    created_at: string;
    updated_at: string;
    search_calls: number;
    model_calls?: number;
    report_status?: string;
    retryable: boolean;
  }>;
};

type AuditEntry = {
  event_id: string;
  event_type: string;
  action: string;
  task_id?: string;
  task_no?: string;
  enterprise_name?: string;
  operator_id?: string;
  actor_type: string;
  target_type: string;
  target_id: string;
  before_json?: string;
  after_json?: string;
  detail?: string;
  trace_id?: string;
  occurred_at: string;
};

type ConfigurationChange = {
  release_id: string;
  config_key: string;
  display_name: string;
  environment: string;
  action: string;
  from_version_no?: number;
  before_json?: string;
  to_version_no: number;
  after_json: string;
  operator_id: string;
  occurred_at: string;
};

type GoldenAcceptanceRun = {
  run_id: string;
  suite_id: string;
  status: "PASSED" | "FAILED" | "INCOMPLETE";
  case_count: number;
  completed_case_count: number;
  severe_subject_mismatch_count: number;
  major_risk_count: number;
  supported_major_risk_count: number;
  explainable_score_count: number;
  docx_pass_count: number;
  critical_defect_count: number;
  high_defect_count: number;
  average_manual_minutes?: number;
  created_at: string;
};

type GoldenSuiteSummary = {
  suite_id: string;
  name: string;
  status: "DRAFT" | "READY";
  case_count: number;
  confirmed_case_count: number;
  verified_artifact_case_count: number;
  content_hash: string;
  created_by: string;
  created_at: string;
  latest_run?: GoldenAcceptanceRun;
};

type GoldenSuiteDetail = {
  suite: GoldenSuiteSummary;
  manifest: {
    schema_version: string;
    cases: Array<{
      id: string;
      business_confirmed: boolean;
      company: { canonical_name: string; unified_credit_code: string; identity_terms: string[] };
      expected: { original_score: string; manual_score: string; risk_level: string };
      evidence_labels: Array<{ major_risk: boolean; include_in_report: boolean }>;
    }>;
  };
  runs: GoldenAcceptanceRun[];
};

type ConfigurationVersionView = {
  version_id: string;
  version_no: number;
  status: "DRAFT" | "VALIDATED" | "PUBLISHED" | "INACTIVE";
  value_json: string;
  checksum: string;
  validation_message?: string;
  created_by: string;
  created_at: string;
  row_version: number;
};

type RiskRuleReplay = {
  replay_id: string;
  version_checksum: string;
  status: "PASSED" | "FAILED";
  sample_count: number;
  passed_count: number;
  score_changed_count: number;
  level_changed_count: number;
  max_score_delta: number;
  created_at: string;
};

type RiskPolicyDocument = {
  schema_version: string;
  name: string;
  thresholds: {
    high_min: number;
    medium_high_min: number;
    medium_min: number;
    medium_low_min: number;
  };
  event_floors: Array<{
    risk_type: string;
    minimum_score: number;
    enabled: boolean;
    evidence_required: boolean;
  }>;
  rule_weights: Record<string, number>;
  risk_labels: Array<{
    legacy_label_no: string;
    category: string;
    evidence_requirement: string;
    priority: number;
    enabled: boolean;
  }>;
  time_windows: { risk_event_days: number; company_change_days: number };
  replay_gate: {
    minimum_samples: number;
    max_score_delta: number;
    allow_level_changes: boolean;
  };
};

type RiskRuleOverview = {
  default_policy_json: string;
  policies: Array<{
    configuration: {
      definition: { config_key: string; display_name: string; description: string };
      versions: ConfigurationVersionView[];
      binding?: { active_version_id: string; environment: string };
    };
    version_impacts: Array<{
      version_id: string;
      latest_replay?: RiskRuleReplay;
      task_usage_count: number;
    }>;
  }>;
};

type LegacyRiskTraceability = {
  current_runtime_mode: string;
  current_runtime_description: string;
  fact_scoring_catalog: Array<{
    risk_name: string;
    risk_type: string;
    primary_source: string;
    recognition_condition: string;
    time_window: string;
    score_handling: string;
    runtime_state: string;
  }>;
  risk_dictionary: Array<{
    legacy_name: string;
    legacy_label_no: string;
    label_name: string;
    canonical_type: string;
    migration_status: string;
    runtime_handling: string;
    source_evidence: string;
  }>;
  active_hard_coded_labels: Array<{
    legacy_label_no: string;
    label_name: string;
    priority_score: string;
    scoring_profiles: string;
    source_evidence: string;
    note: string;
  }>;
  feature_requirements: Array<{
    feature_name: string;
    legacy_source: string;
    atlas_source: string;
    readiness: string;
    required_for_full_recalculation: boolean;
    note: string;
  }>;
  calculation_rules: Array<{
    rule_code: string;
    legacy_source: string;
    new_implementation: string;
    migration_status: string;
    configurable: boolean;
    runtime_condition: string;
    note: string;
  }>;
};

type ConnectorDocument = {
  schema_version: string;
  category: "DATA_SOURCE" | "SEARCH" | "MODEL";
  kind: string;
  enabled: boolean;
  required: boolean;
  failure_policy: "STOP" | "OPTIONAL";
  credential_ref?: string;
  endpoint: { base_url: string; path: string; connect_timeout_ms: number; request_timeout_ms: number };
  retry: { max_attempts: number; backoff_ms: number };
  indices?: Record<string, string>;
  field_mapping?: Record<string, string>;
  settings: Record<string, unknown>;
};

type ConnectorTestView = {
  test_id: string;
  version_id: string;
  version_checksum: string;
  status: "PASSED" | "FAILED";
  latency_ms: number;
  message: string;
  preview_json?: string;
  created_at: string;
};

type ConnectorOverviewView = {
  configuration: {
    definition: {
      config_key: string;
      category: "DATA_SOURCE" | "SEARCH" | "MODEL";
      display_name: string;
      description: string;
    };
    versions: ConfigurationVersionView[];
    binding?: { active_version_id: string; environment: string };
  };
  test_impacts: Array<{ version_id: string; latest_test?: ConnectorTestView }>;
};

type ConfigurationOverviewView = {
  definition: {
    config_key: string;
    category: string;
    display_name: string;
    description?: string;
  };
  versions: ConfigurationVersionView[];
  binding?: { active_version_id: string; environment: string };
};

type SkillDocument = {
  schema_version: "atlas-skill.v1";
  skill_key: string;
  executor_key: string;
  enabled: boolean;
  failure_policy: "STOP" | "OPTIONAL";
  input_contract: string[];
  output_contract: string[];
  parameters: Record<string, string | number | boolean>;
  dependencies: Array<{
    config_key: string;
    category: string;
    required: boolean;
  }>;
};

type ReportTemplateDocument = {
  schema_version: "atlas-report-template.v1";
  enabled: boolean;
  format: "DOCX";
  artifact_id: string;
  original_filename: string;
  template_version: string;
  content_hash: string;
  field_mapping: Record<string, string>;
  dependencies: Array<{
    config_key: string;
    category: string;
    required: boolean;
  }>;
};

type ReportTemplatePreview = {
  template_version: string;
  original_filename: string;
  content_hash: string;
  field_mapping: Record<string, string>;
  inspection: {
    valid: boolean;
    paragraph_count: number;
    table_count: number;
    detected_markers: string[];
    missing_markers: string[];
    message: string;
  };
};

type NavigationItem = { view: View; href: string; label: string; index: string };

const PRIMARY_NAV: NavigationItem[] = [
  { view: "dialogue", href: "/", label: "Atlas 工作台", index: "⌁" },
  { view: "pending", href: "/pending", label: "待处理", index: "!" },
  { view: "tasks", href: "/tasks", label: "任务记录", index: "◷" },
  { view: "reports", href: "/reports", label: "报告", index: "↓" },
];

const MANAGEMENT_NAV: NavigationItem[] = [
  { view: "skills", href: "/skills", label: "Skills", index: "A" },
  { view: "dataSources", href: "/data-sources", label: "数据源", index: "B" },
  { view: "searchModels", href: "/search-models", label: "搜索与模型", index: "C" },
  { view: "riskRules", href: "/risk-rules", label: "规则评分", index: "D" },
  { view: "reportTemplates", href: "/report-templates", label: "报告模板", index: "E" },
  { view: "operations", href: "/operations", label: "运行监控", index: "F" },
  { view: "audit", href: "/audit", label: "审计日志", index: "G" },
  { view: "acceptance", href: "/acceptance", label: "验收评估", index: "H" },
];

const SETTINGS_NAV: NavigationItem = {
  view: "settings",
  href: "/settings",
  label: "系统设置",
  index: "·",
};

const NAV = [...PRIMARY_NAV, ...MANAGEMENT_NAV, SETTINGS_NAV];

const MANAGEMENT_VIEWS = new Set<View>(MANAGEMENT_NAV.map((item) => item.view));

const STATUS_LABELS: Record<string, string> = {
  CREATED: "待执行",
  RESOLVING_SUBJECT: "识别企业主体",
  WAITING_SUBJECT_CONFIRMATION: "待确认主体",
  WAITING_SUBJECT_DATA_REVIEW: "待核对主体数据",
  LOADING_PREVIOUS_REPORT: "准备企业数据（历史任务）",
  COLLECTING_STRUCTURED_DATA: "采集企业数据",
  SEARCHING_PUBLIC_INTELLIGENCE: "检索公开信息",
  CALCULATING_RISK: "待计算风险分",
  WAITING_OPERATOR_CONFIRMATION: "待运营确认",
  GENERATING_REPORT: "生成报告中",
  COMPLETED: "已完成",
  SOURCE_FAILED: "数据查询失败",
  MODEL_FAILED: "模型处理失败",
  REPORT_FAILED: "报告生成失败",
  CANCELLED: "已取消",
};

const ACTION_LABELS: Record<string, string> = {
  EXECUTE_TASK: "继续执行",
  CONFIRM_SUBJECT: "确认企业主体",
  REVIEW_EVIDENCE: "处理证据",
  REVIEW_SUBJECT_DATA: "核对主体数据",
  CALCULATE_RISK: "计算风险分",
  CONFIRM_REVIEW: "确认研判",
  GENERATE_REPORT: "生成报告",
  RETRY_TASK: "重试任务",
  RETRY_REPORT: "重试报告",
  DOWNLOAD_REPORT: "下载报告",
  WAIT: "等待处理",
  NONE: "暂无操作",
};

const READINESS_LABELS: Record<string, string> = {
  DATA_SNAPSHOT_MISSING: "企业数据尚未冻结",
  RISK_SCORE_MISSING: "风险分尚未计算",
  RISK_SCORE_NOT_FROM_LATEST_DATA: "风险分与最新企业数据不一致",
  UNVERIFIED_EVIDENCE: "仍有公开证据待处理",
  EVIDENCE_REVIEW_INCOMPLETE: "证据尚未处理完",
  OPERATOR_CONFIRMATION_REQUIRED: "尚未完成运营确认",
  OPERATOR_CONFIRMATION_STALE: "数据变化后需要重新确认",
  SUBJECT_DATA_CONFLICT: "企业主档与工商变更存在关键字段冲突，需人工核验",
};

const STEP_LABELS: Record<string, string> = {
  RESOLVE_SUBJECT: "识别企业主体",
  VALIDATE_PREVIOUS_REPORT_REFERENCE: "历史任务兼容步骤",
  LOAD_PREVIOUS_REPORT: "历史任务兼容步骤",
  COLLECT_STRUCTURED_DATA: "采集工商与经营数据",
  SEARCH_PUBLIC_INTELLIGENCE: "检索公开负面信息",
  CALCULATE_RISK: "计算风险分",
  GENERATE_REPORT: "生成正式报告",
};

const ADJUSTMENT_REASONS = [
  ["EVIDENCE_CORRECTION", "证据纠正"],
  ["ENTITY_MISMATCH", "主体匹配偏差"],
  ["EVENT_RESOLVED", "风险事件已解决"],
  ["ADDITIONAL_CONTEXT", "补充业务背景"],
  ["RULE_LIMITATION", "规则覆盖不足"],
  ["OTHER", "其他原因"],
] as const;

const ALIAS_TYPES = [
  ["SHORT_NAME", "企业简称"],
  ["FORMER_NAME", "曾用名"],
  ["BRAND", "品牌名"],
  ["STORE", "门店名"],
  ["WEBSITE", "网站名"],
  ["SOCIAL_ACCOUNT", "自媒体名"],
] as const;

const ALIAS_TYPE_LABELS: Record<string, string> = {
  LEGAL_NAME: "企业全称",
  ...Object.fromEntries(ALIAS_TYPES),
};

const ALIAS_RELATIONS = [
  ["SAME_LEGAL_ENTITY", "同一法人主体"],
  ["FORMER_IDENTITY", "历史主体名称"],
  ["OWNED_BRAND", "自有品牌"],
  ["OPERATED_STORE", "直营/运营门店"],
  ["FRANCHISE_STORE", "加盟门店"],
  ["OTHER", "其他已核实关系"],
] as const;

const TASK_EVENT_TYPES = [
  "task.status.changed",
  "step.started",
  "step.completed",
  "step.failed",
  "operator.action.required",
  "data.snapshot.frozen",
  "company.alias.confirmed",
  "public.intelligence.collected",
  "public.intelligence.evidence.decided",
  "risk.score.calculated",
  "risk.score.adjusted",
  "operator.confirmation.completed",
  "report.generating",
  "report.generated",
  "report.failed",
];

function storageValue(key: string, fallback: string) {
  if (typeof window === "undefined") return fallback;
  return window.localStorage.getItem(key) || fallback;
}

function formatTime(value?: string) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function displayAttribute(value?: string) {
  const normalized = value?.trim();
  if (!normalized || normalized === "[]" || normalized === "{}" || normalized === "null") return "/";
  return normalized;
}

function newClientId() {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof cryptoApi?.getRandomValues === "function") {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

function formatDuration(value?: number) {
  if (value === undefined) return "未知";
  if (value < 1000) return `${value} 毫秒`;
  if (value < 60000) return `${(value / 1000).toFixed(1)} 秒`;
  return `${(value / 60000).toFixed(1)} 分钟`;
}

function scoreLevel(score?: number) {
  if (score === undefined) return "未评分";
  if (score >= 8) return "高风险";
  if (score >= 6) return "中高风险";
  if (score >= 4) return "中风险";
  if (score >= 2) return "中低风险";
  return "低风险";
}

function investigationConclusion(
  status: string,
  risk?: Workspace["risk_score"],
  evidence?: Workspace["evidence_progress"],
) {
  if (["SOURCE_FAILED", "MODEL_FAILED", "REPORT_FAILED"].includes(status)) {
    return {
      label: "排查未完成",
      detail: "必查环节失败，不能形成未发现风险的结论",
    };
  }
  if (!risk) {
    return {
      label: "待形成结论",
      detail: "完成数据查询、证据处理和评分后形成正式排查结论",
    };
  }
  if (risk.manual_score === 0 && (evidence?.confirmed ?? 0) === 0) {
    return {
      label: "暂未发现明确风险",
      detail: "必查流程已完成，当前未纳入经研判确认的风险证据",
    };
  }
  return {
    label: scoreLevel(risk.manual_score),
    detail: `发现需关注事项，当前人工分 ${risk.manual_score}`,
  };
}

export function AtlasConsole({ initialView }: { initialView: View }) {
  const [theme, setTheme] = useState<Theme>("glacier");
  const [operatorId, setOperatorId] = useState("local-operator");
  const [apiBase, setApiBase] = useState(DEFAULT_API_BASE);
  const [notice, setNotice] = useState<string>();
  const [backendOnline, setBackendOnline] = useState<boolean>();

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setTheme(storageValue("atlas-theme", "glacier") as Theme);
      setOperatorId(storageValue("atlas-operator", "local-operator"));
      setApiBase(storageValue("atlas-api-base", DEFAULT_API_BASE));
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const api = useCallback(
    async <T,>(path: string, options: RequestInit = {}): Promise<T> => {
      const requestApiBase = storageValue("atlas-api-base", apiBase);
      const requestOperator = storageValue("atlas-operator", operatorId);
      const response = await fetch(`${requestApiBase.replace(/\/$/, "")}${path}`, {
        ...options,
        headers: {
          "X-Operator-Id": requestOperator,
          ...(options.headers || {}),
        },
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || `请求失败（${response.status}）`);
      }
      if (response.status === 204) return undefined as T;
      const type = response.headers.get("content-type") || "";
      if (!type.includes("json")) return (await response.blob()) as T;
      return response.json();
    },
    [apiBase, operatorId],
  );

  useEffect(() => {
    let active = true;
    const requestApiBase = storageValue("atlas-api-base", apiBase);
    fetch(`${requestApiBase.replace(/\/$/, "")}/actuator/health`)
      .then((response) => {
        if (active) setBackendOnline(response.ok);
      })
      .catch(() => {
        if (active) setBackendOnline(false);
      });
    return () => {
      active = false;
    };
  }, [apiBase]);

  function savePreferences(next: {
    theme: Theme;
    operatorId: string;
    apiBase: string;
  }) {
    setTheme(next.theme);
    setOperatorId(next.operatorId);
    setApiBase(next.apiBase);
    window.localStorage.setItem("atlas-theme", next.theme);
    window.localStorage.setItem("atlas-operator", next.operatorId);
    window.localStorage.setItem("atlas-api-base", next.apiBase);
    setNotice("设置已保存在当前浏览器");
  }

  return (
    <div className={`atlas-app theme-${theme}`}>
      <aside className="control-sidebar">
        <Link className="brand" href="/" prefetch={false} aria-label="Atlas 首页">
          <span className="brand-symbol">A</span>
          <span>
            <strong>ATLAS</strong>
            <small>企业风险研判</small>
          </span>
        </Link>
        <div className="sidebar-scroll">
          <section className="nav-section">
            <span className="nav-section-label">运营工作区</span>
            <nav aria-label="业务导航">
            {PRIMARY_NAV.map((item) => (
              <Link
                key={item.view}
                className={initialView === item.view ? "active" : ""}
                href={item.href}
                prefetch={false}
              >
                <span className="nav-index">{item.index}</span>
                <strong>{item.label}</strong>
              </Link>
            ))}
            </nav>
          </section>
          <details
            className="management-menu"
            open={MANAGEMENT_VIEWS.has(initialView) || initialView === "settings"}
          >
            <summary>
              <span className="management-menu-icon">⌘</span>
              <strong>系统管理</strong>
              <small>数据、规则与运行配置</small>
            </summary>
            <nav aria-label="系统管理导航">
              {MANAGEMENT_NAV.map((item) => (
                <Link
                  key={item.view}
                  className={initialView === item.view ? "active" : ""}
                  href={item.href}
                  prefetch={false}
                >
                  <strong>{item.label}</strong>
                </Link>
              ))}
              <Link
                className={initialView === "settings" ? "active" : ""}
                href={SETTINGS_NAV.href}
                prefetch={false}
              >
                <strong>{SETTINGS_NAV.label}</strong>
              </Link>
            </nav>
          </details>
        </div>
        <div className="sidebar-footer">
          <div className="sidebar-service">
            <span className={`status-dot ${backendOnline ? "online" : "offline"}`} />
            <div><strong>{backendOnline ? "服务运行正常" : "等待后端服务"}</strong><small>{operatorId}</small></div>
          </div>
        </div>
      </aside>

      <main className="main-shell">
        <header className="command-header">
          <div>
            <span className="breadcrumb">Atlas / {MANAGEMENT_VIEWS.has(initialView) || initialView === "settings" ? "系统管理" : "智能研判"}</span>
            <h1>{initialView === "reportDiff" ? "报告版本差异" : NAV.find((item) => item.view === initialView)?.label}</h1>
          </div>
          <div className="header-utility">
            <span className={`service-chip ${backendOnline ? "online" : "offline"}`}><i />{backendOnline ? "在线" : "离线"}</span>
            <button
              className="theme-switch"
              type="button"
              onClick={() => {
                const next = theme === "glacier" ? "jade" : "glacier";
                savePreferences({ theme: next, operatorId, apiBase });
              }}
            >
              {theme === "glacier" ? "切换墨玉" : "切换冰川"}
            </button>
            <span className="operator-avatar" title={operatorId}>{operatorId.slice(0, 1).toUpperCase()}</span>
          </div>
        </header>

        {notice && (
          <div className="toast" role="status" onClick={() => setNotice(undefined)}>
            {notice}
          </div>
        )}

        {initialView === "dialogue" && (
          <DialogueView api={api} apiBase={apiBase} notify={setNotice} />
        )}
        {initialView === "companies" && (
          <CompanyLookupView api={api} notify={setNotice} />
        )}
        {initialView === "pending" && (
          <TasksView
            api={api}
            apiBase={apiBase}
            operatorId={operatorId}
            notify={setNotice}
            mode="pending"
          />
        )}
        {initialView === "tasks" && (
          <TasksView
            api={api}
            apiBase={apiBase}
            operatorId={operatorId}
            notify={setNotice}
            mode="all"
          />
        )}
        {initialView === "reports" && (
          <ReportsView api={api} operatorId={operatorId} apiBase={apiBase} />
        )}
        {initialView === "reportDiff" && <ReportDiffView api={api} />}
        {MANAGEMENT_VIEWS.has(initialView) && (
          <ManagementView
            section={initialView}
            api={api}
            notify={setNotice}
            operatorId={operatorId}
          />
        )}
        {initialView === "settings" && (
          <SettingsView
            apiBase={apiBase}
            operatorId={operatorId}
            theme={theme}
            backendOnline={backendOnline}
            onSave={savePreferences}
          />
        )}
      </main>
    </div>
  );
}

function DialogueView({
  api,
  apiBase,
  notify,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  apiBase: string;
  notify: (message: string) => void;
}) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [messages, setMessages] = useState<StoredMessage[]>([]);
  const [draft, setDraft] = useState(() => {
    if (typeof window === "undefined") return "";
    const company = new URLSearchParams(window.location.search).get("company")?.trim();
    return company ? `排查${company}并生成风险报告` : "";
  });
  const [busy, setBusy] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(true);
  const messageEnd = useRef<HTMLDivElement>(null);

  const loadConversations = useCallback(async () => {
    try {
      const items = await api<Conversation[]>("/api/agent/conversations");
      setConversations(items);
      setSelectedId((current) => current || items[0]?.conversation_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setLoadingHistory(false);
    }
  }, [api, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadConversations();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadConversations]);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      if (!selectedId) {
        setMessages([]);
        return;
      }
      void api<StoredMessage[]>(
        `/api/agent/conversations/${selectedId}/messages`,
      )
        .then((items) => {
          if (active) setMessages(items);
        })
        .catch((error) => {
          if (active) notify((error as Error).message);
        });
    }, 0);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [api, notify, selectedId]);

  useEffect(() => {
    messageEnd.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy]);

  function newConversation() {
    setSelectedId(undefined);
    setMessages([]);
    setDraft("");
  }

  async function ensureConversation() {
    if (selectedId) return selectedId;
    const conversation = await api<Conversation>("/api/agent/conversations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: draft.slice(0, 40) || "新建企业风险排查",
      }),
    });
    setConversations((items) => [conversation, ...items]);
    setSelectedId(conversation.conversation_id);
    return conversation.conversation_id;
  }

  async function send(event?: FormEvent) {
    event?.preventDefault();
    const text = draft.trim();
    if (!text || busy) return;
    if (!selectedId) {
      const existing = conversations.find((item) => {
        const company = item.company_query?.replace(/\s+/g, "");
        return company && text.replace(/\s+/g, "").includes(company);
      });
      if (existing && !window.confirm(
        `该企业已有一次研判对话（${formatTime(existing.updated_at)}）。\n\n确定：新建一次独立排查\n取消：返回已有对话`,
      )) {
        setSelectedId(existing.conversation_id);
        return;
      }
    }
    setBusy(true);
    setDraft("");
    try {
      const conversationId = await ensureConversation();
      const optimistic: StoredMessage = {
        message_id: newClientId(),
        role: "USER",
        content: text,
        required_inputs: [],
        suggested_actions: [],
        created_at: new Date().toISOString(),
      };
      setMessages((items) => [...items, optimistic]);
      const response = await api<AgentResponse>(
        `/api/agent/conversations/${conversationId}/messages`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": newClientId(),
          },
          body: JSON.stringify({ message: text }),
        },
      );
      setMessages((items) => [
        ...items,
        {
          message_id: response.message_id,
          role: "ASSISTANT",
          content: response.assistant_message,
          response_type: response.type,
          parsed_intent: response.parsed_intent,
          company_query: response.company_query,
          task_id: response.workspace?.task.task_id,
          required_inputs: response.required_inputs,
          suggested_actions: response.suggested_actions,
          created_at: new Date().toISOString(),
        },
      ]);
      await loadConversations();
    } catch (error) {
      notify((error as Error).message);
      setDraft(text);
    } finally {
      setBusy(false);
    }
  }

  async function archiveConversation(item: Conversation) {
    const taskNote = item.task_id
      ? "对应任务、审计记录和已生成报告会继续保留。"
      : "该操作只移除这条空对话。";
    if (!window.confirm(`从对话列表移除“${item.company_query || item.title}”？\n${taskNote}`)) return;
    try {
      await api<void>(`/api/agent/conversations/${item.conversation_id}`, { method: "DELETE" });
      const remaining = conversations.filter((candidate) => candidate.conversation_id !== item.conversation_id);
      setConversations(remaining);
      if (selectedId === item.conversation_id) {
        setSelectedId(remaining[0]?.conversation_id);
        setMessages([]);
      }
      notify("对话已从列表移除，任务和报告仍然保留");
    } catch (error) {
      notify((error as Error).message);
    }
  }

  return (
    <section className="dialogue-layout">
      <aside className="conversation-list">
        <button className="primary wide" type="button" onClick={newConversation}>
          ＋ 新建对话
        </button>
        <div className="history-label">最近任务对话</div>
        {loadingHistory && <div className="muted-line">正在读取…</div>}
        {!loadingHistory && conversations.length === 0 && (
          <div className="empty-rail">还没有对话，直接在右侧输入任务即可。</div>
        )}
        {conversations.map((item) => {
          const sameCompanyCount = item.company_query
            ? conversations.filter((candidate) => candidate.company_query === item.company_query).length
            : 0;
          return (
            <div className={`conversation-item ${item.conversation_id === selectedId ? "selected" : ""}`} key={item.conversation_id}>
              <button type="button" className="conversation-open" onClick={() => setSelectedId(item.conversation_id)}>
                <span className="conversation-title">{item.company_query || item.title}</span>
                <span className="conversation-meta">
                  {item.task_id ? "已创建任务" : "待创建任务"} · {formatTime(item.updated_at)}
                  {sameCompanyCount > 1 ? ` · 同企业 ${sameCompanyCount} 次` : ""}
                </span>
              </button>
              <button type="button" className="conversation-delete" title="移除对话" aria-label={`移除${item.company_query || item.title}对话`} onClick={() => void archiveConversation(item)}>×</button>
            </div>
          );
        })}
      </aside>

      <div className="conversation-stage">
        <div className="chat-scroll">
          {messages.length === 0 && (
            <div className="welcome-panel">
              <div className="agent-orbit">
                <span>A</span>
              </div>
              <span className="eyebrow">ATLAS AUTONOMOUS RESEARCH</span>
              <h2>说出企业，剩下的交给 Atlas</h2>
              <p>
                我会自主读取企业数据、调查公开信息、研判风险并生成正式报告。正常过程无需逐步点击，只有无法可靠判断的异常才会请你处理。
              </p>
              <div className="agent-promise" aria-label="Atlas 工作方式">
                <span><i />自动连续执行</span>
                <span><i />结论保留证据</span>
                <span><i />异常才找人工</span>
              </div>
              <div className="prompt-examples">
                {[
                  "生成北京童程童慧科技有限公司的风险报告",
                  "排查乾道投资控股集团有限公司，重点关注经营异常",
                  "分析北京全时叁陆伍的失联、欠薪和闭店风险",
                ].map((text) => (
                  <button key={text} type="button" onClick={() => setDraft(text)}>
                    {text}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((message) => (
            <article
              className={`message ${message.role.toLowerCase()} ${message.task_id ? "has-task" : ""}`}
              key={message.message_id}
            >
              <div className="message-avatar">
                {message.role === "ASSISTANT" ? "A" : "我"}
              </div>
              <div className="message-content">
                <div className="message-head">
                  <strong>
                    <span className="message-role-mark">
                      {message.role === "ASSISTANT" ? "AI" : "指令"}
                    </span>
                    {message.role === "ASSISTANT" ? "Atlas Agent" : "运营人员"}
                  </strong>
                  <time>{formatTime(message.created_at)}</time>
                </div>
                <p>{message.content}</p>
                {message.required_inputs?.length > 0 && (
                  <div className="input-request">
                    <span>还需要：</span>
                    {message.required_inputs.map((input) => (
                      <strong key={input.code}>{input.label}</strong>
                    ))}
                  </div>
                )}
                {message.task_id && (
                  <InlineTaskCard
                    taskId={message.task_id}
                    api={api}
                    apiBase={apiBase}
                    notify={notify}
                  />
                )}
              </div>
            </article>
          ))}
          {busy && (
            <div className="agent-thinking">
              <span />
              <span />
              <span />
              Atlas 正在处理
            </div>
          )}
          <div ref={messageEnd} />
        </div>

        <form className="composer" onSubmit={send}>
          <textarea
            aria-label="输入企业风险排查任务"
            value={draft}
            placeholder="输入任务，例如：生成北京简熹和食品有限公司的风险报告，重点核实失联、欠薪和闭店情况…"
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                send();
              }
            }}
          />
          <div className="composer-toolbar">
            <span>企业数据将从已配置数据源直接读取</span>
            <span>Enter 发送 · Shift + Enter 换行</span>
            <button
              type="submit"
              className="send-button"
              disabled={!draft.trim() || busy}
              aria-label="发送"
            >
              ↑
            </button>
          </div>
        </form>
      </div>
    </section>
  );
}

function CompanyLookupView({
  api,
  notify,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
}) {
  const [query, setQuery] = useState("");
  const [resolution, setResolution] = useState<CompanyResolution>();
  const [selected, setSelected] = useState<CompanyCandidate>();
  const [busy, setBusy] = useState(false);

  async function search(event?: FormEvent) {
    event?.preventDefault();
    const value = query.trim();
    if (!value || busy) return;
    setBusy(true);
    try {
      const result = await api<CompanyResolution>(
        `/api/companies/resolve?query=${encodeURIComponent(value)}`,
      );
      setResolution(result);
      setSelected(result.candidates[0]);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const source = resolution?.source_statuses?.[0];
  const freshness = selected?.data_as_of || source?.data_as_of;
  const statusLabel = !resolution
    ? "等待查询"
    : resolution.status === "UNIQUE"
      ? "已唯一识别"
      : resolution.status === "AMBIGUOUS"
        ? "请选择主体"
        : resolution.status === "NOT_FOUND"
          ? "未找到企业"
          : "数据源查询失败";

  return (
    <section className="workspace-page company-lookup-page">
      <div className="page-intro">
        <div>
          <span className="eyebrow">ENTERPRISE SUBJECT DESK</span>
          <h2>企业查询</h2>
          <p>先确认企业主体和数据时点，再把明确的企业交给 Agent 发起风险排查。</p>
        </div>
      </div>
      <form className="company-search-bar" onSubmit={search}>
        <label>
          <span>企业名称或统一社会信用代码</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="例如：北京简熹和食品有限公司"
            autoFocus
          />
        </label>
        <button className="primary" type="submit" disabled={!query.trim() || busy}>
          {busy ? "正在查询" : "查询企业"}
        </button>
      </form>

      <div className="company-query-status">
        <span className={`status-dot ${resolution?.status === "FAILED" ? "offline" : resolution ? "online" : ""}`} />
        <strong>{statusLabel}</strong>
        <span>{source ? `${source.source_name} · ${source.record_count} 条结果` : "查询结果直接来自当前企业主数据源"}</span>
        {source?.fetched_at && <time>本次查询 {formatTime(source.fetched_at)}</time>}
      </div>

      {resolution && resolution.candidates.length === 0 && (
        <div className="empty-state large company-empty-result">
          <strong>{resolution.status === "FAILED" ? "企业数据源查询失败" : "没有匹配到企业主体"}</strong>
          <span>{source?.message || "请核对企业全称或改用统一社会信用代码查询。"}</span>
        </div>
      )}

      {resolution && resolution.candidates.length > 0 && (
        <div className="company-result-layout">
          <div className="company-candidate-list" aria-label="企业候选列表">
            {resolution.candidates.map((candidate) => (
              <button
                type="button"
                key={`${candidate.source_system}-${candidate.source_entity_id}`}
                className={candidate.source_entity_id === selected?.source_entity_id ? "selected" : ""}
                onClick={() => setSelected(candidate)}
              >
                <span className="company-state">{candidate.registration_status || "状态未公示"}</span>
                <strong>{candidate.canonical_name}</strong>
                <small>{candidate.unified_credit_code || candidate.registration_no || "统一社会信用代码未公示"}</small>
                <em>匹配度 {candidate.confidence !== undefined ? `${Math.round(candidate.confidence * 100)}%` : "—"}</em>
              </button>
            ))}
          </div>

          {selected && (
            <article className="company-profile-card">
              <header>
                <div>
                  <span className="eyebrow">CONFIRMED SUBJECT</span>
                  <h3>{selected.canonical_name}</h3>
                  <p>{selected.unified_credit_code || selected.registration_no || "/"}</p>
                </div>
                <span className="company-registration-badge">{selected.registration_status || "未公示"}</span>
              </header>
              <dl>
                <div><dt>法定代表人</dt><dd>{selected.legal_representative || "/"}</dd></div>
                <div><dt>登记地址</dt><dd>{selected.registered_address || "/"}</dd></div>
                <div><dt>数据来源</dt><dd>{source?.source_name || selected.source_system}</dd></div>
                <div><dt>数据时间</dt><dd>{freshness ? new Date(freshness).toLocaleString("zh-CN") : "未提供"}</dd></div>
              </dl>
              <div className="company-identity-hints">
                <span>简称：{displayAttribute(selected.attributes?.shortName)}</span>
                <span>曾用名：{displayAttribute(selected.attributes?.formerNames)}</span>
                <span>品牌：{displayAttribute(selected.attributes?.brands)}</span>
              </div>
              <footer>
                <p>发起后将再次读取并冻结完整企业数据；当前页面只用于确认主体，不作为报告快照。</p>
                <Link
                  className="primary"
                  href={`/?company=${encodeURIComponent(selected.canonical_name)}`}
                  prefetch={false}
                >
                  交给 Atlas 发起排查
                </Link>
              </footer>
            </article>
          )}
        </div>
      )}
    </section>
  );
}

function InlineTaskCard({
  taskId,
  api,
  apiBase,
  notify,
}: {
  taskId: string;
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  apiBase: string;
  notify: (message: string) => void;
}) {
  const [workspace, setWorkspace] = useState<Workspace>();
  useEffect(() => {
    let active = true;
    const refresh = (showError: boolean) => {
      void api<Workspace>(`/api/tasks/${taskId}/workspace`)
        .then((value) => {
          if (!active) return;
          setWorkspace(value);
          if (
            value.task.status === "COMPLETED" ||
            ["CONFIRM_SUBJECT", "REVIEW_SUBJECT_DATA", "REVIEW_EVIDENCE", "RETRY_TASK", "RETRY_REPORT"].includes(value.next_action)
          ) {
            window.clearInterval(timer);
          }
        })
        .catch((error) => {
          if (active && showError) notify((error as Error).message);
        });
    };
    refresh(true);
    const timer = window.setInterval(() => refresh(false), 3000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [api, notify, taskId]);
  if (!workspace) return <div className="inline-task loading-card">读取任务状态…</div>;
  const score = workspace.risk_score?.manual_score;
  const completed = workspace.task.status === "COMPLETED";
  const requiresHuman = [
    "CONFIRM_SUBJECT",
    "REVIEW_SUBJECT_DATA",
    "REVIEW_EVIDENCE",
    "RETRY_TASK",
    "RETRY_REPORT",
  ].includes(workspace.next_action);
  const generatedReport = workspace.reports.find((item) => item.status === "GENERATED");
  const stage = completed
    ? 4
    : ["WAITING_OPERATOR_CONFIRMATION", "GENERATING_REPORT"].includes(workspace.task.status)
      ? 3
      : ["CALCULATING_RISK", "WAITING_SUBJECT_DATA_REVIEW", "MODEL_FAILED"].includes(workspace.task.status)
        ? 2
        : workspace.task.status === "SEARCHING_PUBLIC_INTELLIGENCE"
          ? 1
          : 0;
  const conclusion = investigationConclusion(
    workspace.task.status,
    workspace.risk_score,
    workspace.evidence_progress,
  );
  return (
    <div className={`inline-task agent-delivery-card ${completed ? "completed" : requiresHuman ? "needs-human" : "running"}`}>
      <header className="delivery-card-head">
        <div>
          <span className="task-kicker">{workspace.task.task_no}</span>
          <strong>{workspace.task.company_query}</strong>
        </div>
        <span className="agent-mode-chip">
          <i />{requiresHuman ? "需要你判断" : completed ? "Atlas 已完成" : "Atlas 自动执行中"}
        </span>
      </header>

      <div className="task-journey" aria-label="Agent 执行进度">
        {["主体与数据", "公开信息", "证据研判", "评分与报告"].map((label, index) => (
          <div className={index < stage ? "done" : index === stage && !completed ? "active" : completed ? "done" : "pending"} key={label}>
            <span>{index < stage || completed ? "✓" : index + 1}</span>
            <strong>{label}</strong>
          </div>
        ))}
      </div>

      <div className="delivery-summary">
        <div>
          <span>当前结论</span>
          <strong>{completed ? conclusion.label : STATUS_LABELS[workspace.task.status] || workspace.task.status}</strong>
          <small>{completed ? conclusion.detail : requiresHuman ? "其余步骤已暂停，处理这一项后 Atlas 会自动继续。" : "无需逐步点击，状态会自动刷新。"}</small>
        </div>
        <div>
          <span>风险分</span>
          <strong className={score !== undefined && score >= 8 ? "danger" : ""}>{score === undefined ? "—" : score}</strong>
          <small>{score === undefined ? "完成研判后计算" : scoreLevel(score)}</small>
        </div>
        <div>
          <span>公开证据</span>
          <strong>{workspace.evidence_progress.total}</strong>
          <small>{workspace.evidence_progress.unverified ? `${workspace.evidence_progress.unverified} 条待判断` : "已处理完毕"}</small>
        </div>
      </div>

      <footer className="delivery-actions">
        <span>{completed ? "正式结果已经生成并留存审计记录。" : requiresHuman ? "Atlas 只在无法可靠判断时请求人工介入。" : "你可以离开本页，任务会在后台继续。"}</span>
        {generatedReport && (
          <a className="primary" href={`${apiBase.replace(/\/$/, "")}/api/tasks/${taskId}/reports/latest/download`}>下载正式报告</a>
        )}
        {!generatedReport && requiresHuman && <a className="primary" href={`/pending?id=${taskId}`}>处理异常</a>}
        {!generatedReport && !requiresHuman && <a href={`/tasks?id=${taskId}`}>查看执行详情</a>}
      </footer>
    </div>
  );
}

function TasksView({
  api,
  apiBase,
  operatorId,
  notify,
  mode,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  apiBase: string;
  operatorId: string;
  notify: (message: string) => void;
  mode: "pending" | "all";
}) {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [tasks, setTasks] = useState<TaskListItem[]>([]);
  const [selected, setSelected] = useState<TaskListItem>();
  const [workspace, setWorkspace] = useState<Workspace>();
  const [assessmentHistory, setAssessmentHistory] = useState<AssessmentRevision[]>([]);
  const [evidence, setEvidence] = useState<Evidence[]>([]);
  const [showResolvedEvidence, setShowResolvedEvidence] = useState(false);
  const [modelReviewJob, setModelReviewJob] = useState<EvidenceModelReviewJob>();
  const [companyAliases, setCompanyAliases] = useState<CompanyAlias[]>([]);
  const [subjectCandidates, setSubjectCandidates] = useState<
    CompanyCandidate[]
  >([]);
  const [loading, setLoading] = useState(true);
  const [actionBusy, setActionBusy] = useState(false);
  const [liveState, setLiveState] = useState<
    "connecting" | "online" | "offline"
  >("connecting");
  const [manualScore, setManualScore] = useState("");
  const [adjustmentReason, setAdjustmentReason] = useState(
    "ADDITIONAL_CONTEXT",
  );
  const [adjustmentText, setAdjustmentText] = useState("");
  const [confirmationNote, setConfirmationNote] = useState("");
  const [subjectConflictNote, setSubjectConflictNote] = useState("");
  const [aliasName, setAliasName] = useState("");
  const [aliasType, setAliasType] = useState("BRAND");
  const [aliasRelation, setAliasRelation] = useState("OWNED_BRAND");
  const [aliasEvidence, setAliasEvidence] = useState("");
  const loadedScoreRef = useRef<string>();
  const isPendingMode = mode === "pending";
  const selectedTaskId = selected?.task.task_id;
  const modelReviewActive = Boolean(modelReviewJob && [
    "QUEUED",
    "RUNNING",
    "CANCEL_REQUESTED",
  ].includes(modelReviewJob.status));
  const modelReviewRetryable = Boolean(modelReviewJob && [
    "PARTIAL_FAILED",
    "FAILED",
    "CANCELLED",
  ].includes(modelReviewJob.status));
  const pendingEvidence = evidence.filter(
    (item) => item.verification_status === "UNVERIFIED",
  );
  const resolvedEvidence = evidence.filter(
    (item) => item.verification_status !== "UNVERIFIED",
  );
  const visibleEvidence = showResolvedEvidence
    ? [...pendingEvidence, ...resolvedEvidence]
    : pendingEvidence;

  const load = useCallback(async () => {
    setLoading(true);
    const params = new URLSearchParams({
      operator_id: operatorId,
      page_size: "50",
    });
    if (query.trim()) params.set("query", query.trim());
    if (status) params.append("status", status);
    try {
      const page = await api<TaskPage>(`/api/tasks?${params}`);
      const visibleItems = isPendingMode
        ? page.items.filter((item) =>
            [
              "CONFIRM_SUBJECT",
              "REVIEW_SUBJECT_DATA",
              "REVIEW_EVIDENCE",
              "RETRY_TASK",
              "RETRY_REPORT",
            ].includes(item.next_action),
          )
        : page.items;
      const requestedTaskId = new URLSearchParams(window.location.search).get("id");
      setTasks(visibleItems);
      setSelected(
        (current) =>
          visibleItems.find((item) => item.task.task_id === requestedTaskId) ||
          visibleItems.find(
            (item) => item.task.task_id === current?.task.task_id,
          ) || visibleItems[0],
      );
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [api, isPendingMode, notify, operatorId, query, status]);

  const loadWorkspace = useCallback(
    async (taskId: string) => {
      const [workspaceResult, evidenceResult, aliasResult, reviewJobResult, historyResult] = await Promise.all([
        api<Workspace>(`/api/tasks/${taskId}/workspace`),
        api<Evidence[]>(
          `/api/tasks/${taskId}/public-intelligence/evidence`,
        ).catch(() => []),
        api<CompanyAlias[]>(`/api/tasks/${taskId}/company-aliases`).catch(
          () => [],
        ),
        api<EvidenceModelReviewJob>(
          `/api/tasks/${taskId}/public-intelligence/evidence/model-review/jobs/latest`,
        ).catch(() => undefined),
        api<AssessmentRevision[]>(`/api/tasks/${taskId}/risk-score/history`).catch(
          () => [],
        ),
      ]);
      setWorkspace(workspaceResult);
      setEvidence(evidenceResult);
      setCompanyAliases(aliasResult);
      setModelReviewJob(reviewJobResult);
      setAssessmentHistory(historyResult);
      if (
        workspaceResult.risk_score &&
        loadedScoreRef.current !==
          workspaceResult.risk_score.score_snapshot_id
      ) {
        loadedScoreRef.current =
          workspaceResult.risk_score.score_snapshot_id;
        setManualScore(String(workspaceResult.risk_score.manual_score));
      } else if (!workspaceResult.risk_score) {
        loadedScoreRef.current = undefined;
        setManualScore("");
      }
      if (
        workspaceResult.task.status === "WAITING_SUBJECT_CONFIRMATION"
      ) {
        const resolution = await api<CompanyResolution>(
          `/api/companies/resolve?query=${encodeURIComponent(
            workspaceResult.task.company_query,
          )}`,
        );
        setSubjectCandidates(resolution.candidates);
      } else {
        setSubjectCandidates([]);
      }
    },
    [api],
  );

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    if (!selectedTaskId) return;
    const timer = window.setTimeout(() => {
      void loadWorkspace(selectedTaskId)
        .catch((error) => notify((error as Error).message));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadWorkspace, notify, selectedTaskId]);

  useEffect(() => {
    const taskId = selectedTaskId;
    if (!taskId) return;
    let refreshTimer: number | undefined;
    const connectingTimer = window.setTimeout(
      () => setLiveState("connecting"),
      0,
    );
    const source = new EventSource(
      `${apiBase.replace(/\/$/, "")}/api/tasks/${taskId}/events`,
    );
    const refresh = () => {
      setLiveState("online");
      if (refreshTimer) window.clearTimeout(refreshTimer);
      refreshTimer = window.setTimeout(() => {
        void Promise.all([loadWorkspace(taskId), load()]);
      }, 180);
    };
    source.onopen = () => setLiveState("online");
    source.onerror = () => setLiveState("offline");
    TASK_EVENT_TYPES.forEach((eventType) =>
      source.addEventListener(eventType, refresh),
    );
    return () => {
      window.clearTimeout(connectingTimer);
      if (refreshTimer) window.clearTimeout(refreshTimer);
      TASK_EVENT_TYPES.forEach((eventType) =>
        source.removeEventListener(eventType, refresh),
      );
      source.close();
    };
  }, [apiBase, load, loadWorkspace, selectedTaskId]);

  useEffect(() => {
    const taskId = selectedTaskId;
    if (!taskId || !modelReviewJob || ![
      "QUEUED",
      "RUNNING",
      "CANCEL_REQUESTED",
    ].includes(modelReviewJob.status)) return;
    const timer = window.setInterval(() => {
      void loadWorkspace(taskId).catch((error) =>
        notify((error as Error).message),
      );
    }, 2000);
    return () => window.clearInterval(timer);
  }, [loadWorkspace, modelReviewJob, notify, selectedTaskId]);

  async function performAction<T>(
    successMessage: string | ((result: T) => string),
    action: () => Promise<T>,
  ) {
    if (!selected) return;
    setActionBusy(true);
    try {
      const result = await action();
      notify(
        typeof successMessage === "function"
          ? successMessage(result)
          : successMessage,
      );
      await Promise.all([loadWorkspace(selected.task.task_id), load()]);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setActionBusy(false);
    }
  }

  function post<T = unknown>(path: string, body?: unknown) {
    return api<T>(path, {
      method: "POST",
      ...(body === undefined
        ? {}
        : {
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          }),
    });
  }

  async function runNextAction() {
    if (!selected || !workspace) return;
    const taskId = selected.task.task_id;
    switch (workspace.next_action) {
      case "EXECUTE_TASK":
        await performAction("自动步骤已执行到下一个必要人工节点", () =>
          post(`/api/tasks/${taskId}/execute`),
        );
        break;
      case "RETRY_TASK":
        await performAction("任务已重试", () =>
          post(`/api/tasks/${taskId}/retry`),
        );
        break;
      case "REVIEW_SUBJECT_DATA":
        document
          .getElementById("subject-data-conflicts")
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
        break;
      case "CALCULATE_RISK":
        await performAction("风险分已计算", () =>
          post(
            `/api/tasks/${taskId}/risk-score/calculate-from-confirmed-evidence`,
          ),
        );
        break;
      case "CONFIRM_REVIEW":
        await performAction("本轮研判已确认，正式报告已生成", async () => {
          await post(`/api/tasks/${taskId}/operator-confirmation`, {
            note: confirmationNote.trim() || undefined,
          });
          return post(`/api/tasks/${taskId}/reports`);
        });
        setConfirmationNote("");
        break;
      case "GENERATE_REPORT":
      case "RETRY_REPORT":
        await performAction("正式报告已生成", () =>
          post(`/api/tasks/${taskId}/reports`),
        );
        break;
      case "DOWNLOAD_REPORT": {
        const report = workspace.reports.find(
          (item) => item.status === "GENERATED",
        );
        if (!report) {
          notify("当前没有可下载的成功报告版本");
          return;
        }
        window.location.href = `${apiBase.replace(
          /\/$/,
          "",
        )}/api/tasks/${taskId}/reports/${report.report_id}/download`;
        break;
      }
      case "REVIEW_EVIDENCE":
        document
          .getElementById("task-evidence-review")
          ?.scrollIntoView({ behavior: "smooth", block: "start" });
        break;
      case "CONFIRM_SUBJECT":
        document
          .getElementById("subject-candidates")
          ?.scrollIntoView({ behavior: "smooth", block: "start" });
        break;
      default:
        notify("当前无需人工操作，系统会持续同步任务状态");
    }
  }

  async function resolveSubjectDataConflict() {
    if (!selected || !subjectConflictNote.trim()) {
      notify("请填写以企业主档为准继续的核验依据");
      return;
    }
    const taskId = selected.task.task_id;
    await performAction(
      "主体数据冲突已留痕，Atlas 已继续完成可自动执行的步骤",
      () =>
        post(`/api/tasks/${taskId}/subject-data-conflict-resolution`, {
          decision: "ACCEPT_MASTER",
          note: subjectConflictNote.trim(),
        }),
    );
    setSubjectConflictNote("");
  }

  async function confirmSubject(candidate: CompanyCandidate) {
    if (!selected) return;
    await performAction(`已确认主体：${candidate.canonical_name}`, () =>
      post(
        `/api/tasks/${selected.task.task_id}/subject-confirmation`,
        {
          source_system: candidate.source_system,
          source_entity_id: candidate.source_entity_id,
        },
      ),
    );
  }

  async function adjustScore(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !workspace?.risk_score) return;
    const score = Number(manualScore);
    if (!Number.isFinite(score) || score < 0 || score > 10) {
      notify("人工分必须在 0 到 10 之间");
      return;
    }
    if (!adjustmentText.trim()) {
      notify("请填写调分依据，保证报告可追溯");
      return;
    }
    await performAction("人工评分已保存，需要重新完成运营确认", () =>
      post(
        `/api/tasks/${selected.task.task_id}/risk-score/${workspace.risk_score?.score_snapshot_id}/adjustments`,
        {
          manual_score: score,
          reason_code: adjustmentReason,
          reason_text: adjustmentText.trim(),
        },
      ),
    );
    setManualScore(String(score));
    setAdjustmentText("");
  }

  async function decide(item: Evidence, decision: "CONFIRMED" | "REJECTED") {
    if (!selected) return;
    await performAction(
      decision === "CONFIRMED" ? "证据已确认" : "证据已排除",
      () =>
        post(
          `/api/tasks/${selected.task.task_id}/public-intelligence/evidence/${item.evidence_id}/decision`,
          {
            decision,
            reason:
              decision === "CONFIRMED"
                ? "运营人工核验确认"
                : "运营人工核验排除",
          },
        ),
    );
  }

  async function runModelReview() {
    if (!selected) return;
    setActionBusy(true);
    try {
      const job = await post<EvidenceModelReviewJob>(
        `/api/tasks/${selected.task.task_id}/public-intelligence/evidence/model-review`,
        {},
      );
      setModelReviewJob(job);
      notify("自动研判已重新启动，系统将继续处理并在必要时请求人工判断");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setActionBusy(false);
    }
  }

  async function cancelModelReview() {
    if (!selected || !modelReviewJob) return;
    setActionBusy(true);
    try {
      const job = await post<EvidenceModelReviewJob>(
        `/api/tasks/${selected.task.task_id}/public-intelligence/evidence/model-review/jobs/${modelReviewJob.review_job_id}/cancel`,
      );
      setModelReviewJob(job);
      notify("已请求取消，当前模型批次结束后停止");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setActionBusy(false);
    }
  }

  async function addCompanyAlias(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    if (!aliasName.trim()) {
      notify("请填写简称、品牌或门店名称");
      return;
    }
    if (!aliasEvidence.trim()) {
      notify("请填写该名称与法人主体的核实依据");
      return;
    }
    const taskId = selected.task.task_id;
    await performAction("企业身份词已保存，并完成补充检索", async () => {
      await post(`/api/tasks/${taskId}/company-aliases`, {
        alias_name: aliasName.trim(),
        alias_type: aliasType,
        relation: aliasRelation,
        source_evidence: aliasEvidence.trim(),
      });
      await post(`/api/tasks/${taskId}/public-intelligence/search`);
    });
    setAliasName("");
    setAliasEvidence("");
  }

  function changeAliasType(value: string) {
    setAliasType(value);
    setAliasRelation(
      value === "FORMER_NAME"
        ? "FORMER_IDENTITY"
        : value === "BRAND"
          ? "OWNED_BRAND"
          : value === "STORE"
            ? "OPERATED_STORE"
            : "SAME_LEGAL_ENTITY",
    );
  }

  return (
    <section className={`workspace-page ${isPendingMode ? "pending-workspace-page" : "task-records-page"}`}>
      <div className="page-intro">
        <div>
          <span className="eyebrow">{isPendingMode ? "EXCEPTION INBOX" : "INVESTIGATION HISTORY"}</span>
          <h2>{isPendingMode ? "只处理 Atlas 无法判断的事项" : "任务记录"}</h2>
          <p>{isPendingMode ? "正常任务由 Agent 连续执行，这里只汇集主体歧义、待核验证据和失败重试。" : "查看每次企业研判的执行过程、证据、评分和最终交付结果。"}</p>
        </div>
        <Link className="primary" href="/" prefetch={false}>交给 Atlas 新任务</Link>
      </div>
      <div className="filters">
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索企业名称或任务编号"
        />
        {!isPendingMode && (
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">全部状态</option>
            {Object.entries(STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        )}
        <button className="ghost" type="button" onClick={load}>刷新</button>
      </div>
      <div className="task-workspace-grid">
        <div className="task-table-card">
          <div className="table-head">
            <span>企业 / 任务</span>
            <span>排查结论</span>
            <span>状态</span>
            <span>下一步</span>
          </div>
          {loading && <div className="empty-state">正在读取任务…</div>}
          {!loading && tasks.length === 0 && (
            <div className="empty-state operator-empty-state">
              <strong>{isPendingMode ? "目前没有需要人工处理的异常" : "当前筛选条件下暂无任务"}</strong>
              <span>{isPendingMode ? "Atlas 会继续自动执行其他任务；遇到无法可靠判断的事项时会出现在这里。" : `任务按运营人员隔离，当前身份为 ${operatorId}`}</span>
              {!isPendingMode && <Link href="/settings" prefetch={false}>切换运营人员或检查连接设置</Link>}
            </div>
          )}
          {tasks.map((item) => (
            <button
              type="button"
              key={item.task.task_id}
              className={`task-row ${
                selected?.task.task_id === item.task.task_id ? "selected" : ""
              }`}
              onClick={() => {
                setWorkspace(undefined);
                setSubjectCandidates([]);
                setLiveState("connecting");
                setSelected(item);
              }}
            >
              <span>
                <strong>{item.task.company_query}</strong>
                <small>{item.task.task_no} · {formatTime(item.task.updated_at)}</small>
              </span>
              <span className="score-cell">
                {investigationConclusion(
                  item.task.status,
                  item.risk,
                  item.evidence_progress,
                ).label}
                <small>人工分 {item.risk?.manual_score ?? "—"}</small>
              </span>
              <span>
                <em className={`status-pill status-${item.task.status.toLowerCase()}`}>
                  {STATUS_LABELS[item.task.status] || item.task.status}
                </em>
              </span>
              <span>{ACTION_LABELS[item.next_action] || item.next_action}</span>
            </button>
          ))}
        </div>

        <aside className="task-detail">
          {!workspace && <div className="empty-state">选择任务查看处理详情</div>}
          {workspace && (
            <>
              <div className="detail-title">
                <div className="detail-kicker">
                  <span className="eyebrow">{workspace.task.task_no}</span>
                  <span className={`live-state ${liveState}`}>
                    <i />
                    {liveState === "online"
                      ? "实时同步"
                      : liveState === "connecting"
                        ? "正在连接"
                        : "自动重连中"}
                  </span>
                </div>
                <h3>{workspace.task.company_query}</h3>
                <em className={`status-pill status-${workspace.task.status.toLowerCase()}`}>
                  {STATUS_LABELS[workspace.task.status] || workspace.task.status}
                </em>
              </div>
              <div className="metric-grid">
                <div>
                  <span>排查结论</span>
                  <strong>
                    {investigationConclusion(
                      workspace.task.status,
                      workspace.risk_score,
                      workspace.evidence_progress,
                    ).label}
                  </strong>
                  <small>
                    {investigationConclusion(
                      workspace.task.status,
                      workspace.risk_score,
                      workspace.evidence_progress,
                    ).detail}
                  </small>
                </div>
                <div>
                  <span>原始分</span>
                  <strong>{workspace.risk_score?.original_score ?? "—"}</strong>
                </div>
                <div>
                  <span>人工分</span>
                  <strong>{workspace.risk_score?.manual_score ?? "—"}</strong>
                </div>
                <div>
                  <span>证据处理</span>
                  <strong>
                    {workspace.evidence_progress.confirmed +
                      workspace.evidence_progress.rejected}
                    /{workspace.evidence_progress.total}
                  </strong>
                </div>
              </div>
              <div className="next-action-card">
                <div>
                  <span>下一步操作</span>
                  <strong>
                    {ACTION_LABELS[workspace.next_action] ||
                      workspace.next_action}
                  </strong>
                  <p>
                    {workspace.next_action === "REVIEW_EVIDENCE"
                      ? "逐条确认或排除公开证据，全部处理后才能计算风险分。"
                      : workspace.next_action === "REVIEW_SUBJECT_DATA"
                        ? "核对主档与工商变更的冲突，并留下采用当前主档的依据。"
                      : workspace.next_action === "CONFIRM_REVIEW"
                        ? "确认当前数据、证据和人工评分无误后，系统将直接生成正式报告。"
                        : workspace.next_action === "CONFIRM_SUBJECT"
                          ? "从候选企业中选择与本次排查一致的登记主体。"
                          : "按当前工作流状态继续处理，完成后页面会自动刷新。"}
                  </p>
                </div>
                <button
                  className="primary"
                  type="button"
                  disabled={actionBusy || ["WAIT", "NONE"].includes(workspace.next_action)}
                  onClick={() => void runNextAction()}
                >
                  {actionBusy
                    ? "处理中…"
                    : ACTION_LABELS[workspace.next_action] ||
                      workspace.next_action}
                </button>
              </div>
              {workspace.readiness_blockers.length > 0 && (
                <div className="blockers">
                  <strong>当前阻塞</strong>
                  {workspace.readiness_blockers.map((item) => (
                    <span key={item}>{READINESS_LABELS[item] || item}</span>
                  ))}
                </div>
              )}
              {workspace.subject_data_conflicts.length > 0 && (
                <div className="subject-conflicts" id="subject-data-conflicts">
                  <strong>主体数据冲突</strong>
                  <p>以下关键字段在当前企业主档与工商变更记录中不一致，未核验前禁止评分和生成报告。</p>
                  {workspace.subject_data_conflicts.map((conflict) => (
                    <div className="subject-conflict-row" key={conflict.code}>
                      <span>{conflict.field_name}</span>
                      <span>主档：{conflict.master_value}</span>
                      <span>
                        最新变更：{conflict.latest_change_value}
                        {conflict.changed_at ? `（${formatTime(conflict.changed_at)}）` : ""}
                      </span>
                    </div>
                  ))}
                  {workspace.subject_data_conflict_resolution ? (
                    <div className="subject-conflict-resolved">
                      <strong>已确认采用当前企业主档</strong>
                      <span>
                        {workspace.subject_data_conflict_resolution.operator_id} · {formatTime(workspace.subject_data_conflict_resolution.resolved_at)}
                      </span>
                      <p>{workspace.subject_data_conflict_resolution.note}</p>
                    </div>
                  ) : (
                    <div className="subject-conflict-action">
                      <label>
                        <span>核验依据</span>
                        <textarea
                          value={subjectConflictNote}
                          maxLength={1000}
                          onChange={(event) => setSubjectConflictNote(event.target.value)}
                          placeholder="例如：已核对国家企业信用信息公示系统，当前法定代表人以企业主档记录为准。"
                        />
                      </label>
                      <div>
                        <p>若不能确认主档正确，请先修正企业数据源并重新发起任务。</p>
                        <button
                          className="primary"
                          type="button"
                          disabled={actionBusy || !subjectConflictNote.trim()}
                          onClick={() => void resolveSubjectDataConflict()}
                        >
                          确认以当前主档为准并继续
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {workspace.next_action === "CONFIRM_SUBJECT" && (
                <div className="operation-section" id="subject-candidates">
                  <div className="section-title">
                    <h4>企业主体候选</h4>
                    <span>{subjectCandidates.length} 家</span>
                  </div>
                  <div className="candidate-list">
                    {subjectCandidates.length === 0 && (
                      <div className="empty-evidence">
                        暂未取得候选主体，请刷新后重试
                      </div>
                    )}
                    {subjectCandidates.map((candidate) => (
                      <article
                        key={`${candidate.source_system}:${candidate.source_entity_id}`}
                      >
                        <div>
                          <strong>{candidate.canonical_name}</strong>
                          <p>
                            统一社会信用代码：
                            {candidate.unified_credit_code || "未提供"}
                          </p>
                          <small>
                            法定代表人：
                            {candidate.legal_representative || "未提供"} ·{" "}
                            {candidate.registration_status || "状态未提供"}
                          </small>
                          <small>
                            {candidate.registered_address || "登记地址未提供"}
                          </small>
                        </div>
                        <div className="candidate-side">
                          <span>
                            匹配度{" "}
                            {candidate.confidence === undefined
                              ? "—"
                              : `${Math.round(candidate.confidence * 100)}%`}
                          </span>
                          <button
                            className="primary"
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void confirmSubject(candidate)}
                          >
                            确认此主体
                          </button>
                        </div>
                      </article>
                    ))}
                  </div>
                </div>
              )}

              {workspace.task.status !== "WAITING_SUBJECT_CONFIRMATION" &&
                companyAliases.length > 0 && (
                <div className="operation-section identity-section">
                  <div className="section-title">
                    <div>
                      <h4>企业身份词</h4>
                      <p>新闻常用简称、品牌和门店名；只有已确认关系的名称才参与检索和主体归属。</p>
                    </div>
                    <span>{companyAliases.length} 个</span>
                  </div>
                  <div className="identity-tags">
                    {companyAliases.map((alias) => (
                      <span key={alias.alias_id} title={alias.source_evidence || alias.source_system}>
                        <i>{ALIAS_TYPE_LABELS[alias.alias_type] || alias.alias_type}</i>
                        {alias.alias_name}
                        <small>{alias.source_system === "OPERATOR" ? "人工确认" : "企业数据"}</small>
                      </span>
                    ))}
                  </div>
                  {workspace.task.status !== "COMPLETED" && (
                    <form className="identity-form" onSubmit={addCompanyAlias}>
                      <div className="identity-form-grid">
                        <label>
                          <span>名称</span>
                          <input
                            value={aliasName}
                            onChange={(event) => setAliasName(event.target.value)}
                            placeholder="如：品牌名、门店名、企业简称"
                          />
                        </label>
                        <label>
                          <span>名称类型</span>
                          <select
                            value={aliasType}
                            onChange={(event) => changeAliasType(event.target.value)}
                          >
                            {ALIAS_TYPES.map(([value, label]) => (
                              <option key={value} value={value}>{label}</option>
                            ))}
                          </select>
                        </label>
                        <label>
                          <span>与法人主体关系</span>
                          <select
                            value={aliasRelation}
                            onChange={(event) => setAliasRelation(event.target.value)}
                          >
                            {ALIAS_RELATIONS.map(([value, label]) => (
                              <option key={value} value={value}>{label}</option>
                            ))}
                          </select>
                        </label>
                      </div>
                      <label>
                        <span>核实依据</span>
                        <textarea
                          rows={2}
                          value={aliasEvidence}
                          onChange={(event) => setAliasEvidence(event.target.value)}
                          placeholder="填写官网、商标、门店页面或人工核对说明"
                        />
                      </label>
                      <button className="ghost" type="submit" disabled={actionBusy}>
                        保存并补充检索
                      </button>
                    </form>
                  )}
                </div>
              )}

              {workspace.risk_score && (
                <form className="score-adjustment" onSubmit={adjustScore}>
                  <div className="section-title">
                    <h4>人工评分调整</h4>
                    <span>原始分始终保留</span>
                  </div>
                  <div className="score-lineage">
                    <div>
                      <span>ES物化旧模型分</span>
                      <strong>{workspace.risk_score.legacy_score ?? "—"}</strong>
                    </div>
                    <div>
                      <span>迁移规则计算分</span>
                      <strong>{workspace.risk_score.rule_calculated_score ?? "—"}</strong>
                    </div>
                    <div>
                      <span>事件保底分</span>
                      <strong>{workspace.risk_score.event_floor_score ?? 0}</strong>
                    </div>
                    <div>
                      <span>LLM建议分</span>
                      <strong>{modelReviewJob?.model_suggested_score ?? "—"}</strong>
                    </div>
                    <div>
                      <span>系统原始分</span>
                      <strong>{workspace.risk_score.original_score}</strong>
                    </div>
                    <div>
                      <span>当前人工分</span>
                      <strong>{workspace.risk_score.manual_score}</strong>
                    </div>
                    <div>
                      <span>迁移计算差异</span>
                      <strong>
                        {workspace.risk_score.legacy_score !== undefined &&
                        workspace.risk_score.rule_calculated_score !== undefined
                          ? (workspace.risk_score.rule_calculated_score -
                              workspace.risk_score.legacy_score).toFixed(2)
                          : "—"}
                      </strong>
                    </div>
                    <p>
                      {workspace.risk_score.rule_hits?.some(
                        (hit) => hit.rule_code === "LEGACY_BASE_TOTAL",
                      )
                        ? "当前执行方式：本次快照已使用完整迁移规则重算；规则命中、中间分和倍率均保存在评分快照中。"
                        : workspace.risk_score.legacy_score !== undefined
                          ? "当前执行方式：本次快照的旧模型特征尚不完整，迁移规则计算分沿用ES物化旧模型分，再应用已确认事件最低分。"
                          : "当前执行方式：本次快照的旧模型特征不完整，ES也未提供物化旧模型分，迁移规则计算分按0分基线计算，再应用已确认事件最低分。该结果不代表旧评分模型已完整重算。"}
                    </p>
                  </div>
                  {workspace.risk_score.rule_hits && workspace.risk_score.rule_hits.length > 0 && (
                    <div className="rule-hit-trace">
                      <div className="section-title">
                        <h4>规则与证据追溯</h4>
                        <span>{workspace.risk_score.rule_hits.length} 条命中</span>
                      </div>
                      {workspace.risk_score.rule_hits.map((hit) => (
                        <div className="rule-hit-row" key={`${hit.rule_code}-${hit.score_role}`}>
                          <span>
                            <strong>{hit.rule_name}</strong>
                            <small>{hit.rule_code} · {hit.score_role} · {hit.risk_type}</small>
                          </span>
                          <b>{hit.score}</b>
                          <code>{hit.references?.length ? hit.references.join(" · ") : "无外部证据引用（来自数据快照或聚合规则）"}</code>
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="assessment-history">
                    <div className="section-title">
                      <h4>研判版本记录</h4>
                      <span>{assessmentHistory.length} 个版本</span>
                    </div>
                    {assessmentHistory.length ? assessmentHistory.map((revision, index) => (
                      <details key={revision.assessment_revision_id} open={index === 0}>
                        <summary>
                          <span>
                            <strong>V{revision.revision_no}</strong>
                            <small>{revision.trigger_type === "SYSTEM_CALCULATION" ? "Atlas 自动研判" : "人工调整"}</small>
                          </span>
                          <b>{revision.final_score} · {scoreLevel(revision.final_score)}</b>
                          <time>{new Date(revision.created_at).toLocaleString("zh-CN")}</time>
                        </summary>
                        <div className="assessment-history-detail">
                          <p>{revision.reason_text}</p>
                          <div className="assessment-label-groups">
                            <span>最终标签</span>
                            <div>
                              {revision.final_labels.length ? revision.final_labels.map((label) => (
                                <em key={`${revision.revision_no}-${label.label_code || label.risk_type}`}>
                                  {label.label_name}
                                </em>
                              )) : <small>本次研判无风险标签</small>}
                            </div>
                          </div>
                          <footer>
                            <span>原始分 {revision.original_score}</span>
                            <span>规则分 {revision.rule_calculated_score}</span>
                            <span>保底分 {revision.event_floor_score}</span>
                            <span>{revision.actor_type === "OPERATOR" ? "操作人" : "执行引擎"}：{revision.actor_id}</span>
                            <span>{revision.rule_version} · {revision.engine_version}</span>
                          </footer>
                        </div>
                      </details>
                    )) : <p className="inline-empty">历史任务将在下一次重新研判时建立首个版本记录。</p>}
                  </div>
                  <div className="form-grid">
                    <label>
                      <span>人工分（0–10）</span>
                      <input
                        type="number"
                        min="0"
                        max="10"
                        step="0.1"
                        value={manualScore}
                        onChange={(event) => setManualScore(event.target.value)}
                      />
                    </label>
                    <label>
                      <span>调整原因</span>
                      <select
                        value={adjustmentReason}
                        onChange={(event) =>
                          setAdjustmentReason(event.target.value)
                        }
                      >
                        {ADJUSTMENT_REASONS.map(([value, label]) => (
                          <option key={value} value={value}>
                            {label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  <label>
                    <span>调分依据</span>
                    <textarea
                      rows={3}
                      value={adjustmentText}
                      onChange={(event) =>
                        setAdjustmentText(event.target.value)
                      }
                      placeholder="说明新增事实、证据纠正或规则未覆盖的业务背景"
                    />
                  </label>
                  <button
                    className="ghost"
                    type="submit"
                    disabled={actionBusy}
                  >
                    保存人工评分
                  </button>
                </form>
              )}

              {workspace.next_action === "CONFIRM_REVIEW" && (
                <div className="confirmation-note">
                  <strong>
                    当前拟确认结论：
                    {investigationConclusion(
                      workspace.task.status,
                      workspace.risk_score,
                      workspace.evidence_progress,
                    ).label}
                  </strong>
                  <p>
                    只有必查来源查询成功且证据已处理完成，才能确认“暂未发现明确风险”；查询失败的任务必须停止。
                  </p>
                  <label>
                    <span>运营确认说明（选填）</span>
                    <textarea
                      rows={3}
                      value={confirmationNote}
                      onChange={(event) =>
                        setConfirmationNote(event.target.value)
                      }
                      placeholder="记录本轮研判结论或需要在报告中关注的事项"
                    />
                  </label>
                </div>
              )}

              <div className="operation-section">
                <div className="section-title">
                  <h4>处理进度</h4>
                  <span>{workspace.steps.length} 个步骤</span>
                </div>
                <div className="workflow-steps">
                  {workspace.steps.map((step, index) => (
                    <div
                      className={`workflow-step step-${step.status.toLowerCase()}`}
                      key={`${step.step_name}-${index}`}
                    >
                      <i>{index + 1}</i>
                      <span>
                        <strong>
                          {STEP_LABELS[step.step_name] || step.step_name}
                        </strong>
                        <small>
                          {step.status}
                          {step.attempt_no && step.attempt_no > 1
                            ? ` · 第 ${step.attempt_no} 次`
                            : ""}
                        </small>
                        {step.error_message && <em>{step.error_message}</em>}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="section-title" id="task-evidence-review">
                <div>
                  <h4>需要你判断的证据</h4>
                  <span>
                    {pendingEvidence.length
                      ? `${pendingEvidence.length} 条 Atlas 无法可靠判断`
                      : "Atlas 已完成全部证据判断"}
                  </span>
                </div>
                {evidence.some((item) => item.verification_status === "UNVERIFIED") && modelReviewRetryable && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={actionBusy || modelReviewActive}
                    onClick={() => void runModelReview()}
                  >
                    重新启动自动研判
                  </button>
                )}
              </div>
              {modelReviewJob && (
                <div className={`model-review-panel status-${modelReviewJob.status.toLowerCase()}`}>
                  <div>
                    <strong>
                      {modelReviewJob.status === "QUEUED" ? "自动研判等待执行" :
                        modelReviewJob.status === "RUNNING" ? "Atlas 正在自动研判证据" :
                        modelReviewJob.status === "CANCEL_REQUESTED" ? "正在结束当前批次" :
                        modelReviewJob.status === "SUCCEEDED" ? "自动研判已完成" :
                        modelReviewJob.status === "PARTIAL_FAILED" ? "部分证据自动研判失败" :
                        modelReviewJob.status === "CANCELLED" ? "自动研判已取消" : "自动研判失败"}
                    </strong>
                    <span>
                      已处理 {modelReviewJob.processed_count}/{modelReviewJob.total_count || "待统计"} 条，
                      完成 {modelReviewJob.reviewed_count} 条语义判断
                      {modelReviewJob.failed_count > 0 ? `，${modelReviewJob.failed_count} 条失败` : ""}
                    </span>
                    {modelReviewJob.error_message && <small>{modelReviewJob.error_message}</small>}
                    {modelReviewJob.model_call_count > 0 && (
                      <small className="model-score-advisory">
                        已调用 {modelReviewJob.provider || "联网模型"} / {modelReviewJob.model || "未记录模型名"}
                        {` ${modelReviewJob.model_call_count} 次`}
                        {modelReviewJob.total_token_count > 0
                          ? ` · 输入 ${modelReviewJob.prompt_token_count} Token · 输出 ${modelReviewJob.completion_token_count} Token · 合计 ${modelReviewJob.total_token_count} Token`
                          : " · 服务商未返回 Token 用量"}
                      </small>
                    )}
                    {modelReviewJob.model_suggested_score !== undefined && (
                      <small className="model-score-advisory">
                        LLM建议分 {modelReviewJob.model_suggested_score}（{modelReviewJob.model_suggested_risk_level}）
                        · 依据 {modelReviewJob.model_score_evidence_ids.length} 条高置信建议
                        · 规则 {modelReviewJob.advisory_rule_version}
                        · 语义判断按受控策略自动采纳或转人工，最终风险分仍由确定性规则计算
                      </small>
                    )}
                    <div className="model-review-progress" aria-label="模型研判进度">
                      <i style={{ width: `${modelReviewJob.total_count ? Math.min(100, (modelReviewJob.processed_count / modelReviewJob.total_count) * 100) : 4}%` }} />
                    </div>
                  </div>
                  {["QUEUED", "RUNNING"].includes(modelReviewJob.status) && (
                    <button
                      type="button"
                      className="text-button"
                      disabled={actionBusy}
                      onClick={() => void cancelModelReview()}
                    >
                      取消研判
                    </button>
                  )}
                </div>
              )}
              <div className="evidence-list">
                {evidence.length === 0 && (
                  <div className="empty-evidence">暂无公开证据或尚未执行检索</div>
                )}
                {evidence.length > 0 && pendingEvidence.length === 0 && !showResolvedEvidence && (
                  <div className="evidence-resolved-summary">
                    <div>
                      <strong>无需人工处理</strong>
                      <span>Atlas 已自动确认或排除 {resolvedEvidence.length} 条证据。</span>
                    </div>
                    <button type="button" onClick={() => setShowResolvedEvidence(true)}>
                      查看处理记录
                    </button>
                  </div>
                )}
                {pendingEvidence.length > 0 && resolvedEvidence.length > 0 && (
                  <button
                    type="button"
                    className="evidence-history-toggle"
                    onClick={() => setShowResolvedEvidence((current) => !current)}
                  >
                    {showResolvedEvidence
                      ? "收起 Atlas 已处理证据"
                      : `查看 Atlas 已处理的 ${resolvedEvidence.length} 条证据`}
                  </button>
                )}
                {visibleEvidence.map((item) => (
                  <article key={item.evidence_id}>
                    <div>
                      <span className="risk-type">{item.risk_type}</span>
                      <strong>{item.title}</strong>
                      <p>{item.snippet || "暂无摘要"}</p>
                      {item.metadata?.llm_relevance && (
                        <p className="validation-note">
                          模型建议：{item.metadata.llm_relevance === "RELEVANT" ? "相关" : item.metadata.llm_relevance === "IRRELEVANT" ? "不相关" : "不确定"}
                          {item.metadata.llm_risk_type ? ` · ${item.metadata.llm_risk_type}` : ""}
                          {item.metadata.llm_confidence ? ` · 置信度 ${Math.round(Number(item.metadata.llm_confidence) * 100)}%` : ""}
                          {item.metadata.llm_reason ? ` · ${item.metadata.llm_reason}` : ""}
                        </p>
                      )}
                      <small>
                        {item.source_provider} · {formatTime(item.captured_at)}
                        {item.metadata?.matched_identity_term && (
                          <> · 命中“{item.metadata.matched_identity_term}”</>
                        )}
                        {item.source_url && (
                          <>
                            {" · "}
                            <a
                              href={item.source_url}
                              target="_blank"
                              rel="noreferrer"
                            >
                              查看来源
                            </a>
                          </>
                        )}
                      </small>
                    </div>
                    <div className="evidence-actions">
                      {item.verification_status === "UNVERIFIED" ? (
                        <>
                          <button
                            type="button"
                            disabled={actionBusy || modelReviewActive}
                            onClick={() => void decide(item, "CONFIRMED")}
                          >
                            确认
                          </button>
                          <button
                            type="button"
                            className="quiet-danger"
                            disabled={actionBusy || modelReviewActive}
                            onClick={() => void decide(item, "REJECTED")}
                          >
                            排除
                          </button>
                        </>
                      ) : (
                        <em>{item.verification_status === "CONFIRMED" ? "已确认" : "已排除"}</em>
                      )}
                    </div>
                  </article>
                ))}
              </div>
            </>
          )}
        </aside>
      </div>
    </section>
  );
}

function ReportsView({
  api,
  operatorId,
  apiBase,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  operatorId: string;
  apiBase: string;
}) {
  const [tasks, setTasks] = useState<TaskListItem[]>([]);
  useEffect(() => {
    api<TaskPage>(
      `/api/tasks?operator_id=${encodeURIComponent(operatorId)}&page_size=100`,
    )
      .then((page) => setTasks(page.items.filter((item) => item.latest_report)))
      .catch(() => setTasks([]));
  }, [api, operatorId]);
  return (
    <section className="workspace-page">
      <div className="page-intro">
        <div>
          <span className="eyebrow">VERSIONED REPORT ARCHIVE</span>
          <h2>正式风险排查报告</h2>
          <p>发现风险和排查后暂未发现明确风险都形成正式成果；每个版本绑定企业快照、评分、证据与运营确认。</p>
        </div>
      </div>
      <div className="report-grid">
        {tasks.length === 0 && (
          <div className="empty-state large operator-empty-state">
            <strong>当前运营人员尚无已生成报告</strong>
            <span>报告按任务归属隔离，当前身份为 {operatorId}</span>
            <Link href="/" prefetch={false}>发起风险排查任务</Link>
          </div>
        )}
        {tasks.map((item) => {
          const report = item.latest_report!;
          const reportDownloadReady =
            report.status === "GENERATED" &&
            item.confirmation_state === "VALID" &&
            item.next_action === "DOWNLOAD_REPORT";
          return (
            <article className="report-card" key={report.report_id}>
              <div className="doc-cover">
                <span>ATLAS</span>
                <strong>企业风险<br />监测分析报告</strong>
                <small>VERSION {report.report_version_no}</small>
              </div>
              <div className="report-info">
                <span className="eyebrow">{item.task.task_no}</span>
                <h3>{item.task.company_query}</h3>
                <div className="report-meta">
                  <span>生成时间</span>
                  <strong>{formatTime(report.generated_at)}</strong>
                  <span>排查结论</span>
                  <strong>
                    {investigationConclusion(
                      item.task.status,
                      item.risk,
                      item.evidence_progress,
                    ).label}
                  </strong>
                  <span>报告状态</span>
                  <strong>{report.status}</strong>
                </div>
                <div className="report-actions">
                  {reportDownloadReady ? (
                    <a
                      className="primary"
                      href={`${apiBase}/api/tasks/${item.task.task_id}/reports/${report.report_id}/download`}
                    >
                      下载 DOCX
                    </a>
                  ) : (
                    <Link
                      className="report-refresh-required"
                      href={`/tasks?id=${encodeURIComponent(item.task.task_id)}`}
                      prefetch={false}
                    >
                      需重新确认并生成新报告
                    </Link>
                  )}
                  <a
                    className="ghost"
                    href={`/reports/diff?taskId=${encodeURIComponent(item.task.task_id)}&reportId=${encodeURIComponent(report.report_id)}&company=${encodeURIComponent(item.task.company_query)}`}
                  >
                    查看差异
                  </a>
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function ReportDiffView({ api }: { api: <T>(path: string, options?: RequestInit) => Promise<T> }) {
  const [diff, setDiff] = useState<ReportDiff>();
  const locationSearch = useSyncExternalStore(
    subscribeLocationSearch,
    getLocationSearch,
    getServerLocationSearch,
  );
  const params = new URLSearchParams(locationSearch);
  const taskId = params.get("taskId");
  const reportId = params.get("reportId");
  const company = params.get("company") || "企业报告";
  const [error, setError] = useState<string>();
  useEffect(() => {
    if (!taskId || !reportId) return;
    api<ReportDiff>(`/api/tasks/${taskId}/reports/${reportId}/diff`).then(setDiff).catch((reason) => setError((reason as Error).message));
  }, [api, taskId, reportId]);
  const visibleError = error || (locationSearch && (!taskId || !reportId) ? "缺少任务或报告版本参数" : undefined);
  return (
    <section className="workspace-page report-diff-page">
      <div className="page-intro">
        <div><span className="eyebrow">VERSION COMPARISON</span><h2>{company}</h2><p>{diff?.summary || "正在读取版本差异…"}</p></div>
        <Link className="ghost" href="/reports" prefetch={false}>返回报告库</Link>
      </div>
      {visibleError && <div className="empty-state large"><strong>无法读取差异</strong><span>{visibleError}</span></div>}
      {!visibleError && !diff && <div className="empty-state large">正在比较报告版本…</div>}
      {diff && <>
        <div className="diff-version-hero">
          <div><span>上一版本</span><strong>{diff.previous_report_version_no ? `V${diff.previous_report_version_no}` : "无"}</strong><small>{diff.previous_report_date || "首版报告"}</small></div>
          <i>→</i>
          <div className="current"><span>当前版本</span><strong>V{diff.current_report_version_no}</strong><small>{diff.current_report_date}</small></div>
        </div>
        {[
          ["版本与生成信息", diff.company_changes],
          ["章节字段", diff.section_changes || []],
          ["表格与风险事项", diff.table_row_changes || []],
          ["主要结论", diff.conclusion_changes || []],
        ].map(([title, rows]) => <div className="diff-table-card" key={title as string}>
          <div className="diff-group-title">{title as string}</div>
          <div className="diff-table-head"><span>比较项</span><span>上一版本</span><span>当前版本</span><span>变化</span></div>
          {(rows as ReportDiff["company_changes"]).map((change) => {
            const changed = (change.before_value ?? "") !== (change.after_value ?? "");
            return <div className="diff-table-row" key={`${title}-${change.field}`}><strong>{change.field}</strong><span>{change.before_value ?? "—"}</span><span>{change.after_value ?? "—"}</span><em className={changed ? "changed" : "same"}>{changed ? "已变化" : "无变化"}</em></div>;
          })}
        </div>)}
        <p className="diff-scope-note">差异来自各报告绑定的数据快照和评分快照，可追溯到章节字段、表格行数、规则命中及最终结论；DOCX 像素级红线不作为业务判断依据。</p>
      </>}
    </section>
  );
}

function ConnectorManagementView({
  mode,
  api,
  notify,
  operatorId,
}: {
  mode: "DATA_SOURCE" | "SEARCH_MODEL";
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const [items, setItems] = useState<ConnectorOverviewView[]>([]);
  const [selectedKey, setSelectedKey] = useState<string>();
  const [selectedVersionId, setSelectedVersionId] = useState<string>();
  const [draft, setDraft] = useState<ConnectorDocument>();
  const [sample, setSample] = useState({ company_name: "映射预览企业", unified_credit_code: "911100PREVIEW", md5: "preview-source-1" });
  const [preview, setPreview] = useState<Record<string, unknown>>();
  const [busy, setBusy] = useState(false);

  const filtered = items.filter((item) => mode === "DATA_SOURCE"
    ? item.configuration.definition.category === "DATA_SOURCE"
    : item.configuration.definition.category === "SEARCH" || item.configuration.definition.category === "MODEL");
  const selectedItem = filtered.find((item) => item.configuration.definition.config_key === selectedKey) || filtered[0];
  const versions = selectedItem?.configuration.versions || [];
  const selectedVersion = versions.find((version) => version.version_id === selectedVersionId) || versions.find((version) => version.status === "DRAFT") || versions.find((version) => version.version_id === selectedItem?.configuration.binding?.active_version_id) || versions[0];
  const latestTest = selectedItem?.test_impacts.find((item) => item.version_id === selectedVersion?.version_id)?.latest_test;
  const testCurrent = Boolean(latestTest && selectedVersion && latestTest.version_checksum === selectedVersion.checksum);
  const editable = selectedVersion?.status === "DRAFT";
  const runtimeConsumed = draft?.category === "SEARCH" || draft?.category === "MODEL";
  const dirty = Boolean(draft && selectedVersion
    && JSON.stringify(draft) !== JSON.stringify(JSON.parse(selectedVersion.value_json)));

  const load = useCallback(async (key?: string, versionId?: string) => {
    try {
      const data = await api<ConnectorOverviewView[]>("/api/platform/connectors?environment=DEV");
      setItems(data);
      const visible = data.filter((item) => mode === "DATA_SOURCE"
        ? item.configuration.definition.category === "DATA_SOURCE"
        : item.configuration.definition.category !== "DATA_SOURCE");
      const target = visible.find((item) => item.configuration.definition.config_key === key) || visible[0];
      if (target) {
        setSelectedKey(target.configuration.definition.config_key);
        const version = target.configuration.versions.find((item) => item.version_id === versionId)
          || target.configuration.versions.find((item) => item.status === "DRAFT")
          || target.configuration.versions[0];
        if (version) {
          setSelectedVersionId(version.version_id);
          setDraft(JSON.parse(version.value_json));
        }
      }
    } catch (error) {
      notify((error as Error).message);
    }
  }, [api, mode, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function mutate(path: string, method: string, body: unknown, message: string) {
    setBusy(true);
    try {
      const result = await api<ConfigurationVersionView | ConnectorTestView>(path, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      notify(message);
      await load(selectedItem?.configuration.definition.config_key,
        "version_id" in result ? result.version_id : selectedVersion?.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function initialize(type: "ELASTICSEARCH" | "TAVILY" | "MODEL") {
    await mutate("/api/platform/connectors/initialize", "POST", { type, operator_id: operatorId }, "已创建连接器草稿，尚未影响运行环境");
  }

  async function mappingPreview() {
    if (!selectedVersion) return;
    setBusy(true);
    try {
      const value = await api<Record<string, unknown>>(`/api/platform/connectors/versions/${selectedVersion.version_id}/mapping-preview`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sample_record: sample }),
      });
      setPreview(value);
      notify("字段映射预览已生成");
    } catch (error) { notify((error as Error).message); } finally { setBusy(false); }
  }

  function choose(item: ConnectorOverviewView, version?: ConfigurationVersionView) {
    const chosen = version || item.configuration.versions.find((value) => value.status === "DRAFT") || item.configuration.versions[0];
    setSelectedKey(item.configuration.definition.config_key);
    setSelectedVersionId(chosen?.version_id);
    if (chosen) setDraft(JSON.parse(chosen.value_json));
    setPreview(undefined);
  }

  function setEndpoint(field: keyof ConnectorDocument["endpoint"], value: string | number) {
    if (draft) setDraft({ ...draft, endpoint: { ...draft.endpoint, [field]: value } });
  }
  function setSetting(field: string, value: unknown) {
    if (draft) setDraft({ ...draft, settings: { ...draft.settings, [field]: value } });
  }
  const searchScopes = Array.isArray(draft?.settings.source_scopes)
    ? draft.settings.source_scopes as Array<{
        code: string;
        label: string;
        topic: string;
        include_domains: string[];
        include_raw_content: boolean;
      }>
    : [];
  function setSearchScope(index: number, field: string, value: unknown) {
    const next = searchScopes.map((scope, scopeIndex) =>
      scopeIndex === index ? { ...scope, [field]: value } : scope);
    setSetting("source_scopes", next);
  }

  const title = mode === "DATA_SOURCE" ? "数据源连接档案" : "搜索与模型";
  const missingTypes = mode === "DATA_SOURCE"
    ? !filtered.some((item) => item.configuration.definition.category === "DATA_SOURCE")
      ? ["ELASTICSEARCH" as const] : []
    : ([!filtered.some((item) => item.configuration.definition.category === "SEARCH") ? "TAVILY" : null,
        !filtered.some((item) => item.configuration.definition.category === "MODEL") ? "MODEL" : null]
        .filter(Boolean) as Array<"TAVILY" | "MODEL">);

  return <section className="workspace-page management-page connector-console-page">
    <div className="management-hero compact-rule-hero">
      <div className="management-hero-index">{mode === "DATA_SOURCE" ? "B" : "C"}</div>
      <div className="management-hero-copy"><span className="eyebrow">CONTROLLED CONNECTOR REGISTRY</span><h2>{title}</h2><p>{mode === "DATA_SOURCE" ? "当前页面仅登记 ES 连接、索引和字段映射并执行连接测试；任务仍读取服务启动配置，因此这里的版本禁止发布。" : "Tavily 负责发现并保留可追溯来源；联网模型负责意图理解和证据语义研判。高置信、可引用且主体明确的判断由系统策略自动采纳，其余才转人工。"}</p></div>
      <div className="management-signal"><small>已登记连接器</small><strong>{filtered.length}</strong><button className="text-action" type="button" onClick={() => void load(selectedKey, selectedVersionId)}>刷新 ↗</button></div>
    </div>

    <div className="connector-cards">
      {filtered.map((item) => {
        const active = item.configuration.versions.find((version) => version.version_id === item.configuration.binding?.active_version_id);
        return <button type="button" className={item.configuration.definition.config_key === selectedItem?.configuration.definition.config_key ? "selected" : ""} key={item.configuration.definition.config_key} onClick={() => choose(item)}>
          <span>{item.configuration.definition.category}</span><strong>{item.configuration.definition.display_name}</strong><small>{item.configuration.definition.category === "DATA_SOURCE" ? "连接档案 · 不参与任务" : (active ? `生效 V${active.version_no}` : "尚未发布")}</small>
        </button>;
      })}
      {missingTypes.map((type) => <button className="add-connector" type="button" key={type} disabled={busy} onClick={() => void initialize(type)}><span>＋</span><strong>登记 {type === "ELASTICSEARCH" ? "Elasticsearch" : type === "TAVILY" ? "Tavily" : "联网模型"}</strong><small>只创建草稿</small></button>)}
    </div>

    {selectedItem && selectedVersion && draft && <>
      <div className="connector-version-row"><strong>{selectedItem.configuration.definition.display_name}</strong><div>{versions.map((version) => <button type="button" className={version.version_id === selectedVersion.version_id ? "selected" : ""} key={version.version_id} onClick={() => choose(selectedItem, version)}>V{version.version_no} · {configurationVersionLabel(version.status)}</button>)}</div><button className="secondary" type="button" disabled={busy} onClick={() => void mutate(`/api/platform/configurations/${selectedItem.configuration.definition.config_key}/drafts`, "POST", { value_json: JSON.stringify(draft), operator_id: operatorId }, "已创建新草稿")}>新建草稿</button></div>
      <div className="connector-workbench">
        <div className="connector-editor">
          {!runtimeConsumed && <p className="validation-note">此版本仅用于连接测试和结构预览，不会冻结到新任务，也不会改变当前生产查询链路。</p>}
          <div className="rule-section-heading"><div><span className="eyebrow">01 / ENDPOINT</span><h3>连接与失败策略</h3></div><small>{editable ? "可编辑草稿" : "只读版本"}</small></div>
          <div className="connector-form-grid">
            <label className="toggle-field"><span>启用</span><input type="checkbox" disabled={!editable || !runtimeConsumed} checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} /></label>
            <label className="toggle-field"><span>必查来源</span><input type="checkbox" disabled={!editable || !runtimeConsumed} checked={draft.required} onChange={(event) => setDraft({ ...draft, required: event.target.checked, failure_policy: event.target.checked ? "STOP" : "OPTIONAL" })} /></label>
            <label><span>失败策略（由必查属性确定）</span><input disabled value={draft.required ? "失败即停止" : "可选来源"} /></label>
            <label className="wide"><span>服务地址</span><input disabled={!editable} value={draft.endpoint.base_url} onChange={(event) => setEndpoint("base_url", event.target.value)} /></label>
            <label><span>接口路径</span><input disabled={!editable} value={draft.endpoint.path} onChange={(event) => setEndpoint("path", event.target.value)} /></label>
            <label><span>连接超时（毫秒）</span><input type="number" disabled={!editable} value={draft.endpoint.connect_timeout_ms} onChange={(event) => setEndpoint("connect_timeout_ms", Number(event.target.value))} /></label>
            <label><span>请求超时（毫秒）</span><input type="number" disabled={!editable} value={draft.endpoint.request_timeout_ms} onChange={(event) => setEndpoint("request_timeout_ms", Number(event.target.value))} /></label>
            {draft.category !== "DATA_SOURCE" && <label className="wide"><span>服务器密钥引用（不是密钥值）</span><input disabled={!editable} value={draft.credential_ref || ""} onChange={(event) => setDraft({ ...draft, credential_ref: event.target.value })} placeholder="env:ATLAS_SEARCH_PRIMARY_API_KEY" /></label>}
          </div>

          {draft.category === "DATA_SOURCE" && <>
            <div className="rule-section-heading"><div><span className="eyebrow">02 / INDICES & MAPPING</span><h3>索引与字段映射</h3></div><small>用样本记录先预览再发布</small></div>
            <div className="connector-form-grid">{Object.entries(draft.indices || {}).map(([key, value]) => <label key={key}><span>{key} 索引</span><input disabled={!editable} value={value} onChange={(event) => setDraft({ ...draft, indices: { ...draft.indices, [key]: event.target.value } })} /></label>)}{Object.entries(draft.field_mapping || {}).map(([key, value]) => <label key={key}><span>{key} ← 源字段</span><input disabled={!editable} value={value} onChange={(event) => setDraft({ ...draft, field_mapping: { ...draft.field_mapping, [key]: event.target.value } })} /></label>)}</div>
            <div className="mapping-preview"><div><label>企业名称<input value={sample.company_name} onChange={(event) => setSample({ ...sample, company_name: event.target.value })} /></label><label>信用代码<input value={sample.unified_credit_code} onChange={(event) => setSample({ ...sample, unified_credit_code: event.target.value })} /></label><label>源 ID<input value={sample.md5} onChange={(event) => setSample({ ...sample, md5: event.target.value })} /></label><button className="secondary" type="button" disabled={busy} onClick={() => void mappingPreview()}>预览映射</button></div>{preview && <pre>{JSON.stringify(preview, null, 2)}</pre>}</div>
          </>}

          {draft.category === "SEARCH" && <>
            <div className="rule-section-heading"><div><span className="eyebrow">02 / DISCOVERY POLICY</span><h3>身份词与来源聚合</h3></div><small>召回阶段不使用风险标签</small></div>
            <div className="search-policy-summary">
              <strong>企业全称、已确认简称、品牌、门店和曾用名</strong>
              <span>仅用于主体召回；网页全文交给 Atlas 识别风险事实，最终评分仍由确定性规则完成。</span>
              <span>投诉平台当前覆盖：黑猫投诉、啄木鸟消费投诉、消费保。</span>
            </div>
            <div className="connector-form-grid">
              <label><span>搜索深度</span><select disabled={!editable} value={String(draft.settings.search_depth)} onChange={(event) => setSetting("search_depth", event.target.value)}><option value="basic">基础</option><option value="advanced">深度</option></select></label>
              <label><span>每路最多结果</span><input type="number" disabled={!editable} value={Number(draft.settings.max_results)} onChange={(event) => setSetting("max_results", Number(event.target.value))} /></label>
              <label><span>执行策略</span><input disabled value={draft.settings.strategy === "IDENTITY_SOURCE_AGGREGATION" ? "身份词 × 来源范围" : "旧版风险词模板"} /></label>
            </div>
            {searchScopes.length > 0 ? <div className="search-scope-list">
              {searchScopes.map((scope, index) => <article key={scope.code}>
                <div><span>{scope.code}</span><strong>{scope.label}</strong><small>{scope.include_domains.length ? `限定 ${scope.include_domains.length} 个来源域名` : "全网公开网页"}</small></div>
                <label><span>来源域名（每行一个）</span><textarea disabled={!editable} value={scope.include_domains.join("\n")} onChange={(event) => setSearchScope(index, "include_domains", event.target.value.split("\n").map((item) => item.trim()).filter(Boolean))} /></label>
                <label className="toggle-field"><span>读取网页正文</span><input type="checkbox" disabled={!editable} checked={scope.include_raw_content} onChange={(event) => setSearchScope(index, "include_raw_content", event.target.checked)} /></label>
              </article>)}
            </div> : <div className="legacy-search-warning"><strong>当前是旧版风险词模板</strong><span>新建身份词聚合版本后，将不再用“失联、欠薪、闭店”等标签限制搜索召回。</span></div>}
          </>}
          {draft.category === "MODEL" && <><div className="rule-section-heading"><div><span className="eyebrow">02 / MODEL POLICY</span><h3>意图理解与自动证据研判</h3></div></div><div className="connector-form-grid"><label><span>模型名称</span><input disabled={!editable} value={String(draft.settings.model)} onChange={(event) => setSetting("model", event.target.value)} /></label><label><span>温度</span><input type="number" min="0" max="2" step="0.1" disabled={!editable} value={Number(draft.settings.temperature ?? 0.1)} onChange={(event) => setSetting("temperature", Number(event.target.value))} /></label><label><span>最大输出 Token</span><input type="number" min="64" max="32768" disabled={!editable} value={Number(draft.settings.max_tokens ?? 4096)} onChange={(event) => setSetting("max_tokens", Number(event.target.value))} /></label><label><span>引用门槛</span><input type="number" min="0" max="1" step="0.05" disabled={!editable} value={Number(draft.settings.citation_threshold)} onChange={(event) => setSetting("citation_threshold", Number(event.target.value))} /></label><label className="toggle-field"><span>意图理解</span><input type="checkbox" disabled={!editable} checked={Boolean(draft.settings.intent_enabled ?? true)} onChange={(event) => setSetting("intent_enabled", event.target.checked)} /></label><label className="toggle-field"><span>证据自动研判</span><input type="checkbox" disabled={!editable} checked={Boolean(draft.settings.evidence_review_enabled ?? true)} onChange={(event) => setSetting("evidence_review_enabled", event.target.checked)} /></label><label className="toggle-field"><span>自动采纳高置信判断</span><input type="checkbox" disabled={!editable} checked={Boolean(draft.settings.automatic_evidence_decision_enabled ?? true)} onChange={(event) => setSetting("automatic_evidence_decision_enabled", event.target.checked)} /></label><label><span>自动采纳置信度</span><input type="number" min="0.8" max="1" step="0.01" disabled={!editable} value={Number(draft.settings.automatic_decision_threshold ?? 0.9)} onChange={(event) => setSetting("automatic_decision_threshold", Number(event.target.value))} /></label><label className="wide"><span>研判提示词</span><textarea disabled={!editable} value={String(draft.settings.prompt_template)} onChange={(event) => setSetting("prompt_template", event.target.value)} /></label></div></>}
        </div>

        <aside className="rule-release-panel"><span className="eyebrow">VALIDATION & RELEASE</span><h3>{runtimeConsumed ? "测试与发布" : "连接档案检查"}</h3><dl><div><dt>版本状态</dt><dd>V{selectedVersion.version_no} · {configurationVersionLabel(selectedVersion.status)}{dirty ? " · 有未保存修改" : ""}</dd></div><div><dt>连接测试</dt><dd className={testCurrent && latestTest?.status === "PASSED" ? "passed" : "blocked"}>{latestTest ? `${latestTest.status === "PASSED" ? "已通过" : "未通过"} · ${latestTest.latency_ms}ms${testCurrent ? "" : "（配置已变化）"}` : "尚未测试"}</dd></div><div><dt>任务消费</dt><dd>{runtimeConsumed ? "新任务冻结并执行" : "未接入，禁止发布"}</dd></div></dl>{latestTest && <p className="validation-note">{latestTest.message}</p>}<div className="release-actions">{editable && <button className="primary" type="button" disabled={busy || !dirty} onClick={() => void mutate(`/api/platform/configurations/versions/${selectedVersion.version_id}`, "PUT", { expected_row_version: selectedVersion.row_version, value_json: JSON.stringify(draft), operator_id: operatorId }, "连接器草稿已保存")}>保存草稿</button>} {(editable || selectedVersion.status === "VALIDATED") && <button className="secondary" type="button" disabled={busy || dirty} onClick={() => void mutate(`/api/platform/connectors/versions/${selectedVersion.version_id}/tests`, "POST", { operator_id: operatorId, sample_record: draft.category === "DATA_SOURCE" ? sample : undefined }, "连接测试已完成")}>测试连接</button>}{editable && <button className="secondary" type="button" disabled={busy || dirty} onClick={() => void mutate(`/api/platform/configurations/versions/${selectedVersion.version_id}/validate`, "POST", { expected_row_version: selectedVersion.row_version, operator_id: operatorId }, "配置结构校验通过")}>校验配置</button>}{runtimeConsumed && selectedVersion.status === "VALIDATED" && <button className="primary" type="button" disabled={busy || (draft.enabled && (!testCurrent || latestTest?.status !== "PASSED"))} onClick={() => void mutate(`/api/platform/configurations/versions/${selectedVersion.version_id}/publish`, "POST", { environment: "DEV", idempotency_key: `connector-publish-${selectedVersion.version_id}-${Date.now()}`, operator_id: operatorId }, "连接器已发布，只影响新任务")}>发布版本</button>}{runtimeConsumed && selectedVersion.status === "INACTIVE" && <button className="secondary" type="button" disabled={busy} onClick={() => void mutate(`/api/platform/configurations/versions/${selectedVersion.version_id}/rollback`, "POST", { environment: "DEV", idempotency_key: `connector-rollback-${selectedVersion.version_id}-${Date.now()}`, operator_id: operatorId }, "已回滚到所选版本")}>回滚版本</button>}</div><p className="release-help">{runtimeConsumed ? "页面只保存密钥引用；发布版本只影响之后创建的新任务。" : "可保存并测试连接档案，但后台同样会拒绝发布，避免产生虚假的任务生效状态。"}</p></aside>
      </div>
    </>}
  </section>;
}

function FactScoringMatrix({ traceability }: { traceability?: LegacyRiskTraceability }) {
  if (!traceability) return <div className="fact-scoring-panel loading-panel">风险事实口径读取中…</div>;
  return <section className="fact-scoring-panel" aria-label="风险事实到评分">
    <div className="fact-scoring-heading">
      <div><span className="eyebrow">FACT → DECISION → SCORE</span><h3>风险事实如何进入评分</h3><p>搜索负责发现，Agent 负责识别与核验，只有确定性规则可以计算风险分。</p></div>
      <div className="fact-score-flow" aria-label="评分处理流程"><span>事实来源</span><i>→</i><span>主体与时效</span><i>→</i><span>证据确认</span><i>→</i><span>规则计分</span></div>
    </div>
    <div className="fact-source-legend"><span data-source="structured">结构化数据优先</span><span data-source="public">公开网页与投诉补充</span><span data-source="boundary">标签不等于分数</span></div>
    <div className="traceability-table-wrap">
      <table className="traceability-table fact-scoring-table">
        <thead><tr><th>风险事实</th><th>主要来源</th><th>认定条件</th><th>时间窗口</th><th>评分处理</th><th>运行状态</th></tr></thead>
        <tbody>{traceability.fact_scoring_catalog.map((item) => <tr key={item.risk_type}>
          <td><strong>{item.risk_name}</strong><small>{item.risk_type}</small></td>
          <td>{item.primary_source}</td><td>{item.recognition_condition}</td><td>{item.time_window}</td><td>{item.score_handling}</td>
          <td><span className="traceability-status" data-status={item.runtime_state}>{item.runtime_state}</span></td>
        </tr>)}</tbody>
      </table>
    </div>
  </section>;
}

function RiskTraceabilityPanel({ traceability }: { traceability?: LegacyRiskTraceability }) {
  if (!traceability) {
    return <div className="traceability-panel loading-panel">旧模型追溯信息读取中…</div>;
  }
  const migrated = traceability.calculation_rules.filter((item) => item.migration_status === "已迁移").length;
  const resolved = traceability.calculation_rules.filter((item) => ["已纠错迁移", "V1审计修正", "V1不恢复", "V1新增"].includes(item.migration_status)).length;
  const dependent = traceability.calculation_rules.filter((item) => ["沿用物化分", "缺数据依赖"].includes(item.migration_status)).length;
  const missing = traceability.calculation_rules.filter((item) => item.migration_status === "缺失").length;
  const pending = traceability.calculation_rules.filter((item) => item.migration_status === "待业务确认").length;
  return (
    <section className="traceability-panel" aria-label="旧评分模型追溯">
      <div className="traceability-heading">
        <div>
          <span className="eyebrow">LEGACY MODEL TRACEABILITY</span>
          <h3>旧评分模型执行与迁移追溯</h3>
          <p>{traceability.current_runtime_description}</p>
        </div>
        <span className="runtime-mode">当前真实执行口径</span>
      </div>
      <div className="traceability-summary">
        <div><span>风险字典</span><strong>{traceability.risk_dictionary.length}/39</strong><small>已逐项登记</small></div>
        <div><span>规则已迁移</span><strong>{migrated}</strong><small>具备新实现</small></div>
        <div><span>已明确处置</span><strong>{resolved}</strong><small>纠错、审计或范围决策</small></div>
        <div><span>依赖上游</span><strong>{dependent}</strong><small>尚不能完整重算</small></div>
        <div><span>缺失 / 待确认</span><strong>{missing + pending}</strong><small>{missing} 项缺实现，{pending} 项待口径</small></div>
        <div><span>隐含活动标签</span><strong>{traceability.active_hard_coded_labels.length}</strong><small>RiskEnum外已登记</small></div>
      </div>
      <div className="traceability-table-wrap">
        <table className="traceability-table">
          <thead><tr><th>旧代码规则</th><th>新系统实现</th><th>状态</th><th>是否可配置</th><th>真实执行条件</th><th>说明</th></tr></thead>
          <tbody>
            {traceability.calculation_rules.map((item) => (
              <tr key={item.rule_code}>
                <td><strong>{item.legacy_source}</strong><small>{item.rule_code}</small></td>
                <td>{item.new_implementation}</td>
                <td><span className="traceability-status" data-status={item.migration_status}>{item.migration_status}</span></td>
                <td>{item.configurable ? "是" : "否"}</td>
                <td>{item.runtime_condition}</td>
                <td>{item.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details className="dictionary-details">
        <summary>查看RiskEnum 39项逐项对应关系</summary>
        <div className="traceability-table-wrap">
          <table className="traceability-table dictionary-table">
            <thead><tr><th>旧枚举</th><th>标签编号</th><th>风险名称</th><th>新类型</th><th>执行位置</th><th>状态</th></tr></thead>
            <tbody>{traceability.risk_dictionary.map((item) => (
              <tr key={item.legacy_name}>
                <td>{item.legacy_name}</td><td>{item.legacy_label_no}</td><td>{item.label_name}</td>
                <td>{item.canonical_type}</td><td>{item.runtime_handling}</td>
                <td><span className="traceability-status" data-status={item.migration_status}>{item.migration_status}</span></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </details>
      <details className="dictionary-details">
        <summary>查看RiskEnum外仍参与打分的历史标签</summary>
        <div className="traceability-table-wrap">
          <table className="traceability-table dictionary-table">
            <thead><tr><th>标签编号</th><th>旧名称</th><th>优先分</th><th>适用分支</th><th>来源</th><th>说明</th></tr></thead>
            <tbody>{traceability.active_hard_coded_labels.map((item) => (
              <tr key={item.legacy_label_no}>
                <td>{item.legacy_label_no}</td><td>{item.label_name}</td><td>{item.priority_score}</td>
                <td>{item.scoring_profiles}</td><td>{item.source_evidence}</td><td>{item.note}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </details>
      <details className="dictionary-details">
        <summary>查看完整重算所需输入与当前数据就绪度</summary>
        <div className="traceability-table-wrap">
          <table className="traceability-table dictionary-table">
            <thead><tr><th>评分输入</th><th>旧来源</th><th>Atlas来源</th><th>就绪度</th><th>完整重算必需</th><th>说明</th></tr></thead>
            <tbody>{traceability.feature_requirements.map((item) => (
              <tr key={item.feature_name}>
                <td>{item.feature_name}</td><td>{item.legacy_source}</td><td>{item.atlas_source}</td>
                <td><span className="traceability-status" data-status={item.readiness}>{item.readiness}</span></td>
                <td>{item.required_for_full_recalculation ? "是" : "否（回退）"}</td><td>{item.note}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </details>
    </section>
  );
}

function RiskRulesManagementView({
  api,
  notify,
  operatorId,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const [overview, setOverview] = useState<RiskRuleOverview>();
  const [traceability, setTraceability] = useState<LegacyRiskTraceability>();
  const [selectedId, setSelectedId] = useState<string>();
  const [draft, setDraft] = useState<RiskPolicyDocument>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async (preferredId?: string) => {
    setLoading(true);
    try {
      const [data, traceabilityData] = await Promise.all([
        api<RiskRuleOverview>("/api/platform/risk-rules?environment=DEV"),
        api<LegacyRiskTraceability>("/api/platform/risk-rules/traceability"),
      ]);
      setOverview(data);
      setTraceability(traceabilityData);
      const policy = data.policies[0];
      if (policy) {
        const versions = policy.configuration.versions;
        const nextId = preferredId && versions.some((item) => item.version_id === preferredId)
          ? preferredId
          : versions.find((item) => item.status === "DRAFT")?.version_id
            || policy.configuration.binding?.active_version_id
            || versions[0]?.version_id;
        setSelectedId(nextId);
        const version = versions.find((item) => item.version_id === nextId);
        if (version?.value_json) setDraft(JSON.parse(version.value_json));
      } else {
        setSelectedId(undefined);
        setDraft(JSON.parse(data.default_policy_json));
      }
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [api, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const policyOverview = overview?.policies[0];
  const versions = policyOverview?.configuration.versions || [];
  const selected = versions.find((item) => item.version_id === selectedId);
  const activeId = policyOverview?.configuration.binding?.active_version_id;
  const active = versions.find((item) => item.version_id === activeId);
  const impact = policyOverview?.version_impacts.find((item) => item.version_id === selectedId);
  const replayCurrent = Boolean(
    impact?.latest_replay && selected
      && impact.latest_replay.version_checksum === selected.checksum,
  );
  const editable = selected?.status === "DRAFT";
  const changedSections = selected && active && selected.version_id !== active.version_id
    ? Object.keys(JSON.parse(selected.value_json || "{}") as Record<string, unknown>)
        .filter((key) => JSON.stringify((JSON.parse(selected.value_json) as Record<string, unknown>)[key])
          !== JSON.stringify((JSON.parse(active.value_json) as Record<string, unknown>)[key])).length
    : 0;

  function chooseVersion(version: ConfigurationVersionView) {
    setSelectedId(version.version_id);
    setDraft(JSON.parse(version.value_json));
  }

  async function initialize() {
    setSaving(true);
    try {
      await api("/api/platform/risk-rules/initialize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ operator_id: operatorId }),
      });
      notify("已建立首个规则草稿，尚未发布，不会影响现有任务");
      await load();
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function createDraft() {
    if (!draft) return;
    setSaving(true);
    try {
      const created = await api<ConfigurationVersionView>(
        "/api/platform/configurations/risk.rules.v1/drafts",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ value_json: JSON.stringify(draft), operator_id: operatorId }),
        },
      );
      notify(`已创建 V${created.version_no} 草稿`);
      await load(created.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function saveDraft() {
    if (!draft || !selected || !editable) return;
    setSaving(true);
    try {
      const saved = await api<ConfigurationVersionView>(
        `/api/platform/configurations/versions/${selected.version_id}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            expected_row_version: selected.row_version,
            value_json: JSON.stringify(draft),
            operator_id: operatorId,
          }),
        },
      );
      notify("规则草稿已保存，生效版本未改变");
      await load(saved.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function validateDraft() {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/platform/configurations/versions/${selected.version_id}/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expected_row_version: selected.row_version, operator_id: operatorId }),
      });
      notify("规则结构校验通过；发布仍需黄金样本回放通过");
      await load(selected.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function replaySeedSamples() {
    if (!selected) return;
    const samples = [
      { sample_id: "seed-low", legacy_score: 1.5, risk_types: [], expected_score: 1.5, expected_level: "LOW" },
      { sample_id: "seed-lost-contact", legacy_score: 2, risk_types: ["OUT_OF_CONTACT"], expected_score: 6, expected_level: "MEDIUM_HIGH" },
      { sample_id: "seed-wage", legacy_score: 7.25, risk_types: ["WAGE_ARREARS"], expected_score: 7.25, expected_level: "MEDIUM_HIGH" },
      { sample_id: "seed-closure", legacy_score: 0, risk_types: ["STORE_CLOSURE"], expected_score: 8, expected_level: "HIGH" },
      { sample_id: "seed-multiple", legacy_score: 4, risk_types: ["OUT_OF_CONTACT", "WAGE_ARREARS", "STORE_CLOSURE"], expected_score: 8, expected_level: "HIGH" },
    ];
    setSaving(true);
    try {
      const result = await api<RiskRuleReplay>(
        `/api/platform/risk-rules/versions/${selected.version_id}/replays`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ operator_id: operatorId, samples }),
        },
      );
      notify(result.status === "PASSED"
        ? "黄金样本回放通过"
        : `开发样本 ${result.passed_count}/${result.sample_count} 通过；正式发布仍需至少 20 份黄金样本`);
      await load(selected.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function release(action: "publish" | "rollback") {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/platform/configurations/versions/${selected.version_id}/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          environment: "DEV",
          idempotency_key: `${action}-${selected.version_id}-${Date.now()}`,
          operator_id: operatorId,
        }),
      });
      notify(action === "publish" ? "规则版本已发布，只影响之后创建的任务" : "已回滚到所选历史规则版本");
      await load(selected.version_id);
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  function setFloor(type: string, value: number) {
    if (!draft) return;
    setDraft({
      ...draft,
      event_floors: draft.event_floors.map((item) =>
        item.risk_type === type ? { ...item, minimum_score: value } : item),
    });
  }

  if (!policyOverview) {
    return (
      <section className="workspace-page management-page rule-console-page">
        <div className="management-hero compact-rule-hero">
          <div className="management-hero-index">D</div>
          <div className="management-hero-copy">
            <span className="eyebrow">DETERMINISTIC RISK POLICY</span>
            <h2>规则与评分</h2>
            <p>建立首个受控草稿后，才能编辑、回放、发布和回滚。初始化不会改变当前线上评分。</p>
          </div>
          <div className="management-signal"><small>当前状态</small><strong>{loading ? "读取中" : "尚未初始化"}</strong></div>
        </div>
        <div className="rule-empty-state">
          <strong>当前仍使用代码内置的 RISK_RULES_V1</strong>
          <p>点击后只创建草稿。未通过至少 20 份黄金样本回放前，系统会拒绝发布。</p>
          <button className="primary" type="button" disabled={saving || loading} onClick={() => void initialize()}>
            创建首个规则草稿
          </button>
        </div>
        <FactScoringMatrix traceability={traceability} />
        <RiskTraceabilityPanel traceability={traceability} />
      </section>
    );
  }

  return (
    <section className="workspace-page management-page rule-console-page">
      <div className="management-hero compact-rule-hero">
        <div className="management-hero-index">D</div>
        <div className="management-hero-copy">
          <span className="eyebrow">DETERMINISTIC RISK POLICY</span>
          <h2>规则与评分</h2>
          <p>编辑草稿、查看历史差异和样本影响；生效版本不可直接修改，新发布只作用于新任务。</p>
        </div>
        <div className="management-signal">
          <small>当前生效</small>
          <strong>{active ? `V${active.version_no}` : "未发布"}</strong>
          <button className="text-action" type="button" onClick={() => void load(selectedId)}>刷新版本 ↗</button>
        </div>
      </div>

      <FactScoringMatrix traceability={traceability} />
      <RiskTraceabilityPanel traceability={traceability} />

      <div className="rule-version-bar">
        <div>
          <span className="eyebrow">VERSION HISTORY</span>
          <strong>规则版本</strong>
        </div>
        <div className="rule-version-list">
          {versions.map((version) => (
            <button
              type="button"
              key={version.version_id}
              className={version.version_id === selectedId ? "selected" : ""}
              onClick={() => chooseVersion(version)}
            >
              <b>V{version.version_no}</b>
              <span>{version.status === "DRAFT" ? "草稿" : version.status === "VALIDATED" ? "已校验" : version.status === "PUBLISHED" ? "生效中" : "历史版本"}</span>
              {version.version_id === activeId && <i>当前</i>}
            </button>
          ))}
        </div>
        <button className="secondary" type="button" disabled={saving} onClick={() => void createDraft()}>新建草稿</button>
      </div>

      <div className="rule-workbench">
        <div className="rule-editor">
          <div className="rule-section-heading">
            <div><span className="eyebrow">01 / SCORE LEVELS</span><h3>风险等级阈值</h3></div>
            <small>{editable ? "可编辑草稿" : "只读版本"}</small>
          </div>
          <div className="threshold-grid">
            {draft && ([
              ["high_min", "高风险起点"],
              ["medium_high_min", "中高风险起点"],
              ["medium_min", "中风险起点"],
              ["medium_low_min", "中低风险起点"],
            ] as const).map(([key, label]) => (
              <label key={key}><span>{label}（V1 固定）</span><input type="number" disabled value={draft.thresholds[key]} /></label>
            ))}
          </div>

          <div className="rule-section-heading">
            <div><span className="eyebrow">02 / EVENT FLOOR</span><h3>重大事件最低分</h3></div>
            <small>必须有已确认事件和证据</small>
          </div>
          <div className="event-floor-list">
            {draft?.event_floors.map((item) => (
              <div key={item.risk_type}>
                <span>{item.risk_type === "OUT_OF_CONTACT" ? "失联" : item.risk_type === "WAGE_ARREARS" ? "拖欠工资" : item.risk_type === "STORE_CLOSURE" ? "门店关闭" : item.risk_type}</span>
                <small>{item.evidence_required ? "需要证据" : "无需证据"}</small>
                <label>最低 <input type="number" min="0" max="10" step="0.1" disabled={!editable} value={item.minimum_score} onChange={(event) => setFloor(item.risk_type, Number(event.target.value))} /> 分</label>
              </div>
            ))}
          </div>

          <div className="rule-section-heading">
            <div><span className="eyebrow">03 / TIME & WEIGHTS</span><h3>时间窗口与基础权重</h3></div>
            <small>修改会进入样本影响回放</small>
          </div>
          {draft && <>
            <div className="threshold-grid two-columns">
              <label><span>风险事件窗口（天）</span><input type="number" disabled={!editable} value={draft.time_windows.risk_event_days} onChange={(event) => setDraft({ ...draft, time_windows: { ...draft.time_windows, risk_event_days: Number(event.target.value) } })} /></label>
              <label><span>工商变化窗口（天）</span><input type="number" disabled={!editable} value={draft.time_windows.company_change_days} onChange={(event) => setDraft({ ...draft, time_windows: { ...draft.time_windows, company_change_days: Number(event.target.value) } })} /></label>
            </div>
            <div className="weight-grid">
              {Object.entries(draft.rule_weights).map(([code, value]) => (
                <label key={code}><span>{code.replace("LEGACY_", "")}</span><input type="number" min="0" max="10" step="0.1" disabled={!editable} value={value} onChange={(event) => setDraft({ ...draft, rule_weights: { ...draft.rule_weights, [code]: Number(event.target.value) } })} /></label>
              ))}
            </div>
          </>}

          <div className="rule-section-heading">
            <div><span className="eyebrow">04 / LABEL BOUNDARY</span><h3>标签与评分边界</h3></div>
          </div>
          <div className="risk-label-table">
            <p className="inline-empty">风险标签用于归类已经确认的事实，不等于直接加分。当前只有旧模型已迁移规则以及失联、欠薪、闭店最低分会实际计算；其余标签的来源、认定条件和评分状态请查看上方“风险事实如何进入评分”。</p>
          </div>
        </div>

        <aside className="rule-release-panel">
          <span className="eyebrow">RELEASE CONTROL</span>
          <h3>校验与发布</h3>
          <dl>
            <div><dt>所选版本</dt><dd>{selected ? `V${selected.version_no} · ${selected.status}` : "—"}</dd></div>
            <div><dt>相对生效版本</dt><dd>{selected?.version_id === activeId ? "当前生效版本" : `${changedSections} 个配置区有变化`}</dd></div>
            <div><dt>影响任务</dt><dd>{impact?.task_usage_count || 0} 个历史任务使用</dd></div>
            <div><dt>最近回放</dt><dd>{impact?.latest_replay ? `${impact.latest_replay.passed_count}/${impact.latest_replay.sample_count} 通过${replayCurrent ? "" : "（草稿已变化）"}` : "尚未回放"}</dd></div>
            <div><dt>发布门禁</dt><dd className={replayCurrent && impact?.latest_replay?.status === "PASSED" ? "passed" : "blocked"}>{replayCurrent && impact?.latest_replay?.status === "PASSED" ? "已通过" : `需要 ${draft?.replay_gate.minimum_samples || 20} 份黄金样本`}</dd></div>
          </dl>
          {selected?.validation_message && <p className="validation-note">{selected.validation_message}</p>}
          <div className="release-actions">
            {editable && <button className="primary" type="button" disabled={saving} onClick={() => void saveDraft()}>保存草稿</button>}
            {editable && <button className="secondary" type="button" disabled={saving} onClick={() => void replaySeedSamples()}>回放 5 份开发样本</button>}
            {editable && <button className="secondary" type="button" disabled={saving} onClick={() => void validateDraft()}>校验规则结构</button>}
            {selected?.status === "VALIDATED" && <button className="primary" type="button" disabled={saving || !replayCurrent || impact?.latest_replay?.status !== "PASSED"} onClick={() => void release("publish")}>发布为生效版本</button>}
            {selected?.status === "INACTIVE" && <button className="secondary" type="button" disabled={saving} onClick={() => void release("rollback")}>回滚到此版本</button>}
          </div>
          <p className="release-help">发布后只影响新建任务。已有任务继续使用创建时冻结的规则版本，不会被重算。</p>
        </aside>
      </div>
    </section>
  );
}

function SkillManagementView({
  api,
  notify,
  operatorId,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const [items, setItems] = useState<ConfigurationOverviewView[]>([]);
  const [configKey, setConfigKey] = useState("");
  const [versionId, setVersionId] = useState("");
  const [draft, setDraft] = useState<SkillDocument>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async (preferredVersion?: string) => {
    try {
      const values = await api<ConfigurationOverviewView[]>("/api/platform/skills?environment=DEV");
      setItems(values);
      const selectedConfig = values.find((item) => item.definition.config_key === configKey) || values[0];
      if (!selectedConfig) return;
      setConfigKey(selectedConfig.definition.config_key);
      const selectedVersion = selectedConfig.versions.find((item) => item.version_id === preferredVersion)
        || selectedConfig.versions[0];
      if (selectedVersion) {
        setVersionId(selectedVersion.version_id);
        setDraft(JSON.parse(selectedVersion.value_json) as SkillDocument);
      }
    } catch (error) {
      notify((error as Error).message);
    }
  }, [api, configKey, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const selectedConfig = items.find((item) => item.definition.config_key === configKey) || items[0];
  const selected = selectedConfig?.versions.find((item) => item.version_id === versionId)
    || selectedConfig?.versions[0];
  const activeVersion = selectedConfig?.versions.find(
    (item) => item.version_id === selectedConfig.binding?.active_version_id,
  );

  const dirty = Boolean(draft && selected
    && JSON.stringify(draft) !== JSON.stringify(JSON.parse(selected.value_json)));
  const editable = selected?.status === "DRAFT";

  async function initialize() {
    setSaving(true);
    try {
      await api("/api/platform/skills/initialize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ environment: "DEV", operator_id: operatorId }),
      });
      notify("5 个内置 Skill 已登记，可分别创建草稿和发布");
      await load();
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function createDraft() {
    if (!selectedConfig || !selected) return;
    setSaving(true);
    try {
      const value = await api<ConfigurationVersionView>(
        `/api/platform/configurations/${encodeURIComponent(selectedConfig.definition.config_key)}/drafts`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ value_json: selected.value_json, operator_id: operatorId }),
        },
      );
      await load(value.version_id);
      notify("已创建新的 Skill 草稿");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function saveDraft() {
    if (!selected || !draft || !editable) return;
    setSaving(true);
    try {
      const value = await api<ConfigurationVersionView>(
        `/api/platform/configurations/versions/${selected.version_id}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            expected_row_version: selected.row_version,
            value_json: JSON.stringify(draft),
            operator_id: operatorId,
          }),
        },
      );
      await load(value.version_id);
      notify("Skill 草稿已保存；原生执行器没有被替换");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function validateDraft() {
    if (!selected || dirty) return;
    setSaving(true);
    try {
      const value = await api<ConfigurationVersionView>(
        `/api/platform/configurations/versions/${selected.version_id}/validate`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ expected_row_version: selected.row_version, operator_id: operatorId }),
        },
      );
      await load(value.version_id);
      notify("输入输出契约、参数和依赖校验通过");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function release(action: "publish" | "rollback") {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/platform/configurations/versions/${selected.version_id}/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          environment: "DEV",
          idempotency_key: `${action}-skill-${selected.version_id}-${Date.now()}`,
          operator_id: operatorId,
        }),
      });
      await load(selected.version_id);
      notify(action === "publish" ? "Skill 版本已发布，只影响新任务" : "Skill 已回滚");
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setSaving(false);
    }
  }

  function selectConfiguration(value: ConfigurationOverviewView) {
    const version = value.versions[0];
    setConfigKey(value.definition.config_key);
    setVersionId(version?.version_id || "");
    setDraft(version?.value_json ? JSON.parse(version.value_json) as SkillDocument : undefined);
  }

  function selectVersion(version: ConfigurationVersionView) {
    setVersionId(version.version_id);
    setDraft(JSON.parse(version.value_json) as SkillDocument);
  }

  return (
    <section className="workspace-page management-page connector-management-page">
      <div className="management-hero"><div className="management-hero-index">A</div><div className="management-hero-copy"><span className="eyebrow">CONTROLLED SKILL REGISTRY</span><h2>Skills 能力管理</h2><p>V1 只允许控制 Skill 启停和发布依赖；执行器、失败语义、输入输出契约及参数均由内置代码固定，不能伪装成可配置能力。</p></div><div className="management-signal"><small>已登记 Skill</small><strong>{items.length}</strong><button className="text-action" type="button" onClick={() => void load()}>刷新 ↗</button></div></div>
      {!items.length ? <div className="empty-state"><strong>尚未登记 Skill 配置</strong><p>初始化会登记主体识别、企业快照、公开检索、风险评分和报告生成五项能力。</p><button className="primary" type="button" disabled={saving} onClick={() => void initialize()}>登记内置 Skills</button></div> : <div className="connector-workbench">
        <aside className="connector-list"><div className="connector-list-head"><span className="eyebrow">SKILL CATALOG</span><button type="button" onClick={() => void initialize()}>补齐登记</button></div>{items.map((item) => { const active = item.binding?.active_version_id; const latest = item.versions[0]; return <button key={item.definition.config_key} type="button" className={item.definition.config_key === selectedConfig?.definition.config_key ? "active" : ""} onClick={() => selectConfiguration(item)}><span><strong>{item.definition.display_name}</strong><small>{item.definition.config_key}</small></span><span className="skill-state-stack"><em className="builtin-state">执行器已内置</em><RuntimeState state={active ? "PUBLISHED" : latest?.status || "DRAFT"} /></span></button>; })}</aside>
        <div className="connector-editor">
          <div className="connector-editor-head"><div><span className="eyebrow">CONFIGURABLE CONTRACT</span><h3>{selectedConfig?.definition.display_name}</h3><p>{selectedConfig?.definition.description}</p></div><div className="connector-head-actions">{selected?.status !== "DRAFT" && <button className="secondary" type="button" onClick={() => void createDraft()}>新建草稿</button>}<button className="primary" type="button" disabled={!editable || !dirty || saving} onClick={() => void saveDraft()}>保存草稿</button></div></div>
          <div className="version-strip">{selectedConfig?.versions.map((version) => <button key={version.version_id} type="button" className={version.version_id === selected?.version_id ? "active" : ""} onClick={() => selectVersion(version)}>v{version.version_no}<small>{version.status}</small></button>)}</div>
          {draft && <div className="connector-form-shell">
            <div className="rule-section-heading"><div><span className="eyebrow">01 / EXECUTION BOUNDARY</span><h3>执行边界</h3></div></div>
            <div className="connector-form-grid"><label><span>启用此 Skill</span><select disabled={!editable} value={String(draft.enabled)} onChange={(event) => setDraft({ ...draft, enabled: event.target.value === "true" })}><option value="true">启用</option><option value="false">停用（新任务不可执行）</option></select></label><label><span>失败策略（内置固定）</span><input disabled value="失败即停止" /></label><label className="wide"><span>内置执行器（不可编辑）</span><input disabled value={draft.executor_key} /></label></div>
            <div className="rule-section-heading"><div><span className="eyebrow">02 / DATA CONTRACT</span><h3>输入与输出</h3></div></div>
            <div className="connector-form-grid"><label className="wide"><span>输入字段（执行器声明，只读）</span><textarea disabled value={draft.input_contract.join("\n")} /></label><label className="wide"><span>输出字段（执行器声明，只读）</span><textarea disabled value={draft.output_contract.join("\n")} /></label></div>
            <div className="rule-section-heading"><div><span className="eyebrow">03 / PARAMETERS</span><h3>业务参数</h3></div></div>
            <div className="connector-form-grid">{Object.entries(draft.parameters).map(([key, value]) => <label key={key}><span>{key}（内置固定）</span><input disabled value={String(value)} /></label>)}</div>
            <div className="rule-section-heading"><div><span className="eyebrow">04 / DEPENDENCIES</span><h3>依赖能力</h3></div></div>
            <div className="dependency-list">{draft.dependencies.map((dependency, index) => <div className="dependency-row" key={`${dependency.config_key}-${index}`}><input disabled={!editable} value={dependency.config_key} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, config_key: event.target.value }; setDraft({ ...draft, dependencies: values }); }} /><select disabled={!editable} value={dependency.category} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, category: event.target.value }; setDraft({ ...draft, dependencies: values }); }}><option>DATA_SOURCE</option><option>SEARCH</option><option>MODEL</option><option>RULES</option><option>REPORT_TEMPLATE</option></select><label><input type="checkbox" disabled={!editable} checked={dependency.required} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, required: event.target.checked }; setDraft({ ...draft, dependencies: values }); }} />发布前必须可用</label></div>)}</div>
          </div>}
        </div>
        <aside className="connector-release"><span className="eyebrow">RELEASE GATE</span><h3>运行与发布</h3><div className="skill-runtime-summary"><div><span>内置执行器</span><strong>已装载</strong></div><div><span>DEV 运行绑定</span><strong>{activeVersion ? `v${activeVersion.version_no} · 已发布` : "未发布"}</strong></div></div><div className="release-status"><span className="release-status-label">当前查看版本</span><RuntimeState state={selected?.status || "DRAFT"} /><p>{selected?.validation_message || "保存后先校验输入输出、内置执行器和依赖。"}</p></div><div className="release-actions">{editable && <button className="secondary" type="button" disabled={dirty || saving} onClick={() => void validateDraft()}>校验契约</button>}{selected?.status === "VALIDATED" && <button className="primary" type="button" disabled={saving} onClick={() => void release("publish")}>发布版本</button>}{selected?.status === "INACTIVE" && <button className="secondary" type="button" disabled={saving} onClick={() => void release("rollback")}>回滚到此版本</button>}</div><p className="release-help">新任务必须冻结 5 个已发布且启用的 Skill；已有任务继续使用创建时的版本。</p></aside>
      </div>}
    </section>
  );
}

function ReportTemplateManagementView({
  api,
  notify,
  operatorId,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const [items, setItems] = useState<ConfigurationOverviewView[]>([]);
  const [versionId, setVersionId] = useState("");
  const [draft, setDraft] = useState<ReportTemplateDocument>();
  const [preview, setPreview] = useState<ReportTemplatePreview>();
  const [file, setFile] = useState<File>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async (preferredVersion?: string) => {
    try {
      const values = await api<ConfigurationOverviewView[]>("/api/platform/report-templates?environment=DEV");
      setItems(values);
      const preferred = values.flatMap((item) => item.versions)
        .find((version) => version.version_id === preferredVersion);
      const version = preferred || values[0]?.versions[0];
      setVersionId(version?.version_id || "");
      setDraft(version?.value_json ? JSON.parse(version.value_json) as ReportTemplateDocument : undefined);
    } catch (error) {
      notify((error as Error).message);
    }
  }, [api, notify]);

  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
  const selectedConfig = items.find((item) => item.versions.some((version) => version.version_id === versionId)) || items[0];
  const selected = selectedConfig?.versions.find((version) => version.version_id === versionId) || selectedConfig?.versions[0];
  const editable = selected?.status === "DRAFT";
  const dirty = Boolean(draft && selected && JSON.stringify(draft) !== JSON.stringify(JSON.parse(selected.value_json)));

  useEffect(() => {
    if (!selected?.value_json) return;
    api<ReportTemplatePreview>(`/api/platform/report-templates/versions/${selected.version_id}/preview`)
      .then(setPreview).catch((error) => notify((error as Error).message));
  }, [api, notify, selected?.version_id, selected?.value_json]);

  function selectVersion(version: ConfigurationVersionView) {
    setVersionId(version.version_id);
    setDraft(JSON.parse(version.value_json) as ReportTemplateDocument);
  }

  async function initialize() {
    setSaving(true);
    try {
      await api("/api/platform/report-templates/initialize", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ environment: "DEV", operator_id: operatorId }) });
      await load();
      notify("现有正式 DOCX 已登记为 V1 模板草稿");
    } catch (error) { notify((error as Error).message); } finally { setSaving(false); }
  }

  async function upload() {
    if (!file) return;
    setSaving(true);
    try {
      const body = new FormData();
      body.append("file", file);
      body.append("operatorId", operatorId);
      body.append("environment", "DEV");
      if (draft) body.append("fieldMappingJson", JSON.stringify(draft.field_mapping));
      const result = await api<{ configuration: ConfigurationOverviewView }>("/api/platform/report-templates/uploads", { method: "POST", body });
      const uploaded = result.configuration.versions[0];
      await load(uploaded.version_id);
      setFile(undefined);
      notify("DOCX 已上传并通过包结构检查，已形成新草稿");
    } catch (error) { notify((error as Error).message); } finally { setSaving(false); }
  }

  async function saveDraft() {
    if (!selected || !draft || !editable) return;
    setSaving(true);
    try {
      const value = await api<ConfigurationVersionView>(`/api/platform/configurations/versions/${selected.version_id}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expected_row_version: selected.row_version, value_json: JSON.stringify(draft), operator_id: operatorId }) });
      await load(value.version_id);
      notify("模板字段映射已保存");
    } catch (error) { notify((error as Error).message); } finally { setSaving(false); }
  }

  async function validateDraft() {
    if (!selected || dirty) return;
    setSaving(true);
    try {
      const value = await api<ConfigurationVersionView>(`/api/platform/configurations/versions/${selected.version_id}/validate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expected_row_version: selected.row_version, operator_id: operatorId }) });
      await load(value.version_id);
      notify("DOCX 格式、字段映射和依赖校验通过");
    } catch (error) { notify((error as Error).message); } finally { setSaving(false); }
  }

  async function release(action: "publish" | "rollback") {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/platform/configurations/versions/${selected.version_id}/${action}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ environment: "DEV", idempotency_key: `${action}-template-${selected.version_id}-${Date.now()}`, operator_id: operatorId }) });
      await load(selected.version_id);
      notify(action === "publish" ? "模板版本已发布，新任务将冻结使用" : "模板已回滚");
    } catch (error) { notify((error as Error).message); } finally { setSaving(false); }
  }

  async function download() {
    if (!selected) return;
    try {
      const blob = await api<Blob>(`/api/platform/report-templates/versions/${selected.version_id}/download`);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url; anchor.download = `${draft?.template_version || "atlas-template"}.docx`; anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) { notify((error as Error).message); }
  }

  return (
    <section className="workspace-page management-page connector-management-page">
      <div className="management-hero"><div className="management-hero-index">E</div><div className="management-hero-copy"><span className="eyebrow">VERSIONED DOCX PRODUCTION</span><h2>报告模板管理</h2><p>上传 DOCX、逐项确认运行定位词真实存在，再按版本发布；新任务冻结模板，生成与回放都使用同一份内容和映射。</p></div><div className="management-signal"><small>模板版本</small><strong>{selected ? `v${selected.version_no}` : "—"}</strong><button className="text-action" type="button" onClick={() => void load()}>刷新 ↗</button></div></div>
      {!items.length ? <div className="empty-state"><strong>尚未登记正式模板</strong><p>可直接登记当前 V1 正式 DOCX，随后检查、校验并发布。</p><button className="primary" type="button" disabled={saving} onClick={() => void initialize()}>登记现有 V1 模板</button></div> : <div className="connector-workbench">
        <aside className="connector-list"><div className="connector-list-head"><span className="eyebrow">TEMPLATE VERSIONS</span><button type="button" onClick={() => void initialize()}>检查登记</button></div>{selectedConfig?.versions.map((version) => <button key={version.version_id} type="button" className={version.version_id === selected?.version_id ? "active" : ""} onClick={() => selectVersion(version)}><span><strong>模板 v{version.version_no}</strong><small>{formatTime(version.created_at)} · {version.created_by}</small></span><RuntimeState state={version.status} /></button>)}</aside>
        <div className="connector-editor">
          <div className="connector-editor-head"><div><span className="eyebrow">DOCX ARTIFACT</span><h3>{draft?.original_filename || "正式模板"}</h3><p>{draft?.template_version} · {draft?.content_hash.slice(0, 12)}</p></div><div className="connector-head-actions"><button className="secondary" type="button" onClick={() => void download()}>下载模板</button><button className="primary" type="button" disabled={!editable || !dirty || saving} onClick={() => void saveDraft()}>保存映射</button></div></div>
          <div className="template-upload-panel"><label><span>上传新 DOCX 形成草稿</span><input type="file" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" onChange={(event) => setFile(event.target.files?.[0])} /></label><button className="secondary" type="button" disabled={!file || saving} onClick={() => void upload()}>上传并检查</button></div>
          {preview && <div className="template-inspection"><div><RuntimeState state={preview.inspection.valid ? "READY" : "ACTION_REQUIRED"} /><strong>{preview.inspection.message}</strong></div><span>{preview.inspection.paragraph_count} 个段落</span><span>{preview.inspection.table_count} 个表格</span><span>{preview.inspection.detected_markers.length} 个字段标记</span></div>}
          {draft && <div className="connector-form-shell">
            <div className="rule-section-heading"><div><span className="eyebrow">01 / TEMPLATE IDENTITY</span><h3>模板标识</h3></div></div>
            <div className="connector-form-grid"><label><span>模板版本</span><input disabled={!editable} value={draft.template_version} onChange={(event) => setDraft({ ...draft, template_version: event.target.value })} /></label><label><span>启用此模板</span><select disabled={!editable} value={String(draft.enabled)} onChange={(event) => setDraft({ ...draft, enabled: event.target.value === "true" })}><option value="true">启用（新任务使用）</option><option value="false">停用（新任务回退内置模板）</option></select></label><label><span>输出格式</span><input disabled value={draft.format} /></label><label className="wide"><span>内容校验和（不可编辑）</span><input disabled value={draft.content_hash} /></label></div>
            <div className="rule-section-heading"><div><span className="eyebrow">02 / FIELD MAPPING</span><h3>字段定位</h3></div></div>
            <div className="connector-form-grid">{Object.entries(draft.field_mapping).map(([key, value]) => <label key={key}><span>{key}{key === "company_name" ? "（渲染器固定）" : "（发布前检查）"}</span><input disabled={!editable || key === "company_name"} value={value} onChange={(event) => setDraft({ ...draft, field_mapping: { ...draft.field_mapping, [key]: event.target.value } })} /></label>)}</div>
            <div className="rule-section-heading"><div><span className="eyebrow">03 / DEPENDENCIES</span><h3>模板依赖</h3></div></div>
            <div className="dependency-list">{draft.dependencies.map((dependency, index) => <div className="dependency-row" key={`${dependency.config_key}-${index}`}><input disabled={!editable} value={dependency.config_key} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, config_key: event.target.value }; setDraft({ ...draft, dependencies: values }); }} /><select disabled={!editable} value={dependency.category} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, category: event.target.value }; setDraft({ ...draft, dependencies: values }); }}><option>SKILL</option><option>RULES</option><option>DATA_SOURCE</option><option>SEARCH</option><option>MODEL</option></select><label><input type="checkbox" disabled={!editable} checked={dependency.required} onChange={(event) => { const values = [...draft.dependencies]; values[index] = { ...dependency, required: event.target.checked }; setDraft({ ...draft, dependencies: values }); }} />发布前必须可用</label></div>)}</div>
          </div>}
        </div>
        <aside className="connector-release"><span className="eyebrow">RELEASE GATE</span><h3>发布门禁</h3><div className="release-status"><RuntimeState state={selected?.status || "DRAFT"} /><p>{selected?.validation_message || "必须通过 DOCX 结构、每个运行定位词和依赖检查。"}</p></div><div className="release-actions">{editable && <button className="secondary" type="button" disabled={dirty || saving} onClick={() => void validateDraft()}>校验模板</button>}{selected?.status === "VALIDATED" && <button className="primary" type="button" disabled={saving} onClick={() => void release("publish")}>发布模板</button>}{selected?.status === "INACTIVE" && <button className="secondary" type="button" disabled={saving} onClick={() => void release("rollback")}>回滚此版本</button>}</div><p className="release-help">无效 DOCX、运行定位词不存在或必需依赖未发布时不能发布。</p></aside>
      </div>}
    </section>
  );
}

function OperationsManagementView({
  api,
  notify,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
}) {
  const [days, setDays] = useState(7);
  const [snapshot, setSnapshot] = useState<OperationsSnapshot>();
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const to = new Date();
      const from = new Date(to.getTime() - days * 86400000);
      setSnapshot(await api<OperationsSnapshot>(
        `/api/platform/operations?from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}&failure_limit=30`,
      ));
    } catch (error) { notify((error as Error).message); } finally { setLoading(false); }
  }, [api, days, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function retry(taskId: string) {
    setRetrying(taskId);
    try {
      await api(`/api/tasks/${taskId}/retry`, { method: "POST" });
      notify("失败任务已按原任务重试，证据、评分和报告不会重复创建");
      await load();
    } catch (error) { notify((error as Error).message); } finally { setRetrying(undefined); }
  }

  const metrics = [
    ["任务总量", snapshot?.total_tasks, `统计窗口内创建`],
    ["活跃执行", snapshot?.active_tasks, `${snapshot?.activity_threshold_minutes || 15} 分钟内有进展`],
    ["待人工处理", snapshot?.waiting_tasks, "等待主体确认或异常人工研判"],
    ["已停滞", snapshot?.stalled_tasks, `超过 ${snapshot?.activity_threshold_minutes || 15} 分钟无进展`],
    ["已完成", snapshot?.completed_tasks, "完成业务闭环"],
    ["失败任务", snapshot?.failed_tasks, "可下钻查看失败步骤"],
    ["平均耗时", formatDuration(snapshot?.average_duration_millis), "仅统计已完成任务"],
    ["搜索调用", snapshot?.search_calls, "公开信息搜索批次"],
    ["模型调用", snapshot?.model_calls, `${snapshot?.model_total_tokens || 0} Token`],
    ["报告生成", snapshot?.generated_reports, `${snapshot?.failed_reports || 0} 个失败`],
  ];
  const maxCreated = Math.max(1, ...(snapshot?.throughput.map((item) => item.created) || [1]));

  return <section className="workspace-page observability-page">
    <div className="management-hero compact-observability-hero"><div className="management-hero-index">F</div><div className="management-hero-copy"><span className="eyebrow">REAL RUNTIME OBSERVATORY</span><h2>运行监控</h2><p>直接读取真实任务、搜索批次、模型调用与报告版本；调用次数和 Token 来自任务实际执行记录。</p></div><div className="management-signal"><small>统计窗口</small><strong>最近 {days} 天</strong><button className="text-action" type="button" onClick={() => void load()}>{loading ? "刷新中" : "刷新数据 ↗"}</button></div></div>
    <div className="range-switch" aria-label="统计时间范围">{[1, 7, 30].map((value) => <button key={value} className={days === value ? "active" : ""} type="button" onClick={() => setDays(value)}>{value === 1 ? "24 小时" : `${value} 天`}</button>)}</div>
    <div className="operations-metrics">{metrics.map(([label, value, hint]) => <article key={String(label)}><small>{label}</small><strong>{loading ? "—" : value ?? 0}</strong><span>{hint}</span></article>)}</div>
    <div className="observability-grid">
      <article className="throughput-panel"><div className="panel-heading"><div><span className="eyebrow">THROUGHPUT</span><h3>每日任务流量</h3></div><small>创建 / 完成 / 失败</small></div>{snapshot?.throughput.length ? <div className="throughput-bars">{snapshot.throughput.map((point) => <div key={point.date}><span>{point.date.slice(5)}</span><div><i style={{ width: `${Math.max(4, point.created / maxCreated * 100)}%` }} /><b style={{ width: `${point.created ? point.completed / point.created * 100 : 0}%` }} /></div><strong>{point.created} / {point.completed} / {point.failed}</strong></div>)}</div> : <p className="inline-empty">当前时间范围内没有任务数据。</p>}</article>
      <article className="report-health-panel"><div className="panel-heading"><div><span className="eyebrow">REPORT PIPELINE</span><h3>报告与模型状态</h3></div></div><div className="report-health-ring"><strong>{snapshot?.generated_reports || 0}</strong><span>成功生成</span></div><dl><div><dt>生成失败</dt><dd>{snapshot?.failed_reports || 0}</dd></div><div><dt>模型调用</dt><dd>{snapshot?.model_calls || 0} 次</dd></div><div><dt>输入 Token</dt><dd>{snapshot?.model_prompt_tokens || 0}</dd></div><div><dt>输出 Token</dt><dd>{snapshot?.model_completion_tokens || 0}</dd></div></dl></article>
    </div>
    <div className="failure-table stalled-table"><div className="panel-heading"><div><span className="eyebrow">STALLED TASKS</span><h3>停滞任务下钻</h3></div><small>只有最近 {snapshot?.activity_threshold_minutes || 15} 分钟内有状态进展的系统任务才计为活跃</small></div><div className="stalled-row stalled-head"><span>企业 / 任务</span><span>当前状态</span><span>当前步骤</span><span>最后进展</span><span>停滞时长</span><span>操作</span></div>{snapshot?.stalled.length ? snapshot.stalled.map((item) => <div className="stalled-row" key={item.task_id}><span><strong>{item.enterprise_name}</strong><small>{item.task_no}</small></span><span>{STATUS_LABELS[item.status] || item.status}</span><span>{STEP_LABELS[item.current_step || ""] || item.current_step || "未知"}</span><span>{formatTime(item.updated_at)}</span><strong>{formatDuration(item.stalled_minutes * 60000)}</strong><span><Link href={`/tasks?id=${item.task_id}`} prefetch={false}>查看任务</Link></span></div>) : <p className="inline-empty">当前时间范围内没有停滞任务。</p>}</div>
    <div className="failure-table"><div className="panel-heading"><div><span className="eyebrow">FAILED TASKS</span><h3>失败任务下钻</h3></div><small>只允许重试后端判定为可重试的失败</small></div><div className="failure-row failure-head"><span>企业 / 任务</span><span>失败步骤</span><span>错误</span><span>调用</span><span>更新时间</span><span>操作</span></div>{snapshot?.failures.length ? snapshot.failures.map((item) => <div className="failure-row" key={item.task_id}><span><strong>{item.enterprise_name}</strong><small>{item.task_no}</small></span><span>{STEP_LABELS[item.failed_step || ""] || item.failed_step || "未知"}</span><code>{item.error_code || "UNKNOWN"}</code><span>搜索 {item.search_calls} · 模型 {item.model_calls ?? "未知"}</span><span>{formatTime(item.updated_at)}</span><span><Link href={`/tasks?id=${item.task_id}`} prefetch={false}>查看</Link><button type="button" disabled={!item.retryable || retrying === item.task_id} onClick={() => void retry(item.task_id)}>{retrying === item.task_id ? "重试中" : "重试"}</button></span></div>) : <p className="inline-empty">当前时间范围内没有失败任务。</p>}</div>
  </section>;
}

function AuditManagementView({
  api,
  notify,
  operatorId,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const now = new Date();
  const initialFrom = new Date(now.getTime() - 7 * 86400000).toISOString().slice(0, 10);
  const [filters, setFilters] = useState({ task: "", enterprise: "", operator: "", type: "", from: initialFrom, to: now.toISOString().slice(0, 10) });
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [selected, setSelected] = useState<AuditEntry>();
  const [change, setChange] = useState<ConfigurationChange>();
  const [loading, setLoading] = useState(true);

  const query = useCallback((exporting = false) => {
    const params = new URLSearchParams();
    if (filters.task) params.set("task_id", filters.task);
    if (filters.enterprise) params.set("enterprise", filters.enterprise);
    if (filters.operator) params.set("operator_id", filters.operator);
    if (filters.type) params.set("event_type", filters.type);
    if (filters.from) params.set("from", new Date(`${filters.from}T00:00:00`).toISOString());
    if (filters.to) params.set("to", new Date(`${filters.to}T23:59:59`).toISOString());
    params.set("limit", exporting ? "500" : "150");
    return params.toString();
  }, [filters]);

  const load = useCallback(async () => {
    setLoading(true);
    try { setEntries(await api<AuditEntry[]>(`/api/platform/audit?${query()}`)); }
    catch (error) { notify((error as Error).message); } finally { setLoading(false); }
  }, [api, notify, query]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function inspect(entry: AuditEntry) {
    setSelected(entry); setChange(undefined);
    if (entry.event_type === "CONFIGURATION_RELEASE") {
      try { setChange(await api<ConfigurationChange>(`/api/platform/configuration-changes?release_id=${entry.event_id}`)); }
      catch (error) { notify((error as Error).message); }
    }
  }

  async function exportAudit() {
    try {
      const blob = await api<Blob>(`/api/platform/audit/export?${query(true)}`);
      const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
      anchor.href = url; anchor.download = `atlas-audit-${filters.from}-${filters.to}.csv`; anchor.click();
      URL.revokeObjectURL(url); notify("审计日志已按当前筛选条件导出");
    } catch (error) { notify((error as Error).message); }
  }

  return <section className="workspace-page audit-page">
    <div className="management-hero compact-observability-hero"><div className="management-hero-index">G</div><div className="management-hero-copy"><span className="eyebrow">TRACE & ACCOUNTABILITY</span><h2>审计日志</h2><p>把任务步骤、人工决定、评分调整、报告版本和配置发布放在同一条时间线上，可按任务、企业、操作人、事件和时间追溯。</p></div><div className="management-signal"><small>当前记录</small><strong>{loading ? "—" : entries.length}</strong><button className="text-action" type="button" onClick={() => void exportAudit()}>导出 CSV ↗</button></div></div>
    <div className="audit-filter-bar"><label><span>任务 ID</span><input value={filters.task} onChange={(event) => setFilters({ ...filters, task: event.target.value })} placeholder="UUID" /></label><label><span>企业</span><input value={filters.enterprise} onChange={(event) => setFilters({ ...filters, enterprise: event.target.value })} placeholder="名称关键词" /></label><label><span>操作人</span><input value={filters.operator} onChange={(event) => setFilters({ ...filters, operator: event.target.value })} placeholder={operatorId} /></label><label><span>事件类型</span><select value={filters.type} onChange={(event) => setFilters({ ...filters, type: event.target.value })}><option value="">全部事件</option><option value="TASK_STEP">任务步骤</option><option value="OPERATOR_DECISION">人工评分</option><option value="EVIDENCE_DECISION">证据决定</option><option value="OPERATOR_CONFIRMATION">运营确认</option><option value="REPORT_VERSION">报告版本</option><option value="CONFIGURATION_RELEASE">配置发布</option><option value="AUDIT_EVENT">通用审计</option></select></label><label><span>开始日期</span><input type="date" value={filters.from} onChange={(event) => setFilters({ ...filters, from: event.target.value })} /></label><label><span>结束日期</span><input type="date" value={filters.to} onChange={(event) => setFilters({ ...filters, to: event.target.value })} /></label><button className="primary" type="button" onClick={() => void load()}>查询</button></div>
    <div className="audit-workbench"><div className="audit-timeline">{entries.length ? entries.map((entry) => <button type="button" key={`${entry.event_type}-${entry.event_id}`} className={selected?.event_id === entry.event_id ? "selected" : ""} onClick={() => void inspect(entry)}><i /><span><small>{formatTime(entry.occurred_at)} · {entry.event_type}</small><strong>{entry.action}</strong><em>{entry.enterprise_name || entry.detail || entry.target_type}</em></span><span><small>{entry.operator_id || entry.actor_type || "系统"}</small><code>{entry.task_no || entry.target_id}</code></span></button>) : <p className="inline-empty">当前筛选条件下没有审计记录。</p>}</div><aside className="audit-detail">{selected ? <><span className="eyebrow">EVENT DETAIL</span><h3>{selected.action}</h3><dl><div><dt>发生时间</dt><dd>{new Date(selected.occurred_at).toLocaleString("zh-CN")}</dd></div><div><dt>操作人</dt><dd>{selected.operator_id || selected.actor_type || "未知"}</dd></div><div><dt>关联任务</dt><dd>{selected.task_no || selected.task_id || "无"}</dd></div><div><dt>目标</dt><dd>{selected.target_type} / {selected.target_id}</dd></div><div><dt>说明</dt><dd>{selected.detail || "无"}</dd></div></dl>{change && <div className="config-diff"><div><small>变更前 · v{change.from_version_no || "无"}</small><pre>{change.before_json ? JSON.stringify(JSON.parse(change.before_json), null, 2) : "首次发布，无前序版本"}</pre></div><div><small>变更后 · v{change.to_version_no}</small><pre>{JSON.stringify(JSON.parse(change.after_json), null, 2)}</pre></div></div>}{!change && (selected.before_json || selected.after_json) && <div className="config-diff"><div><small>变更前</small><pre>{selected.before_json || "无"}</pre></div><div><small>变更后</small><pre>{selected.after_json || "无"}</pre></div></div>}</> : <div className="audit-placeholder"><strong>选择一条记录</strong><p>查看操作人、关联任务、前后值和追踪信息；配置发布会自动展示版本差异。</p></div>}</aside></div>
  </section>;
}

function GoldenAcceptanceView({
  api,
  notify,
  operatorId,
}: {
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  type EvaluationDraft = {
    case_id: string;
    subject_matched: boolean;
    major_risk_count: number;
    supported_major_risk_count: number;
    score_explainable: boolean;
    docx_core_fields_ok: boolean;
    critical_defect_count: number;
    high_defect_count: number;
    manual_minutes: string;
    notes: string;
  };
  const [suites, setSuites] = useState<GoldenSuiteSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [detail, setDetail] = useState<GoldenSuiteDetail>();
  const [evaluations, setEvaluations] = useState<EvaluationDraft[]>([]);
  const [file, setFile] = useState<File>();
  const [name, setName] = useState("业务黄金样本集");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (preferredId?: string) => {
    try {
      const items = await api<GoldenSuiteSummary[]>("/api/platform/golden-acceptance/suites");
      setSuites(items);
      const id = preferredId || selectedId || items[0]?.suite_id;
      if (!id) { setDetail(undefined); return; }
      setSelectedId(id);
      const next = await api<GoldenSuiteDetail>(`/api/platform/golden-acceptance/suites/${id}`);
      setDetail(next);
      setEvaluations(next.manifest.cases.map((sample) => ({
        case_id: sample.id,
        subject_matched: false,
        major_risk_count: sample.evidence_labels.filter((item) => item.major_risk && item.include_in_report).length,
        supported_major_risk_count: 0,
        score_explainable: false,
        docx_core_fields_ok: false,
        critical_defect_count: 0,
        high_defect_count: 0,
        manual_minutes: "",
        notes: "",
      })));
    } catch (error) { notify((error as Error).message); }
  }, [api, notify, selectedId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function importManifest() {
    if (!file) return;
    setBusy(true);
    try {
      const manifest = JSON.parse(await file.text());
      const created = await api<GoldenSuiteSummary>("/api/platform/golden-acceptance/suites", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, manifest, operator_id: operatorId }),
      });
      notify(created.status === "READY" ? "正式黄金样本集已登记，可开始盲测" : "样本集已登记为草稿；需 20～50 份且全部业务确认");
      await load(created.suite_id);
    } catch (error) { notify((error as Error).message); } finally { setBusy(false); }
  }

  function updateEvaluation(index: number, patch: Partial<EvaluationDraft>) {
    const next = [...evaluations]; next[index] = { ...next[index], ...patch }; setEvaluations(next);
  }

  async function submitEvaluation() {
    if (!detail) return;
    setBusy(true);
    try {
      const result = await api<GoldenAcceptanceRun>(`/api/platform/golden-acceptance/suites/${detail.suite.suite_id}/evaluations`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ operator_id: operatorId, cases: evaluations.map((item) => ({
          ...item,
          manual_minutes: item.manual_minutes === "" ? null : Number(item.manual_minutes),
        })) }),
      });
      notify(result.status === "PASSED" ? "业务黄金样本验收通过" : `验收结果：${result.status}，请查看未通过门禁`);
      await load(detail.suite.suite_id);
    } catch (error) { notify((error as Error).message); } finally { setBusy(false); }
  }

  const latest = detail?.runs[0];
  const maturityLevels = [
    ["E0", "未开始", "没有可运行实现"],
    ["E1", "已实现", "代码或页面已存在"],
    ["E2", "技术验证", "业务语义测试通过"],
    ["E3", "集成验证", "部署环境真实运行"],
    ["E4", "业务验收", "真实资料由运营验收"],
    ["E5", "生产就绪", "安全、恢复和演练通过"],
  ];
  const gates = [
    ["正式样本数量", detail ? `${detail.suite.case_count}/20` : "—", Boolean(detail && detail.suite.case_count >= 20)],
    ["业务确认", detail ? `${detail.suite.confirmed_case_count}/${detail.suite.case_count}` : "—", Boolean(detail && detail.suite.confirmed_case_count === detail.suite.case_count)],
    ["真实资料验证", detail ? `${detail.suite.verified_artifact_case_count}/${detail.suite.case_count}` : "—", Boolean(detail && detail.suite.verified_artifact_case_count === detail.suite.case_count)],
    ["严重主体错配", latest ? latest.severe_subject_mismatch_count : "未验收", latest?.severe_subject_mismatch_count === 0],
    ["重大风险有证据", latest ? `${latest.supported_major_risk_count}/${latest.major_risk_count}` : "未验收", Boolean(latest && latest.supported_major_risk_count === latest.major_risk_count)],
    ["评分可解释", latest ? `${latest.explainable_score_count}/${latest.case_count}` : "未验收", Boolean(latest && latest.explainable_score_count === latest.case_count)],
    ["DOCX 核心字段", latest ? `${latest.docx_pass_count}/${latest.case_count}` : "未验收", Boolean(latest && latest.docx_pass_count === latest.case_count)],
    ["Critical / High", latest ? `${latest.critical_defect_count} / ${latest.high_defect_count}` : "未验收", Boolean(latest && latest.critical_defect_count === 0 && latest.high_defect_count === 0)],
  ];

  return <section className="workspace-page acceptance-page">
    <div className="management-hero compact-observability-hero"><div className="management-hero-index">H</div><div className="management-hero-copy"><span className="eyebrow">BUSINESS GOLDEN ACCEPTANCE</span><h2>验收评估</h2><p>登记历史业务报告与人工终稿作为质量基线，执行主体、舆情证据、评分、DOCX 和运营耗时盲测；这些样本只用于验收，不是新任务输入。</p></div><div className="management-signal"><small>最近结果</small><strong>{latest?.status || detail?.suite.status || "未登记"}</strong><button className="text-action" type="button" onClick={() => void load()}>刷新数据 ↗</button></div></div>
    <div className="maturity-scale" aria-label="E0 到 E5 验收等级"><div className="maturity-scale-intro"><span className="eyebrow">DEFINITION OF DONE</span><strong>完成不再只有一个含义</strong><p>任务必须逐级取得证据；页面存在只到 E1，自动化通过只到 E2，真实业务闭环达到 E4 后才能称为 V1 完成。</p></div>{maturityLevels.map(([level, label, description]) => <article key={level}><b>{level}</b><strong>{label}</strong><small>{description}</small></article>)}</div>
    <div className="acceptance-import"><label><span>样本集名称</span><input value={name} onChange={(event) => setName(event.target.value)} /></label><label><span>选择 acceptance manifest JSON</span><input type="file" accept="application/json,.json" onChange={(event) => setFile(event.target.files?.[0])} /></label><button className="primary" type="button" disabled={!file || busy} onClick={() => void importManifest()}>导入并校验</button><p>模板位于 <code>data/golden/acceptance-manifest.template.json</code>。少于 20 份或存在未确认样本时自动标为草稿。</p></div>
    <div className="acceptance-gates">{gates.map(([label, value, passed]) => <article key={String(label)}><small>{label}</small><strong>{value}</strong><RuntimeState state={passed ? "READY" : "ACTION_REQUIRED"} /></article>)}</div>
    <div className="acceptance-workbench"><aside className="suite-list"><span className="eyebrow">SAMPLE SUITES</span>{suites.length ? suites.map((suite) => <button type="button" key={suite.suite_id} className={suite.suite_id === selectedId ? "selected" : ""} onClick={() => void load(suite.suite_id)}><span><strong>{suite.name}</strong><small>{suite.case_count} 份 · {suite.confirmed_case_count} 份确认</small></span><RuntimeState state={suite.status === "READY" ? "READY" : "ACTION_REQUIRED"} /></button>) : <p className="inline-empty">尚未登记业务黄金样本集。</p>}</aside><div className="case-evaluation"><div className="panel-heading"><div><span className="eyebrow">BLIND TEST WORKSHEET</span><h3>逐样本验收记录</h3></div><small>默认不勾选通过，必须由运营人员逐项确认</small></div>{detail ? <><div className="case-evaluation-head"><span>企业 / 样本</span><span>主体正确</span><span>重大风险证据</span><span>评分可解释</span><span>DOCX</span><span>缺陷 C/H</span><span>人工分钟</span></div>{detail.manifest.cases.map((sample, index) => { const row = evaluations[index]; return row ? <div className="case-evaluation-row" key={sample.id}><span><strong>{sample.company.canonical_name}</strong><small>{sample.id} · {sample.expected.manual_score} / {sample.expected.risk_level}</small></span><label><input type="checkbox" checked={row.subject_matched} onChange={(event) => updateEvaluation(index, { subject_matched: event.target.checked })} />正确</label><span><input type="number" min="0" value={row.supported_major_risk_count} onChange={(event) => updateEvaluation(index, { supported_major_risk_count: Number(event.target.value) })} /><b>/ {row.major_risk_count}</b></span><label><input type="checkbox" checked={row.score_explainable} onChange={(event) => updateEvaluation(index, { score_explainable: event.target.checked })} />可解释</label><label><input type="checkbox" checked={row.docx_core_fields_ok} onChange={(event) => updateEvaluation(index, { docx_core_fields_ok: event.target.checked })} />通过</label><span><input type="number" min="0" value={row.critical_defect_count} onChange={(event) => updateEvaluation(index, { critical_defect_count: Number(event.target.value) })} /><input type="number" min="0" value={row.high_defect_count} onChange={(event) => updateEvaluation(index, { high_defect_count: Number(event.target.value) })} /></span><input type="number" min="0" step="0.1" value={row.manual_minutes} onChange={(event) => updateEvaluation(index, { manual_minutes: event.target.value })} /></div> : null; })}<div className="evaluation-submit"><span>本轮将保存为独立验收记录，不覆盖历史结果。</span><button className="primary" type="button" disabled={busy || !evaluations.length} onClick={() => void submitEvaluation()}>提交本轮验收</button></div></> : <p className="inline-empty">导入或选择一个样本集后开始验收。</p>}</div></div>
  </section>;
}

function ManagementView({
  section,
  api,
  notify,
  operatorId,
}: {
  section: View;
  api: <T>(path: string, options?: RequestInit) => Promise<T>;
  notify: (message: string) => void;
  operatorId: string;
}) {
  const [runtime, setRuntime] = useState<RuntimeCapabilities>();
  const [loading, setLoading] = useState(true);

  const loadRuntime = useCallback(async () => {
    setLoading(true);
    try {
      setRuntime(await api<RuntimeCapabilities>("/api/runtime/capabilities"));
    } catch (error) {
      notify((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [api, notify]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadRuntime(), 0);
    return () => window.clearTimeout(timer);
  }, [loadRuntime]);

  if (section === "riskRules") {
    return <RiskRulesManagementView api={api} notify={notify} operatorId={operatorId} />;
  }
  if (section === "skills") {
    return <SkillManagementView api={api} notify={notify} operatorId={operatorId} />;
  }
  if (section === "reportTemplates") {
    return <ReportTemplateManagementView api={api} notify={notify} operatorId={operatorId} />;
  }
  if (section === "operations") {
    return <OperationsManagementView api={api} notify={notify} />;
  }
  if (section === "audit") {
    return <AuditManagementView api={api} notify={notify} operatorId={operatorId} />;
  }
  if (section === "acceptance") {
    return <GoldenAcceptanceView api={api} notify={notify} operatorId={operatorId} />;
  }
  if (section === "dataSources" || section === "searchModels") {
    return <ConnectorManagementView
      mode={section === "dataSources" ? "DATA_SOURCE" : "SEARCH_MODEL"}
      api={api}
      notify={notify}
      operatorId={operatorId}
    />;
  }

  const searchEngine = runtime?.search_providers.find(
    (provider) => provider.mode === "SEARCH_ENGINE",
  );
  const llmSearch = runtime?.search_providers.find(
    (provider) => provider.mode === "LLM_SEARCH",
  );
  const searchConfigured = Boolean(searchEngine || llmSearch);
  const esEnabled = runtime?.data_provider.name === "ELASTICSEARCH";
  const stateLabel = (configured: boolean, ready = "已启用") =>
    loading ? "检测中" : configured ? ready : "待配置";
  const capabilities = [
    {
      code: "01",
      title: "企业主体识别",
      text: `完整企业名称、统一社会信用代码，由${
        runtime?.data_provider.name || "当前数据 Provider"
      }完成多源身份绑定。`,
      state: stateLabel(Boolean(runtime?.data_provider.configured)),
      tone: runtime?.data_provider.configured ? "ready" : "waiting",
    },
    {
      code: "02",
      title: "工商与经营快照",
      text: "主档、工商变更、风险事件、联系方式和已入库公开信息统一冻结，保证报告可复现。",
      state: stateLabel(Boolean(runtime?.data_provider.configured)),
      tone: runtime?.data_provider.configured ? "ready" : "waiting",
    },
    {
      code: "03",
      title: "公开负面舆情",
      text: "搜索引擎与带引用联网模型统一适配，执行证据去重、内容留存和人工核验。",
      state: stateLabel(searchConfigured, "已接入"),
      tone: searchConfigured ? "ready" : "waiting",
    },
    {
      code: "04",
      title: "风险评分",
      text: `保留旧分、规则原始分和人工分；当前规则版本 ${
        runtime?.risk_scoring.details.rule_version || "检测中"
      }。`,
      state: stateLabel(Boolean(runtime?.risk_scoring.configured)),
      tone: runtime?.risk_scoring.configured ? "ready" : "waiting",
    },
    {
      code: "05",
      title: "正式 DOCX 报告",
      text: "沿用 V1 模板，输出版本化文档、差异、输入哈希和运营确认审计链。",
      state: stateLabel(Boolean(runtime?.report_generation.configured)),
      tone: runtime?.report_generation.configured ? "ready" : "waiting",
    },
    {
      code: "06",
      title: "Elasticsearch 查询",
      text: esEnabled
        ? "当前实例已使用 ES 只读 Provider，主体与子数据通过别名和 routing 查询。"
        : "当前实例使用离线数据 Provider，ES Adapter 已保留，可通过部署配置切换。",
      state: stateLabel(esEnabled, "当前启用"),
      tone: esEnabled ? "ready" : "waiting",
    },
  ];

  const sectionData: Record<string, {
    index: string;
    eyebrow: string;
    title: string;
    description: string;
    signal: string;
    signalLabel: string;
    cards: Array<{ title: string; state: string; summary: string; meta: string }>;
    rows: Array<{ item: string; configuration: string; state: string; policy: string }>;
  }> = {
    skills: {
      index: "A",
      eyebrow: "CONTROLLED BUSINESS SKILLS",
      title: "Skills 能力登记",
      description: "Agent 只编排经过登记的业务能力。每项能力都有明确输入、输出、失败边界和版本，不允许模型临时创造业务动作。",
      signal: `${capabilities.filter((item) => item.tone === "ready").length} / ${capabilities.length}`,
      signalLabel: "当前可用能力",
      cards: capabilities.map((item) => ({ title: item.title, state: item.tone === "ready" ? "READY" : "ACTION_REQUIRED", summary: item.text, meta: `SKILL ${item.code} · 受控执行` })),
      rows: [
        { item: "主体查询", configuration: "company.resolve / v1", state: runtime?.data_provider.state || "LOADING", policy: "候选不唯一时必须人工确认" },
        { item: "企业快照", configuration: "company.snapshot / v1", state: runtime?.data_provider.state || "LOADING", policy: "生成报告前冻结输入" },
        { item: "风险评分", configuration: runtime?.risk_scoring.details.rule_version || "RISK_RULES_V1", state: runtime?.risk_scoring.state || "LOADING", policy: "保留原始分和人工分" },
        { item: "报告生成", configuration: runtime?.report_generation.name || "DOCX_V1", state: runtime?.report_generation.state || "LOADING", policy: "运营确认后才能生成" },
      ],
    },
    dataSources: {
      index: "B",
      eyebrow: "SOURCE REGISTRY",
      title: "数据源管理",
      description: "管理企业结构化数据、公开信息与任务数据快照。页面展示真实运行状态，但认证信息只保留在服务器。",
      signal: runtime?.data_provider.name || "—",
      signalLabel: "当前主数据提供方",
      cards: [
        { title: "企业主数据", state: runtime?.data_provider.state || "LOADING", summary: "企业基本信息、工商变更、经营与风险子数据。", meta: runtime?.data_provider.details.mode || "Provider 检测中" },
        { title: "公开信息", state: searchConfigured ? "READY" : "NOT_CONFIGURED", summary: "主流搜索引擎与带引用的联网模型检索结果。", meta: "失败与零结果分别记录" },
        { title: "任务数据快照", state: runtime?.data_provider.state || "LOADING", summary: "每次排查冻结本次实际使用的企业数据、来源状态与数据时间。", meta: "可追溯 · 不随源数据漂移" },
      ],
      rows: [
        { item: "企业结构化数据", configuration: runtime?.data_provider.name || "正在检测", state: runtime?.data_provider.state || "LOADING", policy: "只读查询；必查失败即停止" },
        { item: "主流搜索引擎", configuration: searchEngine?.name || "未配置", state: searchEngine?.state || "NOT_CONFIGURED", policy: "失败与零结果严格区分" },
        { item: "联网模型检索", configuration: llmSearch?.name || "未配置", state: llmSearch?.state || "NOT_CONFIGURED", policy: "无可访问引用不可确认" },
        { item: "任务数据快照", configuration: "按任务冻结", state: runtime?.data_provider.state || "LOADING", policy: "报告只使用本次任务已冻结的数据" },
      ],
    },
    searchModels: {
      index: "C",
      eyebrow: "SEARCH & MODEL ROUTING",
      title: "搜索与模型",
      description: "搜索负责找到可追溯事实，模型负责意图理解、摘要和辅助研判；确定性风险规则不交给模型自由计算。",
      signal: searchConfigured ? "已接入" : "待接入",
      signalLabel: "外部搜索状态",
      cards: [
        { title: "主流搜索引擎", state: searchEngine?.state || "NOT_CONFIGURED", summary: "提供可访问网页、标题、摘要与抓取时间。", meta: searchEngine?.name || "等待 Provider" },
        { title: "联网大模型", state: llmSearch?.state || "NOT_CONFIGURED", summary: "只采纳带可访问引用的结果，不能用模型记忆充当证据。", meta: llmSearch?.name || "等待 Provider" },
        { title: "意图理解模型", state: runtime?.agent_model.state || "LOADING", summary: "识别企业查询、风险排查、报告生成与运营指令；失败时使用受限规则。", meta: runtime?.agent_model.name || "正在检测" },
      ],
      rows: [
        { item: "任务意图", configuration: runtime?.agent_model.name || "正在检测", state: runtime?.agent_model.state || "LOADING", policy: "规则降级不扩展任务范围" },
        { item: "网页检索", configuration: searchEngine?.name || "未配置", state: searchEngine?.state || "NOT_CONFIGURED", policy: "必须保留来源链接" },
        { item: "模型检索", configuration: llmSearch?.name || "未配置", state: llmSearch?.state || "NOT_CONFIGURED", policy: "引用不可访问则不进入证据池" },
      ],
    },
    riskRules: {
      index: "D",
      eyebrow: "DETERMINISTIC RISK POLICY",
      title: "规则与评分",
      description: "完整特征快照使用迁移规则重算；特征不完整时明确沿用旧系统分。运营调分始终保留原始分、原因与时间。",
      signal: runtime?.risk_scoring.details.rule_version || "RISK_RULES_V1",
      signalLabel: "生效规则版本",
      cards: [
        { title: "高风险", state: "READY", summary: "评分区间 [8, 10]，建议优先处置并重点核验。", meta: "LEVEL 05" },
        { title: "中高风险", state: "READY", summary: "评分区间 (6, 8]，需要完整证据和运营关注。", meta: "LEVEL 04" },
        { title: "中风险", state: "READY", summary: "评分区间 (4, 6]，进入常规风险说明。", meta: "LEVEL 03" },
        { title: "中低 / 低风险", state: "READY", summary: "(2, 4] 为中低风险，[0, 2] 为低风险。", meta: "LEVEL 01—02" },
      ],
      rows: [
        { item: "失联", configuration: "事件最低分 6", state: "READY", policy: "不得被其他低权重事件冲淡" },
        { item: "拖欠工资", configuration: "事件最低分 6", state: "READY", policy: "命中后原始分不得低于 6" },
        { item: "门店关闭", configuration: "事件最低分 8", state: "READY", policy: "命中后进入高风险区间" },
        { item: "迁移模式", configuration: runtime?.risk_scoring.details.migration_mode || "安全迁移", state: "READY", policy: "完整特征重算；缺失时沿用旧分" },
        { item: "人工调整", configuration: "原始分 / 人工分并存", state: "READY", policy: "原因与备注必填并留痕" },
      ],
    },
    reportTemplates: {
      index: "E",
      eyebrow: "DOCUMENT PRODUCTION",
      title: "报告模板",
      description: "V1 使用现有正式 DOCX 模板。模板只定义版式，报告内容来自冻结的数据、证据、评分和运营确认。",
      signal: runtime?.report_generation.details.format || "DOCX",
      signalLabel: "当前输出格式",
      cards: [
        { title: "企业风险监测分析报告", state: runtime?.report_generation.state || "LOADING", summary: "正式模板 V1，保留封面、企业概况、风险结论、证据与评分说明。", meta: runtime?.report_generation.name || "DOCX_V1" },
        { title: "版本与差异", state: "READY", summary: "每次生成形成独立版本，可下载并查看与上一版 Atlas 报告的差异。", meta: "VERSIONED OUTPUT" },
        { title: "PDF 输出", state: "NOT_CONFIGURED", summary: "V1 暂不生成 PDF，待 DOCX 链路稳定后再开启。", meta: "BACKLOG" },
      ],
      rows: [
        { item: "正式模板", configuration: "V1 DOCX", state: runtime?.report_generation.state || "LOADING", policy: "保持示例报告章节结构" },
        { item: "生成门禁", configuration: "数据 + 证据 + 评分 + 确认", state: "READY", policy: "任一门禁未完成不得生成" },
        { item: "追溯信息", configuration: "输入哈希 / 规则版本 / 操作人", state: "READY", policy: "与报告版本一起保存" },
      ],
    },
    operations: {
      index: "F",
      eyebrow: "RUNTIME OBSERVATORY",
      title: "运行监控",
      description: "集中观察服务、企业数据、搜索、模型、规则和报告链路的可用性，用业务语言提示哪些任务会被阻断。",
      signal: runtime?.service_status || "LOADING",
      signalLabel: "服务运行状态",
      cards: [
        { title: "企业数据", state: runtime?.data_provider.state || "LOADING", summary: runtime?.data_provider.name || "正在读取 Provider", meta: "SUBJECT & SNAPSHOT" },
        { title: "外部搜索", state: searchConfigured ? "READY" : "NOT_CONFIGURED", summary: searchConfigured ? "至少一个搜索 Provider 可用" : "尚未配置真实搜索 Provider", meta: "PUBLIC INTELLIGENCE" },
        { title: "意图模型", state: runtime?.agent_model.state || "LOADING", summary: runtime?.agent_model.name || "正在读取模型状态", meta: "AGENT ROUTER" },
        { title: "风险评分", state: runtime?.risk_scoring.state || "LOADING", summary: runtime?.risk_scoring.name || "正在读取规则状态", meta: "RISK ENGINE" },
        { title: "报告生成", state: runtime?.report_generation.state || "LOADING", summary: runtime?.report_generation.name || "正在读取报告状态", meta: "DOCUMENT SERVICE" },
      ],
      rows: [
        { item: "API 服务", configuration: "Atlas API", state: runtime?.service_status || "LOADING", policy: "不可用时停止所有新任务" },
        { item: "企业查询", configuration: runtime?.data_provider.name || "正在检测", state: runtime?.data_provider.state || "LOADING", policy: "查询失败即停止当前任务" },
        { item: "风险引擎", configuration: runtime?.risk_scoring.name || "正在检测", state: runtime?.risk_scoring.state || "LOADING", policy: "只执行已发布规则" },
        { item: "文档服务", configuration: runtime?.report_generation.name || "正在检测", state: runtime?.report_generation.state || "LOADING", policy: "失败可单独重试" },
      ],
    },
    audit: {
      index: "G",
      eyebrow: "TRACE & ACCOUNTABILITY",
      title: "审计日志",
      description: "任务步骤、证据决定、评分调整、运营确认和报告版本形成一条可还原的研判链。当前页面展示审计范围，记录按具体任务查看。",
      signal: "5 类",
      signalLabel: "核心留痕对象",
      cards: [
        { title: "任务步骤", state: "READY", summary: "记录每一步开始、完成、失败、重试和错误原因。", meta: "WORKFLOW EVENTS" },
        { title: "证据决定", state: "READY", summary: "记录确认、驳回、主体匹配以及操作人。", meta: "EVIDENCE DECISIONS" },
        { title: "评分调整", state: "READY", summary: "同时保存原始分、人工分、原因和备注。", meta: "SCORE SNAPSHOTS" },
        { title: "报告版本", state: "READY", summary: "记录输入哈希、生成时间、模板和下载版本。", meta: "REPORT VERSIONS" },
      ],
      rows: [
        { item: "任务执行", configuration: "步骤事件流", state: "READY", policy: "失败原因与重试次数不可覆盖" },
        { item: "人工研判", configuration: "操作人 + 时间 + 决定", state: "READY", policy: "调整必须说明原因" },
        { item: "报告追溯", configuration: "版本 + 内容哈希", state: "READY", policy: "下载版本可唯一定位" },
        { item: "统一身份", configuration: "开发期浏览器操作人", state: "ACTION_REQUIRED", policy: "生产接入统一身份后强化" },
      ],
    },
  };

  const current = sectionData[section] || sectionData.skills;

  return (
    <section className="workspace-page management-page">
      <div className="management-hero">
        <div className="management-hero-index">{current.index}</div>
        <div className="management-hero-copy">
          <span className="eyebrow">{current.eyebrow}</span>
          <h2>{current.title}</h2>
          <p>{current.description}</p>
        </div>
        <div className="management-signal">
          <small>{current.signalLabel}</small>
          <strong>{loading ? "···" : current.signal}</strong>
          <button className="text-action" type="button" onClick={() => void loadRuntime()}>
            {loading ? "检测中" : "刷新状态"} ↗
          </button>
        </div>
      </div>

      <div className="runtime-ticker" aria-label="运行摘要">
        <span><i className={runtime?.service_status === "UP" ? "online" : "offline"} />实例 {runtime?.service_status === "UP" ? "在线" : "检测中"}</span>
        <span>企业数据 <strong>{runtime?.data_provider.name || "—"}</strong></span>
        <span>搜索 <strong>{searchConfigured ? "已配置" : "待配置"}</strong></span>
        <span>模型 <strong>{runtime?.agent_model.state === "READY" ? "在线" : "规则降级"}</strong></span>
        <span>报告 <strong>{runtime?.report_generation.details.format || "—"}</strong></span>
      </div>

      <div className="registry-grid">
        {current.cards.map((item, index) => (
          <article key={item.title}>
            <div className="registry-card-head">
              <span>{String(index + 1).padStart(2, "0")}</span>
              <RuntimeState state={item.state} />
            </div>
            <h3>{item.title}</h3>
            <p>{item.summary}</p>
            <small>{item.meta}</small>
          </article>
        ))}
      </div>

      <div className="registry-ledger">
        <div className="ledger-heading">
          <div><span className="eyebrow">ACTIVE REGISTRY</span><h3>当前配置与执行边界</h3></div>
          <span>敏感认证信息不在浏览器中读取或回显</span>
        </div>
        <div className="ledger-table" role="table">
          <div className="ledger-row ledger-header" role="row">
            <span>项目</span><span>当前配置</span><span>状态</span><span>执行策略</span>
          </div>
          {current.rows.map((row) => (
            <div className="ledger-row" role="row" key={row.item}>
              <strong>{row.item}</strong>
              <span>{row.configuration}</span>
              <RuntimeState state={row.state} />
              <span>{row.policy}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function RuntimeState({ state = "LOADING" }: { state?: string }) {
  const labels: Record<string, string> = {
    READY: "正常",
    UP: "正常",
    RULE_FALLBACK: "规则降级",
    NOT_CONFIGURED: "待配置",
    ACTION_REQUIRED: "待处理",
    TEST_ONLY: "仅测试",
    LOADING: "检测中",
    DRAFT: "草稿待发布",
    VALIDATED: "已校验待发布",
    PUBLISHED: "已发布生效",
    INACTIVE: "历史版本",
    PASSED: "已通过",
    FAILED: "未通过",
  };
  const ready = state === "READY" || state === "UP" || state === "PUBLISHED" || state === "PASSED";
  return (
    <em className={`runtime-state ${ready ? "ready" : "waiting"}`}>
      {labels[state] || state}
    </em>
  );
}

function configurationVersionLabel(state: string) {
  const labels: Record<string, string> = {
    DRAFT: "草稿待发布",
    VALIDATED: "已校验待发布",
    PUBLISHED: "已发布生效",
    INACTIVE: "历史版本",
  };
  return labels[state] || state;
}

function SettingsView({
  apiBase,
  operatorId,
  theme,
  backendOnline,
  onSave,
}: {
  apiBase: string;
  operatorId: string;
  theme: Theme;
  backendOnline?: boolean;
  onSave: (value: { apiBase: string; operatorId: string; theme: Theme }) => void;
}) {
  const [draft, setDraft] = useState({ apiBase, operatorId, theme });
  return (
    <section className="workspace-page settings-page">
      <div className="page-intro">
        <div>
          <span className="eyebrow">LOCAL OPERATOR SETTINGS</span>
          <h2>工作台设置</h2>
          <p>开发阶段设置保存在当前浏览器；生产身份认证接入后将由统一身份提供。</p>
        </div>
      </div>
      <div className="settings-grid">
        <form
          className="settings-card"
          onSubmit={(event) => {
            event.preventDefault();
            onSave(draft);
          }}
        >
          <h3>连接与身份</h3>
          <label>
            <span>后端服务地址</span>
            <input
              value={draft.apiBase}
              onChange={(event) => setDraft({ ...draft, apiBase: event.target.value })}
              placeholder="http://localhost:8080"
            />
          </label>
          <label>
            <span>当前运营人员标识</span>
            <input
              value={draft.operatorId}
              onChange={(event) => setDraft({ ...draft, operatorId: event.target.value })}
              maxLength={64}
            />
          </label>
          <div className="connection-state">
            <span className={`status-dot ${backendOnline ? "online" : "offline"}`} />
            {backendOnline ? "已连接 Atlas 服务" : "尚未连接 Atlas 服务"}
          </div>
          <button className="primary" type="submit">保存设置</button>
        </form>
        <div className="settings-card">
          <h3>界面主题</h3>
          <div className="theme-options">
            <button
              type="button"
              className={draft.theme === "glacier" ? "selected" : ""}
              onClick={() => setDraft({ ...draft, theme: "glacier" })}
            >
              <span className="theme-preview glacier-preview" />
              <strong>冰川运营</strong>
              <small>清晰、冷静、高密度</small>
            </button>
            <button
              type="button"
              className={draft.theme === "jade" ? "selected" : ""}
              onClick={() => setDraft({ ...draft, theme: "jade" })}
            >
              <span className="theme-preview jade-preview" />
              <strong>墨玉政企</strong>
              <small>稳重、深邃、权威感</small>
            </button>
          </div>
          <button
            className="primary"
            type="button"
            onClick={() => onSave(draft)}
          >
            应用主题
          </button>
        </div>
        <div className="settings-card policy-card">
          <h3>当前业务门禁</h3>
          <ul>
            <li><strong>自然语言</strong><span>只创建任务和查询状态</span></li>
            <li><strong>证据确认</strong><span>必须由运营人员明确操作</span></li>
            <li><strong>人工改分</strong><span>保留原始分、原因和操作人</span></li>
            <li><strong>报告生成</strong><span>仅在正式运营确认有效后开放</span></li>
          </ul>
        </div>
      </div>
    </section>
  );
}
