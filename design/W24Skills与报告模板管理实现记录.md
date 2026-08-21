# W24 Skills 与报告模板管理实现记录

## 1. 完成结论

W24 已完成。Skills 和报告模板已从只读登记页升级为真实管理工作台，统一使用 W21 的草稿、校验、发布、回滚和任务冻结版本底座。

页面只能配置受控业务契约，不能上传、编译或替换 Java 执行代码。DOCX 模板可以上传，但只能作为文档版式与字段定位资产，不具备代码执行能力。

## 2. Skill 管理

系统登记 5 个 V1 核心 Skill：

| Skill | 内置执行器 | 运行位置 |
|---|---|---|
| `company.resolve` | `builtin.company.resolve.v1` | 企业主体识别 |
| `company.snapshot` | `builtin.company.snapshot.v1` | 企业数据冻结 |
| `intelligence.search` | `builtin.intelligence.search.v1` | 公开信息检索 |
| `risk.score` | `builtin.risk.score.v1` | 确定性风险评分 |
| `report.generate` | `builtin.report.generate.v1` | DOCX 报告生成 |

每个 Skill 可配置：

- 启用/停用；
- 失败停止或可选继续；
- 输入字段契约；
- 输出字段契约；
- 结构化业务参数；
- 数据源、搜索、模型、规则、模板和其他 Skill 依赖；
- 依赖是否为发布前必需。

`skill_key` 与 `executor_key` 的对应关系由后端固定。尝试把某一 Skill 指向其他执行器会在校验阶段被拒绝。

Skill 启停已进入真实任务执行：主体识别、企业快照、公开搜索、风险评分和报告生成在执行前都会读取任务冻结 Skill 版本。没有 Skill 配置的历史任务继续按原逻辑运行；冻结为停用的任务返回 `SKILL_DISABLED`，不会静默跳过。

## 3. 依赖发布门禁

Skill 和报告模板均支持跨类别依赖。依赖标记为“发布前必须可用”时，发布会检查：

- 配置键是否已登记；
- 实际类别是否与声明一致；
- 目标环境是否有生效版本。

任一检查不通过时返回配置冲突，草稿不能发布。默认依赖先设为可选，适配当前开发环境；平台管理员可在页面按生产要求切换为必需。

## 4. DOCX 模板管理

报告模板采用 `atlas-report-template.v1` 配置契约，记录：

- DOCX 内容寻址 ID 与 SHA-256；
- 原始文件名；
- 业务模板版本；
- 输出格式；
- 企业名称、信用代码、法定代表人、经营状态、风险分、风险标签、网络舆情字段定位；
- Skill、规则和模型依赖。

管理页支持：

- 一键登记当前 V1 正式模板；
- 上传新 DOCX 形成草稿；
- 查看段落数、表格数、检测到的字段标记和缺失标记；
- 编辑字段定位和模板依赖；
- 下载任一模板版本；
- 校验、发布和回滚。

正式 V1 模板检查结果为 142 个段落、5 个表格，核心企业字段结构通过。

## 5. 模板安全与持久化

上传入口限制：

- 文件名必须为 `.docx`；
- 文件大小不超过 20 MB；
- 必须包含 DOCX 关键 OOXML 部件；
- ZIP 部件数量最多 512；
- 单个解压部件最多 20 MB；
- 解压总量最多 100 MB；
- 拒绝异常包路径；
- 内容按 SHA-256 寻址并在读取时再次校验。

Docker 使用 `ATLAS_MANAGED_TEMPLATE_ROOT=/data/files/report-templates`，复用 `atlas-files` 持久化卷，容器重建不会丢失上传模板。

## 6. 任务运行时闭环

新任务创建时，配置快照同时冻结 Skill 和报告模板版本。报告生成读取任务冻结模板，不读取此刻最新模板，因此：

- 新发布模板只影响之后创建的任务；
- 历史任务仍可复现原模板；
- 报告版本保存业务模板版本；
- 报告输入哈希包含模板版本和模板内容哈希；
- 任务配置快照保存具体配置版本与校验和。

字段定位不是只读说明：已发布映射会实际参与工商字段、风险分、风险标签和网络舆情章节的 DOCX 定位。

## 7. 接口

- Skill 列表：`GET /api/platform/skills`；
- Skill 初始化：`POST /api/platform/skills/initialize`；
- 内置执行器目录：`GET /api/platform/skills/catalog`；
- 模板列表：`GET /api/platform/report-templates`；
- 现有模板登记：`POST /api/platform/report-templates/initialize`；
- DOCX 上传：`POST /api/platform/report-templates/uploads`；
- 模板预览：`GET /api/platform/report-templates/versions/{versionId}/preview`；
- 模板下载：`GET /api/platform/report-templates/versions/{versionId}/download`；
- 草稿编辑、校验、发布和回滚复用统一配置版本接口。

## 8. 验证结果

- 后端全量测试：92 项，0 失败、0 错误、0 跳过；
- W24 管理集成测试：2 项，覆盖 Skill 依赖门禁、冻结停用、正式模板识别、无效 DOCX、缺字段和任务冻结模板；
- 运营端生产构建：12 个路由成功；
- 运营端页面测试：7 项全部通过。

## 9. W25 衔接

W25 进入真实运行监控与审计：把任务吞吐、步骤耗时、失败分布、搜索调用、评分、报告生成、配置变更和人工决定聚合为可查询指标，并提供按任务下钻、受控重试、审计导出与配置版本差异。
