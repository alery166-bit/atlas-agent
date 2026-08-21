# Atlas ES V1

开发验证镜像使用 Elastic 官方 `9.4.2` Linux 发行包构建，并校验官方 SHA-512；基础系统层复用现有 Atlas Java 镜像，以绕过服务器访问 Docker 镜像仓库时的低速链路。ES 版本、程序文件和许可仍来自 Elastic 官方发行物。

本目录是 `company_data` 从 MySQL/CSV 迁入新 ES 的可执行设计稿。

开发验证版本固定为 Elasticsearch `9.4.2`。验证环境使用单节点、单分片、零副本且不安装
Kibana；生产节点数、分片、副本、安全和存储策略仍需按生产环境重新评估。

## 文件

- `table-routing.json`：38 张源表的目标领域和类型；
- `index-templates/*.json`：6 个索引模板；
- `sample-documents.ndjson`：企业主档、事件、公开信息样例。

## 安装顺序

1. 根据实际 ES 版本复核模板 API；
2. 安装 6 个 index template；
3. 创建 `*-v1-000001` 物理索引；
4. 为每个物理索引创建 `*-read` 与 `*-write` 别名；
5. 小批量导入样本并执行质量校验；
6. 全量导入后原子切换读别名。

## 100 家开发验证

从任务根目录执行：

```bash
python3 scripts/es-dev-generate-sample.py --count 100
docker compose --profile es-dev up -d elasticsearch-dev
python3 scripts/es-dev-install.py --reset
```

验证集保留既有 8 家高覆盖企业的事件、公开信息和联系方式，再从 `company_base.csv` 流式
补充 92 家企业主档。生成文件写入 `work/es-dev-100/`，属于可再生成的中间产物。

`--reset` 只删除并重建六个 `atlas-*-v1-000001` 开发验证索引，不影响 PostgreSQL、报告
文件或其他项目。ES 只绑定宿主机 `127.0.0.1:9200`；Compose 网络内可以通过
`http://elasticsearch-dev:9200` 访问。

模板默认不依赖 IK 等第三方分词插件，便于 Docker 和生产环境保持一致。确认生产集群已安装且版本一致后，可单独增加中文 analyzer component template，不应直接修改业务字段结构。

所有模板使用 `dynamic: strict`。尚未标准化的字段必须进入 `extensions` 或 `source_payload`，不能靠动态 mapping 自动扩展。

子文档导入时使用 `atlas_company_id` 作为 `_routing`。查询子索引时也应携带 routing，避免跨全部分片扫描。
