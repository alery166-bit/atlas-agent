# Atlas 远程 Docker 部署记录

部署日期：2026-08-03

## 1. 部署结论

Atlas V1 已部署到 `10.210.0.62` 的 Docker 环境，当前后端使用 ES 百企业验证集。

| 项目 | 结果 |
|---|---|
| 服务器 | `10.210.0.62` / `hs-bot00` |
| 部署目录 | `/opt/atlas-agent` |
| 运营端 | `http://10.210.0.62:8300` |
| 后端 | `http://10.210.0.62:8301` |
| 健康检查 | `http://10.210.0.62:8301/actuator/health` |
| 数据库 | PostgreSQL 17，独立命名卷 |
| 数据模式 | 业务后端使用 ES 9.4.2 百企业验证集；离线 JSON/CSV 模式仍可配置回切 |

## 2. 容器

- `atlas-enterprise-agent-postgres-1`
- `atlas-enterprise-agent-service-1`
- `atlas-enterprise-agent-console-1`
- `atlas-enterprise-agent-elasticsearch-dev-1`（可选 `es-dev` profile）
- `atlas-enterprise-agent-elasticsearch-dev-init-1`（数据卷权限初始化，正常退出）

PostgreSQL 和后端健康检查均通过；运营端返回 HTTP 200。浏览器打开后显示“服务在线”，
五个导航入口正常加载，说明前端使用远程 `8301` API 而不是访问客户端本机地址。

后端日志确认：

- Docker Profile 生效；
- PostgreSQL 连接成功；
- Flyway V1～V8 全部迁移成功；
- Spring Boot 在容器内 `8080` 正常启动；
- Actuator 暴露 health、info 和 metrics。

同日完成第二次前后端更新：新增脱敏运行状态接口 `/api/runtime/capabilities`，能力与数据源页
据此展示当前 ES、搜索、模型、评分和报告配置；Chrome 复验页面状态正确，控制台不再出现
Vinext RSC 预取错误。

## 3. 端口与隔离

服务器原有 `8000`、`8080`、`8100`、`8200` 等服务保持运行。本次选择：

- `8300 -> console:3000`
- `8301 -> service:8080`
- `127.0.0.1:9200 -> elasticsearch-dev:9200`

Compose 项目名、网络、容器和三个持久卷均与现有项目隔离。数据库密码由远端部署脚本随机
生成，保存在权限为 `600` 的 `/opt/atlas-agent/.env`，未写入代码或部署记录。

## 4. 部署集

首次体验部署没有上传 `data/company` 下约 5.29 GB 的离线企业数据，仅包含：

- 服务端和运营端源码；
- Dockerfile 与 Compose；
- 北京简熹和食品正式 DOCX 模板；
- 乾道投资控股集团、北京童程童慧两个 JSON 样本。

这样可以先验证产品和工作流，同时避免占用服务器剩余磁盘。完整企业数据后续应通过生产
ES Provider 接入，不建议继续复制 MySQL 导出的 5 GB 文件。

后续新增独立的 100 家 ES 验证集，没有上传完整企业目录。验证集包含 100 家企业、4,506 条
事件、225 条舆情和 184 条联系方式；Atlas 后端已通过 Compose 内部网络切换到 ES，并完成
三家企业真实 API 查询验证。详细结果见 `AtlasES9.4.2百企业验证记录-20260803.md` 和
`AtlasESAdapter实现与验证记录-20260803.md`。

## 5. 运维命令

```bash
cd /opt/atlas-agent
docker compose ps
docker compose logs -f service console
docker compose up -d --no-build
bash scripts/deploy-docker.sh
docker compose --profile es-dev ps -a elasticsearch-dev-init elasticsearch-dev
python3 scripts/es-dev-install.py --reset
python3 scripts/es_adapter_smoke.py --output work/es-validation/es-adapter-smoke-20260803.json
```

停止体验环境时使用：

```bash
cd /opt/atlas-agent
docker compose stop
```

不要执行 `docker compose down -v`，否则会删除 PostgreSQL、报告和旧报告持久卷。

## 6. 已知限制

- 真实搜索和联网模型尚未接入；后端 ES Adapter 已接入开发验证环境；
- 当前没有统一身份，开发运营人默认为 `local-operator`；
- 搜索引擎和联网模型尚未配置，能力页会明确显示为“待配置”；
- 尚未执行生产级 TLS、备份恢复、故障注入和压力测试。
