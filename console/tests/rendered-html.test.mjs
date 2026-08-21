import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(
    new Request(`http://localhost${path}`, {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("renders the Atlas dialogue entry", async () => {
  const response = await render("/");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /<title>Atlas 企业风险研判工作台<\/title>/i);
  assert.match(html, /企业风险研判/);
  assert.match(html, /Atlas 工作台/);
  assert.match(html, /待处理/);
  assert.match(html, /任务记录/);
  assert.match(html, /系统管理/);
  assert.match(html, /搜索与模型/);
  assert.doesNotMatch(html, />企业查询</);
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/i);
});

test("dialogue visually separates operator and Atlas messages", async () => {
  const [source, styles] = await Promise.all([
    readFile(new URL("../app/AtlasConsole.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);
  assert.match(source, /message-role-mark/);
  assert.match(source, /message\.task_id \? "has-task"/);
  assert.match(styles, /\.message\.user \{ grid-template-columns: minmax\(0,1fr\) 38px; \}/);
  assert.match(styles, /\.message\.user \.message-avatar \{[\s\S]*?grid-column: 2;/);
  assert.match(styles, /\.message\.assistant \.message-content \{[\s\S]*?border-left: 3px solid/);
});

test("conversation archive, duplicate-company hint, report diff and pending layout are wired", async () => {
  const [source, styles] = await Promise.all([
    readFile(new URL("../app/AtlasConsole.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);
  assert.match(source, /method: "DELETE"/);
  assert.match(source, /同企业 \$\{sameCompanyCount\} 次/);
  assert.match(source, /\/reports\/diff\?taskId=/);
  assert.match(source, /useSyncExternalStore/);
  assert.match(styles, /\.pending-workspace-page \.task-workspace-grid \{ grid-template-columns: minmax\(430px,\.78fr\) minmax\(600px,1\.22fr\)/);
  assert.match(styles, /\.pending-workspace-page \.task-detail \{[\s\S]*?max-height: none; overflow: visible;/);
});

test("report archive only offers downloads for the current valid formal report", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  assert.match(source, /item\.confirmation_state === "VALID"/);
  assert.match(source, /item\.next_action === "DOWNLOAD_REPORT"/);
  assert.match(source, /需重新确认并生成新报告/);
});

test("renders all operator routes", async () => {
  for (const [path, label] of [
    ["/companies", "企业查询"],
    ["/pending", "只处理 Atlas 无法判断的事项"],
    ["/tasks", "任务记录"],
    ["/reports", "正式风险排查报告"],
    ["/skills", "Skills 能力管理"],
    ["/data-sources", "数据源连接档案"],
    ["/search-models", "搜索与模型"],
    ["/risk-rules", "规则与评分"],
    ["/report-templates", "报告模板"],
    ["/operations", "运行监控"],
    ["/audit", "审计日志"],
    ["/acceptance", "验收评估"],
    ["/capabilities", "Skills 能力管理"],
    ["/settings", "工作台设置"],
  ]) {
    const response = await render(path);
    assert.equal(response.status, 200);
    assert.match(await response.text(), new RegExp(label));
  }
});

test("enterprise lookup confirms a subject before handing it to the agent", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/companies/resolve?query=",
    "企业名称或统一社会信用代码",
    "数据时间",
    "当前页面只用于确认主体，不作为报告快照",
    "交给 Atlas 发起排查",
    "new URLSearchParams(window.location.search)",
    'normalized === "[]"',
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("task workbench wires every controlled operator action", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/subject-confirmation",
    "/subject-data-conflict-resolution",
    "确认以当前主档为准并继续",
    "/calculate-from-confirmed-evidence",
    "/adjustments",
    "/operator-confirmation",
    "/company-aliases",
    "matched_identity_term",
    "保存并补充检索",
    "/reports",
    "new EventSource(",
    "TASK_EVENT_TYPES",
    "public.intelligence.evidence.decided",
    "risk.score.calculated",
    "risk.score.adjusted",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("runtime management uses live status without exposing secrets", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  assert.match(source, /\/api\/runtime\/capabilities/);
  assert.match(source, /敏感认证信息不在浏览器中读取或回显/);
  assert.match(source, /prefetch=\{false\}/);
});

test("risk rule management wires draft replay publish and rollback controls", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/platform/risk-rules",
    "/api/platform/risk-rules/traceability",
    "/api/platform/configurations/versions/",
    "保存草稿",
    "回放 5 份开发样本",
    "发布为生效版本",
    "回滚到此版本",
    "影响任务",
    "草稿已变化",
    "RiskEnum 39项逐项对应关系",
    "风险事实如何进入评分",
    "标签不等于分数",
    "事实来源",
    "规则计分",
    "RiskEnum外仍参与打分的历史标签",
    "完整重算所需输入与当前数据就绪度",
    "隐含活动标签",
    "缺数据依赖",
    "V1审计修正",
    "沿用物化分",
    "待业务确认",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("task score lineage uses current data sources without a previous report", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "ES物化旧模型分",
    "迁移规则计算分",
    "系统原始分",
    "当前人工分",
    "LEGACY_BASE_TOTAL",
    "该结果不代表旧评分模型已完整重算",
  ]) {
    assert.match(source, new RegExp(contract));
  }
  assert.doesNotMatch(source, /输入旧报告分|相对旧报告变化|previous_report_score/);
  assert.doesNotMatch(source, /运营人员上传现有 DOCX|旧风险报告.*安全上传|识别企业查询、旧报告更新/);
  assert.match(source, /任务数据快照/);
});

test("operator console treats a completed no-hit investigation as a formal result", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "排查结论",
    "暂未发现明确风险",
    "排查未完成",
    "不能形成未发现风险的结论",
    "查询失败的任务必须停止",
    "正式风险排查报告",
  ]) {
    assert.match(source, new RegExp(contract));
  }
});

test("connector management wires mapping connection test and version controls", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/platform/connectors",
    "mapping-preview",
    "测试连接",
    "服务器密钥引用（不是密钥值）",
    "必查失败即停止",
    "身份词与来源聚合",
    "召回阶段不使用风险标签",
    "黑猫投诉",
    "意图理解与自动证据研判",
    "自动采纳高置信判断",
    "automatic_decision_threshold",
    "任务消费",
    "未接入，禁止发布",
    "failure_policy: event.target.checked ? \"STOP\" : \"OPTIONAL\"",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("skill and report template management wire real versioned controls", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/platform/skills",
    "内置执行器（不可编辑）",
    "发布前必须可用",
    "/api/platform/report-templates",
    "上传新 DOCX 形成草稿",
    "字段定位",
    "校验模板",
    "发布模板",
    "失败策略（内置固定）",
    "输入字段（执行器声明，只读）",
    "内置代码固定",
    "执行器已内置",
    "DEV 运行绑定",
    "新任务必须冻结 5 个已发布且启用的 Skill",
    "key === \"company_name\"",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("operations and audit pages use real observability contracts", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/platform/operations",
    "调用次数和 Token 来自任务实际执行记录",
    "失败任务下钻",
    "/api/platform/audit?",
    "/api/platform/audit/export",
    "/api/platform/configuration-changes",
    "变更前",
    "变更后",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("golden acceptance page wires manifest import and formal quality gates", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "/api/platform/golden-acceptance/suites",
    "业务黄金样本集",
    "严重主体错配",
    "重大风险有证据",
    "评分可解释",
    "DOCX 核心字段",
    "Critical / High",
    "默认不勾选通过",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("operator console keeps a readable and consistent type scale", async () => {
  const css = await readFile(
    new URL("../app/globals.css", import.meta.url),
    "utf8",
  );
  assert.match(css, /--font-readable-caption:\s*12px/);
  assert.match(css, /--font-readable-small:\s*13px/);
  assert.match(css, /--font-readable-body:\s*14px/);
  assert.match(css, /grid-template-columns:\s*repeat\(auto-fit,minmax\(150px,1fr\)\)/);
  assert.match(css, /\.failure-row\s*\{[^}]*min-width:\s*980px/s);
  assert.match(css, /\.case-evaluation-head,\s*\.case-evaluation-row\s*\{[^}]*min-width:\s*940px/s);
});

test("operations distinguish active waiting stalled and failed tasks", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of [
    "activity_threshold_minutes",
    "活跃执行",
    "待人工处理",
    "已停滞",
    "停滞任务下钻",
    "分钟内有状态进展的系统任务才计为活跃",
  ]) {
    assert.match(source, new RegExp(contract));
  }
});

test("acceptance page explains the E0 to E5 definition of done", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  for (const contract of ["DEFINITION OF DONE", "E0", "E1", "E2", "E3", "E4", "E5", "页面存在只到 E1", "真实业务闭环达到 E4"]) {
    assert.match(source, new RegExp(contract));
  }
});

test("dialogue creates investigations without uploading a previous DOCX", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  assert.match(source, /企业数据将从已配置数据源直接读取/);
  assert.match(source, /只有无法可靠判断的异常才会请你处理/);
  assert.doesNotMatch(source, /添加原风险报告|选择原风险报告 DOCX|previous_report_file_id/);
});

test("V1.1 foreground focuses on autonomous work and exception-only intervention", async () => {
  const [source, css] = await Promise.all([
    readFile(new URL("../app/AtlasConsole.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);
  for (const contract of [
    'label: "Atlas 工作台"',
    'label: "待处理"',
    'label: "任务记录"',
    'mode="pending"',
    "自动连续执行",
    "异常才找人工",
    "Atlas 自动执行中",
    "下载正式报告",
    "/pending?id=",
    "window.setInterval",
  ]) {
    assert.match(source, new RegExp(contract.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.match(css, /\.management-menu\s*\{/);
  assert.match(css, /\.inline-task\.agent-delivery-card\s*\{/);
});

test("dialogue can create idempotency keys on an internal HTTP origin", async () => {
  const source = await readFile(
    new URL("../app/AtlasConsole.tsx", import.meta.url),
    "utf8",
  );
  assert.match(source, /function newClientId\(\)/);
  assert.match(source, /cryptoApi\?\.getRandomValues/);
  assert.doesNotMatch(source, /message_id:\s*crypto\.randomUUID/);
  assert.doesNotMatch(source, /"Idempotency-Key":\s*crypto\.randomUUID/);
});
