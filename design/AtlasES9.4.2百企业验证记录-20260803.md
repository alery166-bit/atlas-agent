# Atlas Elasticsearch 9.4.2 百企业验证记录

验证日期：2026-08-03

## 1. 验证结论

Atlas ES V1 的开发验证环境已在 `10.210.0.62` 部署完成，100 家企业验证集通过模板安装、严格 mapping 导入、数量核对、精确查询和 routing 查询。后端随后已切换到 ES 数据模式，并完成三家企业的真实 API 查询与数据快照验证。该结果证明 ES 9.4.2 可以承载当前 V1 数据结构和小规模查询，但不代替生产容量、安全和高可用验收。

## 2. 环境与边界

| 项目 | 结果 |
|---|---|
| Elasticsearch | `9.4.2`，单节点 |
| 集群名 | `atlas-dev-es` |
| Docker 镜像 | `atlas/elasticsearch-dev:9.4.2` |
| 程序来源 | Elastic 官方 Linux x86_64 发行包 |
| 完整性 | 官方 SHA-512 校验通过 |
| 基础系统层 | 复用服务器已有 `atlas-enterprise-agent-service:latest` |
| HTTP 端口 | 仅绑定宿主机 `127.0.0.1:9200` |
| JVM 堆 | 2 GiB |
| 容器上限 | 4 GiB |
| 分片 | 每个业务索引 1 主分片、0 副本 |
| 安全 | 仅开发验证期关闭 X-Pack Security；未对外暴露 |
| Kibana | 未部署 |

服务器访问 Elastic Docker Registry 的链路速度过低，因此验证镜像改由 Elastic 官方 Artifact CDN 下载同版本发行包构建。构建过程校验官方 SHA-512，未使用第三方镜像或软件包。

## 3. 验证数据

验证集固定为 100 家，不导入完整企业库：

- 保留离线实验中 8 家高覆盖企业及其事件、舆情和联系方式；
- 固定纳入 `乾道投资控股集团有限公司.json` 和 `北京童程童慧科技有限公司.json`；
- 其余企业从 `company_base.csv` 顺序、去重、流式抽样；
- 100 个 `atlas_company_id` 唯一，100 个信用代码唯一；
- 配套子文档只关联样本中的 8 家企业，孤立企业引用为 0。

生成结果：

- `work/es-dev-100/company.ndjson`
- `work/es-dev-100/manifest.json`
- `work/es-dev-100/validation-result.json`

## 4. 索引与数量

已安装 6 个 index template，并创建对应的 `*-v1-000001` 物理索引及 read/write alias。

| 读别名 | 业务文档数 | 结果 |
|---|---:|---|
| `atlas-company-read` | 100 | 通过 |
| `atlas-company-event-read` | 4,506 | 通过 |
| `atlas-public-intel-read` | 225 | 通过 |
| `atlas-company-contact-read` | 184 | 通过 |
| `atlas-company-relation-read` | 0 | V1 预留 |
| `atlas-company-asset-read` | 0 | V1 预留 |

事件索引 `_cat/indices` 显示的 Lucene 文档数为 5,858，其中包含 nested 内部文档；业务数量以 `_count` 返回的 4,506 为准。

## 5. 查询验证

- 使用 `atlas_company_id` 作为事件 routing 的查询命中 663 条；
- `北京简熹和食品有限公司` 精确查询命中 1 条；
- `北京童程童慧科技有限公司` 精确查询命中 1 条；
- `乾道投资控股集团有限公司` 精确查询命中 1 条；
- 导入前后集群状态均为 `green`。

## 6. 资源结果

最终稳定状态：

- ES 容器健康，CPU 约 `0.22%`；
- ES 容器内存约 `2.87 GiB / 4 GiB`；
- 服务器内存可用约 `8.0 GiB`；
- 根分区剩余约 `8.5 GiB`；
- 验证镜像显示大小约 `2.81 GB`，其中复用了已有 Atlas Java 基础层。

当前资源足够持续运行 100 家验证集。服务器无 swap，且根分区容量有限，不应在该机器上直接开始全量企业数据迁移或生产容量测试。

## 7. 可复现命令

从 `/opt/atlas-agent` 执行：

```bash
python3 scripts/es-dev-generate-sample.py --count 100
docker compose --profile es-dev build elasticsearch-dev
docker compose --profile es-dev up -d elasticsearch-dev
python3 scripts/es-dev-install.py --reset
docker compose --profile es-dev ps -a elasticsearch-dev-init elasticsearch-dev
```

`--reset` 只删除并重建六个 `atlas-*-v1-000001` 验证索引，不影响 PostgreSQL、报告文件或其他项目容器。不要执行 `docker compose down -v`。

## 8. 后端接入结果

- `atlas-adapter-es` 已实现，支持主体精确解析、企业主档、工商变更、风险事件、联系方式和
  已入库公开舆情聚合；
- 后端通过 `ATLAS_DATA_PROVIDER=es` 切换，Compose 内部地址为
  `http://elasticsearch-dev:9200`；
- 北京简熹和食品、乾道投资控股集团、北京童程童慧三家企业均通过真实 API 的主体解析与
  数据快照验证；
- 后端全量自动化测试为 65 项，0 失败、0 错误、0 跳过。

具体实现、任务 ID 和边界见 `AtlasESAdapter实现与验证记录-20260803.md`。

## 9. 尚未完成

- 真实主流搜索引擎和联网模型检索参数尚未提供；系统按既定规则将未配置检索判定为
  `SEARCH_PROVIDER_UNAVAILABLE` 并停止，不生成伪结果；
- 生产 ES 的认证、TLS、节点数、副本、快照、ILM、监控和容量策略尚未设计验收；
- 关系、资产索引只有 mapping 和空索引，尚未导入数据；
- 全量迁移、增量同步、双读核对和切换回滚继续后置，等待生产 ES 环境与业务接入决策。
