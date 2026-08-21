# Atlas 企业风险研判 Agent

Atlas V1 面向企业风险排查运营人员，只处理一件事：直接读取企业主数据、公开信息与受控
评分规则，完成单企业风险研判并输出正式 DOCX 报告。新任务不要求上传或选择历史报告。

## 目录

- `service`：Java 21 / Spring Boot 后端，包含任务、企业数据、评分、证据、报告和 Agent 会话。
- `console`：对话式运营工作台，包含任务、证据、报告、能力和设置页面。
- `data/company`：开发期离线企业数据。
- `data/samples`、`data/templates`、`data/reference`：样本、正式报告模板和数据字典。
- `data/golden`：评分黄金样本清单、Schema 和真实样本引用约定。
- `design`：详细设计、阶段实现记录、操作手册、部署手册和技术验收记录。
- `deploy`、`compose.yaml`：PostgreSQL、后端和运营端的容器化定义。

## 本地开发

后端：

```powershell
cd service
.\mvnw.cmd clean verify
.\mvnw.cmd -pl atlas-bootstrap -am spring-boot:run
```

运营端（另开终端）：

```powershell
cd console
npm install
npm run dev
```

打开 `http://localhost:3000`。默认后端为 `http://localhost:8080`，也可在“系统设置”
中修改。开发身份默认是 `local-operator`。

离线回归与真实浏览器验收：

```powershell
.\scripts\golden-regression.ps1
.\scripts\w13-console-e2e.ps1
```

浏览器验收会临时启动 H2 后端、fixture 搜索和运营端，并使用本机 Chrome 完成对话建任务、
企业数据读取、证据处置、评分调整、运营确认、DOCX 生成与下载。

## Docker 运行

复制 `.env.docker.example` 为 `.env`，至少修改数据库密码，然后执行：

```powershell
docker compose up --build
```

当前开发机未安装 Docker，但已在 `10.210.0.62` 完成实际 Compose 构建和拉起验证：

- 运营端：`http://10.210.0.62:8300`
- 后端健康检查：`http://10.210.0.62:8301/actuator/health`
- 远端目录：`/opt/atlas-agent`

可选 ES 9.4.2 百企业验证环境使用独立 Compose profile：

```bash
docker compose --profile es-dev build elasticsearch-dev
docker compose --profile es-dev up -d elasticsearch-dev
python3 scripts/es-dev-install.py --reset
```

后端切换到 ES 数据模式：

```bash
ATLAS_DATA_PROVIDER=es docker compose --profile es-dev up -d --no-build service
python3 scripts/es_adapter_smoke.py \
  --output work/es-validation/es-adapter-smoke-20260803.json
```

ES 仅绑定远端宿主机 `127.0.0.1:9200`，后端通过 Compose 内部网络访问，不对办公网络
公开。完整结果见 `design/AtlasES9.4.2百企业验证记录-20260803.md` 和
`design/AtlasESAdapter实现与验证记录-20260803.md`。

### Cerebro ES 查看工具

远端验证环境可使用独立 Cerebro 容器查看 ES，不需要公开 `9200`。部署脚本从 Cerebro
官方 GitHub Release 获取固定的 `0.9.4` 发布包，基于现有 Atlas Java 镜像构建，并通过
`atlas-enterprise-agent_default` 网络连接 `http://elasticsearch-dev:9200`：

```bash
cd /opt/atlas-agent
ATLAS_PUBLIC_HOST=10.210.0.62 bash scripts/deploy-cerebro.sh
```

- 访问地址：`http://10.210.0.62:8302/`
- 容器：`atlas-cerebro`
- 登录凭据：仅保存在远端 `/opt/atlas-agent/.cerebro.env`，文件权限为 `600`
- 内存限制：`512 MB`，JVM 堆上限 `256 MB`
- 发布包 SHA-256：`c17f4abaaa7eb7d32c71ba17effc9995f3a96ee7cf10f4bfc929537df6430710`

Cerebro 是 ES 管理工具而非只读浏览器；当前开发 ES 未启用安全认证，因此页面内的删除索引、
关闭索引、修改设置等操作会真实生效。验证环境中只使用查询、概览和 REST 读取能力。

### 百炼模型配置

V1 使用阿里云百炼 OpenAI 兼容接口。API Key 只放在部署主机 `.env` 中，不通过页面保存：

```bash
ATLAS_LLM_API_KEY=替换为真实百炼APIKey
```

写入后重建服务容器的环境变量，再在“搜索与模型”页面对已校验的模型版本执行“连接测试”；
测试通过后才能发布。当前默认地址为
`https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions`，模型名为
`qwen3.8-max`。发布仅影响之后创建的新任务，已有任务继续使用创建时冻结的配置。

模型只负责对话意图理解和未确认舆情证据的辅助研判；确定性评分、证据确认/驳回和最终报告
仍由既有规则与运营人员控制。模型不可用时，对话意图自动回退到确定性解析，评分链路不受影响。

### 报告前企业信息刷新

Atlas 可在主体确认后、冻结任务快照前直接调用企业信息更新接口。该能力默认关闭；凭据只放部署
主机 `.env`，不得写进源码或页面：

```bash
ATLAS_COMPANY_REFRESH_ENABLED=true
ATLAS_COMPANY_REFRESH_BASE_URL=https://openapi.xiaolanben.com/api/
ATLAS_COMPANY_REFRESH_ACCESS_ID=替换为受控AccessId
ATLAS_COMPANY_REFRESH_ACCESS_TOKEN=替换为受控AccessToken
```

刷新覆盖 24 类报告必需数据和 7 类可选资产数据。报告必需类别失败时任务停止；可选资产失败会
留痕但不阻断风险报告。对话中要求“更新报告”会新建一次研判，从刷新企业数据开始执行，不再复用
旧任务快照。

首次体验部署只上传两个 JSON 样本，没有复制约 5.29 GB 的离线企业目录。完整说明见
`design/Atlas部署与运维手册V1.md` 和 `design/Atlas远程Docker部署记录-20260803.md`。

## 当前交付状态

- 后端全量自动化测试：96 项通过，0 失败、0 错误、0 跳过；
- 北京简熹和食品纵向报告烟测：通过；
- 运营端生产构建、代码规范检查和 4 项路由/操作契约测试：通过；
- 真实 Chrome 运营全链路测试：1 项通过，下载 DOCX 已校验 ZIP 签名与文件大小；
- 任务工作台已闭环主体确认、证据处理、人工调分、运营确认、报告生成和实时状态同步；
- Agent 意图支持可选模型端口、严格结构校验和确定性规则降级；
- 搜索接入具备超时、限流、重试、熔断和低基数指标；Tavily 已配置并完成真实联网联调，查询采用
  企业全称精确短语，只有明确命中企业全称或信用代码的结果才进入证据，摘要最多保留 2,000 字；
- DOCX 上传具备压缩包结构、路径、展开大小和条目数防护，任务创建具备并发幂等保护；
- 已建立 5 个合成评分黄金样本及历史业务报告/人工终稿验收引用框架；历史报告仅用于质量
  对照，不作为新任务输入；
- H2 本地模式与 PostgreSQL Docker 模式均已配置；
- 10.210.0.62 Docker 体验环境已部署，PostgreSQL、后端和运营端均正常运行；
- ES 9.4.2 单节点百企业验证及后端 ES Adapter 接入通过，远端后端当前使用 ES 数据模式；
- 管理台已拆为 Skills、数据源、搜索与模型、规则评分、报告模板、运行监控、审计日志、验收评估八个独立入口，
  均读取 `/api/runtime/capabilities` 的真实脱敏状态；旧 `/capabilities` 地址保留兼容并进入 Skills；
- 运营端页面契约测试 9 项通过，13 个路由生产构建成功；
- 运营端现采用明亮运营控制台：浅色分组侧栏承载业务与七类平台管理入口，顶部栏只保留页面
  上下文、运行状态和主题切换；卡片、台账和对话区以清晰度和长时间使用为优先。冰川运营、
  墨玉政企两套配色模式继续保留；
- 联网模型、生产 ES 安全与容量、统一身份和 20～50 份真实黄金样本仍需取得生产参数或
  业务资料后验收；数据库、报告文件和服务器配置的隔离备份恢复演练已通过，Docker 侧仍需
  补正式版本的应用回滚演练。

技术结果与待业务确认项见 `design/AtlasV1技术验收记录.md`。
