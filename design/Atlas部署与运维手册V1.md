# Atlas 部署与运维手册 V1

## 1. 部署组成

V1 由 PostgreSQL、`service` 和 `console` 三部分组成。开发期可使用 H2
和离线 JSON/CSV；生产期切换 PostgreSQL、ES 和真实搜索 Provider，不改变任务、
评分、证据和报告领域模型。

## 2. 本地开发启动

要求 Java 21、Node.js 22+。后端和前端分别执行：

```powershell
cd service
.\mvnw.cmd clean verify
.\mvnw.cmd -pl atlas-bootstrap -am spring-boot:run
```

```powershell
cd console
npm install
npm run dev
```

健康检查：`GET http://localhost:8080/actuator/health`。运营端：
`http://localhost:3000`。

## 3. Docker 启动

要求 Docker Engine 支持 Compose V2。

```powershell
Copy-Item .env.docker.example .env
docker compose config
docker compose up --build -d
docker compose ps
```

首次启动等待 PostgreSQL 和后端健康后再访问运营端。`.env` 不得提交代码库。数据库
密码必须替换；真实搜索开关保持 `false`，直到地址、凭据和引用能力全部确认。

## 4. 持久化与备份

Compose 使用三个业务卷：

- `atlas-postgres`：任务、会话、评分、证据、确认和报告元数据；
- `atlas-files`：生成的 DOCX；
- `atlas-previous-reports`：仅为既有历史任务保留的兼容文件，不是新任务输入。

备份必须同时覆盖数据库、两个文件卷、`.env`、Compose 定义、部署文件和默认 DOCX 模板。
使用 `scripts/backup-docker.sh` 生成带 SHA-256 清单的同批次备份，再用
`scripts/restore-drill-docker.sh <备份目录>` 恢复到临时数据库和临时目录。演练不会覆盖当前
数据库或报告卷；恢复后会比较关键表行数及全部文件内容哈希，并自动清理临时对象。

## 5. 配置

关键环境变量：

| 变量 | 作用 |
|---|---|
| `ATLAS_DB_URL/USERNAME/PASSWORD` | PostgreSQL 连接 |
| `ATLAS_COMPANY_DATA_ROOT` | 离线企业数据目录 |
| `ATLAS_REPORT_TEMPLATE` | V1 正式 DOCX 模板 |
| `ATLAS_PREVIOUS_REPORT_ROOT` | 历史任务兼容文件目录；新任务不读取 |
| `ATLAS_ALLOWED_ORIGINS` | 运营端允许来源 |
| `ATLAS_SEARCH_PRIMARY_*` | 主搜索服务 |
| `ATLAS_SEARCH_LLM_*` | 带可访问引用的联网模型服务 |

两个搜索 Provider 均可分别配置：

- `MAX_ATTEMPTS`：单次查询最大尝试次数；
- `RETRY_BACKOFF`：重试退避基数；
- `CIRCUIT_FAILURE_THRESHOLD`：连续失败多少次后开路；
- `CIRCUIT_OPEN_DURATION`：开路持续时间。

默认值以 `application.yml` 为准。只对限流、上游 5xx、超时和网络 I/O 失败重试；
参数错误等确定性 4xx 不重试。熔断半开状态只允许一个探测请求。

搜索 Provider 设为启用且要求密钥时，缺少密钥会阻止应用启动。必选来源运行失败时任务
进入 `SOURCE_FAILED`，不会继续评分或生成报告。

## 6. 日常运维

至少监控：

- `/actuator/health`；
- 任务失败状态及错误码；
- 搜索 Provider 成功率、空结果率和耗时；
- 待处理证据与待运营确认数量；
- DOCX 生成失败率和文件哈希校验；
- Worker 租约超时、重试次数和积压量；
- 数据库、正式报告卷和历史兼容卷容量。

`/actuator/metrics` 可查询以下关键指标：

- `atlas.search.requests`、`atlas.search.duration`、`atlas.search.results`；
- `atlas.search.upstream.calls`、`atlas.search.retries`；
- `atlas.search.circuit.state`、`atlas.search.circuit.opened`；
- `atlas.business.task.status.transitions`、`atlas.business.task.terminal.duration`；
- `atlas.business.operator.actions`、`atlas.business.evidence.decisions`；
- `atlas.business.risk.scores`、`atlas.business.reports`；
- `atlas.business.workflow.failures`。

业务指标只使用状态、动作、结果、风险等级和受控错误码等低基数标签，不写入企业名、
任务 ID、运营人员 ID 或联系方式。

故障处理遵循“先保留现场、再重试”：记录任务 ID、步骤、trace ID、来源批次和错误码，
确认是可重试失败后调用结构化重试接口。不要直接修改数据库状态。

## 7. 发布与回滚

发布前执行后端 `clean verify`、北京样本烟测、`scripts/golden-regression.ps1`、
前端 `npm test`、`scripts/w13-console-e2e.ps1` 和 `docker compose config`。
数据库迁移只向前执行；回滚应用前必须确认旧版本是否理解当前 Flyway 版本。报告文件
采用新版本追加，禁止覆盖已发布版本。

## 8. 环境验收状态

截至 2026-08-18，`10.210.0.62` 的 PostgreSQL 17、ES 9.4.2、后端和运营端均正常运行，
Flyway 已迁移到 V23，后端健康检查和浏览器页面通过。
体验环境使用运营端 `8300`、后端 `8301`，以避开服务器现有的 `8080` 服务。

当前已接入百企业 ES 验证数据、Tavily 和百炼兼容模型。进入正式生产前仍需补齐统一身份、
TLS、生产 ES 鉴权与安全参数，并完成正式发布版本的应用回滚演练和业务黄金样本签字验收。
