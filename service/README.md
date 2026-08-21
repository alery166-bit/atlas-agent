# Atlas Enterprise Agent

Atlas V1 的服务框架，当前只实现“单企业旧风险报告更新”。

## 当前能力

- Java 21 + Spring Boot 3.5；
- Maven 多模块、模块化单体；
- H2 文件数据库和 Flyway；
- 任务创建、查询、幂等；
- 持久化任务事件和 SSE 重放；
- V1 状态枚举、失败语义和错误码；
- 风险等级边界单元测试；
- 固定任务工作流、数据库租约、步骤尝试记录和失败断点续跑；
- 企业主体识别、统一平台企业 ID 和来源身份绑定；
- JSON 样本优先、超大 CSV 逐行流式读取；
- 企业主档、工商变更、九类结构化风险源标准化；
- 企业事实、风险事件和每个来源状态的冻结快照；
- 必查来源失败立即进入 `SOURCE_FAILED`，成功但 0 条单独记录；
- 版本化、无外部副作用的风险评分引擎；
- 旧风险分独立留存，规则分、事件最低分、原始分和人工分分栏保存；
- 失联、欠薪最低 6 分，门店关闭最低 8 分，多事件取最大值；
- 人工改分原因、前后值和操作人可追溯，低于事件最低分返回警告；
- 正式 V1 DOCX 模板识别与旧报告内容解析；
- 基于冻结数据快照和评分快照生成新版 DOCX；
- 报告版本、输入哈希、内容哈希、生成差异和失败原因持久化；
- 同一任务、同一输入幂等复用已生成报告；
- 报告列表、差异查询和 DOCX 下载接口；
- 失联、欠薪、闭店三类公开检索计划和多 Provider 契约；
- 企业全称、简称、曾用名、品牌、门店、官网及社交账号等身份词持久化，支持人工确认后补充检索；
- 公开检索成功、空结果、失败三态及失败批次持久化；
- URL 归一化、内容指纹去重、企业主体匹配和证据分级；
- 证据确认/驳回操作留痕，无可访问引用的大模型线索禁止直接确认；
- 候选证据网页正文按任务冻结，保留提取文本、原始内容哈希、文本哈希、抓取时间与失败原因；
- 网页抓取默认拒绝回环、链路本地和内网地址，限制响应体大小且不自动跳转；
- 已确认证据转换为风险事件并进入现有评分引擎；
- 经人工确认的证据及最新成功正文快照写入正式 DOCX，保留来源链接和抓取时间；
- PostgreSQL/ES/搜索等模块边界已预留。
- 可选的报告前企业信息刷新模块，覆盖 31 类接口、主体一致性校验、逐类别成功/空结果/失败状态；
- 持久化多轮 Agent 会话、消息历史、槽位继承和幂等重放；
- 旧 DOCX 报告安全上传、文件哈希和路径隔离；
- 完整运营门户和 Docker Compose 运行定义。

## 启动

Windows：

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -pl atlas-bootstrap -am spring-boot:run
```

服务默认监听 `http://localhost:8080`，健康检查为：

```text
GET /actuator/health
```

创建任务：

```http
POST /api/tasks
Idempotency-Key: demo-001
X-Operator-Id: local-operator
Content-Type: application/json

{
  "prompt": "更新北京简熹和食品有限公司的风险报告",
  "company_query": "北京简熹和食品有限公司",
  "previous_report_file_id": "report-v1"
}
```

执行到当前已实现边界：

```text
POST /api/tasks/{taskId}/execute
```

首次执行成功后任务状态为 `SEARCHING_PUBLIC_INTELLIGENCE`，表示结构化数据快照已经冻结。再次执行会运行已配置的公开检索 Provider：成功后进入 `CALCULATING_RISK`；必选 Provider 失败则进入 `SOURCE_FAILED`，同时保留失败批次。

查询执行结果：

```text
GET /api/tasks/{taskId}/steps
GET /api/tasks/{taskId}/snapshot
GET /api/companies/resolve?query=北京简熹和食品有限公司
```

公开检索、证据确认和证据评分：

```text
POST /api/tasks/{taskId}/public-intelligence/search
GET  /api/tasks/{taskId}/public-intelligence/searches
GET  /api/tasks/{taskId}/public-intelligence/evidence
POST /api/tasks/{taskId}/public-intelligence/evidence/model-review
POST /api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/content-snapshot
GET  /api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/content-snapshot
GET  /api/tasks/{taskId}/public-intelligence/content-snapshots
POST /api/tasks/{taskId}/public-intelligence/evidence/{evidenceId}/decision
GET  /api/tasks/{taskId}/public-intelligence/decisions
GET  /api/tasks/{taskId}/public-intelligence/confirmed-events
POST /api/tasks/{taskId}/risk-score/calculate-from-confirmed-evidence
```

企业身份词查询与人工补录：

```text
GET  /api/tasks/{taskId}/company-aliases
POST /api/tasks/{taskId}/company-aliases
```

新闻检索不会要求标题或摘要包含统一社会信用代码。系统使用已确认的企业全称、
简称、曾用名、品牌、门店、官网和社交账号进行检索与主体匹配；人工补录身份词后，
再次发起公开检索只执行新增查询计划，并保留身份词关系、来源依据和操作审计。

`test` profile 提供明确标记的离线搜索与正文抓取夹具。非测试环境未配置真实 Provider 时会返回 `SEARCH_PROVIDER_NOT_CONFIGURED`，正文抓取未启用时会返回明确失败快照，不会生成虚假舆情或网页正文。

真实搜索服务通过 `atlas.intelligence.search.providers` 配置。每个 Provider
可独立设置搜索引擎/联网模型模式、地址、查询参数、结果上限、超时、限流、
是否必选、是否返回可访问引用及 API Key 请求头。配置为启用且要求密钥的
Provider 若缺少密钥，应用启动即失败，避免带着无效配置运行。

网页正文抓取由 `atlas.intelligence.content` 控制，默认关闭；启用后仍默认
禁止访问内网地址。开发环境确需抓取内网夹具时，必须显式开启
`allow-private-network`，生产环境不建议开启。

基于冻结快照计算风险分。`confirmed_events` 只能传入运营人员或后续证据核验流程已经确认的事件：

```http
POST /api/tasks/{taskId}/risk-score/calculate
Content-Type: application/json

{
  "confirmed_events": [
    {
      "risk_type": "STORE_CLOSURE",
      "reference_id": "finding-001",
      "title": "已确认门店关闭",
      "evidence_ids": ["evidence-001"]
    }
  ]
}
```

查询评分和人工调整：

```text
GET  /api/tasks/{taskId}/risk-score
POST /api/tasks/{taskId}/risk-score/{scoreSnapshotId}/adjustments
GET  /api/tasks/{taskId}/risk-score/decisions
```

基于旧报告、最新冻结快照和最新评分快照生成 DOCX：

```text
POST /api/tasks/{taskId}/reports
GET  /api/tasks/{taskId}/reports
GET  /api/tasks/{taskId}/reports/{reportId}/diff
GET  /api/tasks/{taskId}/reports/{reportId}/download
```

`POST /reports` 只接受已经具备企业快照和评分快照的任务。报告仅纳入
`CONFIRMED` 证据，优先使用最新成功网页正文快照，否则回退到检索摘要；
欠薪证据进入“互联网投诉”，其他负面公开证据进入“网络舆情”。证据 ID、
正文哈希和截断状态共同参与报告输入哈希，因此证据或正文发生变化时会创建
新报告版本，不会误复用旧文件。下载响应包含 `X-Content-SHA256`，便于客户端
校验文件完整性。

多候选主体确认：

```http
POST /api/tasks/{taskId}/subject-confirmation
Content-Type: application/json
X-Operator-Id: local-operator

{
  "source_system": "OFFLINE_CSV",
  "source_entity_id": "q1c2cc690e31c11f0978b00163e0ee983"
}
```

可重试的来源失败可以从失败步骤继续：

```text
POST /api/tasks/{taskId}/retry
```

订阅事件：

```text
GET /api/tasks/{taskId}/events
```

## 运行模式

- `local`：默认，H2文件库、本地文件、离线数据；
- `test`：H2内存库；
- `docker`：PostgreSQL配置骨架；
- `production`：后续补充生产数据库、对象存储、ES和真实搜索Provider。

本地离线数据位置可通过环境变量覆盖：

```powershell
$env:ATLAS_COMPANY_DATA_ROOT = "D:\data\company_data"
```

默认读取 `../data/company`，并将 `../data/samples` 中的两个 ES JSON 样本作为快速回归来源。JSON 未命中时才扫描 `company_base.csv`；所有 CSV 查询均为流式读取，不会把整个文件加载到内存。

## 项目结构

```text
atlas-domain
atlas-application
atlas-agent
atlas-adapter-offline
atlas-adapter-search
atlas-adapter-storage
atlas-adapter-es
atlas-adapter-company-refresh
atlas-adapter-docx
atlas-api
atlas-worker
atlas-bootstrap
```

## W7 运营确认与报告门禁

报告生成前必须完成正式运营确认，测试和业务流程不再直接修改任务状态。

```text
POST /api/tasks/{taskId}/operator-confirmation
GET  /api/tasks/{taskId}/operator-confirmations
POST /api/tasks/{taskId}/reports
```

确认前必须完成风险评分，并将全部公开证据标记为 `CONFIRMED` 或 `REJECTED`。
确认记录绑定最新企业数据快照、评分、人工调整记录、证据状态和网页正文快照。
确认后若评分或证据发生变化，原确认自动失效；运营人员重新确认后才能生成报告。
新报告版本保存 `operator_confirmation_id`，V7 以前的历史报告继续兼容。

实现与接口说明见：

- `../design/W7运营确认与报告门禁实现记录.md`

## W8 任务工作台聚合接口

运营工作台进入任务时可以通过一个接口取得首屏所需状态：

```text
GET /api/tasks/{taskId}/workspace
```

响应聚合任务、企业快照、风险评分、证据进度、最新运营确认、报告版本和执行步骤，
同时返回 `readiness_blockers` 与 `next_action`。前端可以据此显示“处理证据”“计算风险分”
“重新确认”“生成报告”或“下载报告”等主操作，不必复制服务端状态机判断。

完整说明见：

- `../design/W8任务工作台聚合接口实现记录.md`

详细设计见：

- `../design/Atlas企业情报Agent详细设计V1.2.md`
- `../design/Atlas企业情报AgentV1开发计划.md`
- `../design/W4旧报告与DOCX纵向链路实现记录.md`
- `../design/W4正式DOCX模板执行契约.md`
- `../design/W5公开检索与证据链详细设计.md`
- `../design/W5公开检索与证据链实现记录.md`
- `../design/W6搜索接入与报告证据落版实现记录.md`

## W9 运营任务列表

运营首页可以按关键字、任务状态和操作人筛选任务，并使用稳定游标翻页：

```text
GET /api/tasks?query=企业名称&status=WAITING_OPERATOR_CONFIRMATION&operator_id=operator-1&page_size=20
```

每条任务同时返回最新风险分、证据进度、确认状态、阻塞原因、建议下一步操作、
最新确认和最新报告摘要。列表采用批量加载，不会对每条任务分别查询快照、评分、
证据和报告。

完整说明见：

- `../design/W9运营任务列表与批量摘要实现记录.md`

## W10 受控对话式任务入口

对话页面可以通过自然语言创建风险报告更新任务或查询任务状态：

```text
POST /api/agent/messages
```

创建任务时需提供 `Idempotency-Key`、完整企业名称或统一社会信用代码，以及
`previous_report_file_id`。缺少输入时接口返回 `required_inputs`，存在任务时返回
完整 `workspace` 和 `suggested_actions`。

当前对话入口不会直接确认/驳回证据、修改风险分或生成正式报告；这些操作仍使用现有
结构化接口和服务端门禁。完整说明见：

- `../design/W10受控对话式任务入口实现记录.md`

## W11 多轮会话与运营门户

会话和消息可以持久化恢复，企业、旧报告和任务上下文可以跨消息继承：

```text
POST /api/agent/conversations
GET  /api/agent/conversations
GET  /api/agent/conversations/{conversationId}/messages
POST /api/agent/conversations/{conversationId}/messages
POST /api/files/previous-reports
```

运营端位于 `../console`，包含对话、任务、报告、能力/数据源和设置页面。
完整说明见：

- `../design/W11多轮会话与运营门户实现记录.md`
- `../design/Atlas运营操作手册V1.md`
- `../design/Atlas部署与运维手册V1.md`
- `../design/AtlasV1技术验收记录.md`

## 已知边界

- 当前已完成标准 HTTP 搜索 Provider、配置校验和离线流程验收，尚未配置实际搜索服务、联网模型账号和 ES；
- 已确认证据已能写入正式 DOCX；联网搜索结果质量、网页反爬兼容和来源许可仍需在实际 Provider 接入后验收；
- 当前评分兼容层读取离线快照中的旧分；旧 `RiskScoreService` 的细粒度纯规则仍需用黄金样本逐条迁移；
- 当前执行由同步 API 触发，数据库租约已具备，定时 Worker 领取后续补充；
- Docker 构建与 Compose 定义已完成；当前开发机未安装 Docker，尚未做容器实机拉起验证；
- `X-Operator-Id` 是开发期身份占位，生产需接入受信网关或统一身份。
