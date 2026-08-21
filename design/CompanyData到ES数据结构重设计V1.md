# CompanyData 到 ES 数据结构重设计 V1

版本：1.0  
日期：2026-07-29  
适用范围：Atlas V1「单企业旧风险报告更新」

## 1. 结论

`company_data` 不应按 MySQL 表原样迁入 ES，也不应重新构造一份包含所有一对多明细的企业巨型文档。

建议采用三层结构：

```text
MySQL / CSV 原始数据
  → Raw 原始层：字段和值原样留存，可重放
  → Canonical 标准层：统一企业身份、字段、日期、金额、事件类型
  → ES Serving 层：按查询场景生成 6 个可重建的检索投影
```

ES 最终只建立 6 个领域索引：

| 索引 | 文档粒度 | 主要内容 | V1 |
|---|---|---|---|
| `atlas-company-v1-*` | 一家企业一条 | 工商主档、经营概要、风险与数据新鲜度摘要 | 必须 |
| `atlas-company-event-v1-*` | 一个事件一条 | 工商变更、司法、处罚、经营异常、股权冻结等 | 必须 |
| `atlas-public-intel-v1-*` | 一条公开信息一条 | 内部新闻、搜索结果、投诉、模型检索线索 | 必须 |
| `atlas-company-contact-v1-*` | 一个联系方式一条 | 电话、邮箱、网站及有效性状态 | 必须 |
| `atlas-company-relation-v1-*` | 一条关系一条 | 股东、高管、投资、分支机构 | 百企业已导入 |
| `atlas-company-asset-v1-*` | 一项资产/资质一条 | 商标、专利、软著、证书、标准、荣誉等 | 百企业已导入 |

报告、人工改分、任务、证据原文及版本记录不以 ES 为权威存储：

- PostgreSQL：企业身份绑定、任务、快照、风险事实、评分版本、人工调整、报告版本；
- 对象存储：原网页、附件、截图、旧 DOCX、生成后的 DOCX；
- ES：面向检索和报告数据装配的可重建投影。

## 2. 本次数据盘点

### 2.1 数据规模

`company_data` 共 38 个 CSV 文件，约 4.93 GiB。早期按物理换行估算约 1415 万行，但部分引号字段含有合法换行，会放大该数字；2026-07-29 迁移实验已改用 RFC 4180 兼容解析器统计逻辑记录。抽样读取 38 个表、35,739 条逻辑记录，没有发现 CSV 行列错位。

下表中已被迁移实验覆盖的 V1 关键表使用逻辑记录数；其余表仍为物理行近似值，正式全量迁移前应统一用同一解析器复核。

高体量表如下：

| 表 | 约行数 | 约文件大小 | 结构判断 |
|---|---:|---:|---|
| `company_certificate` | 2,325,823 | 396 MiB | 一对多资产 |
| `company_contact` | 2,252,275 | 275 MiB | 一对多敏感联系方式 |
| `company_trademark` | 1,427,571 | 386 MiB | 一对多知识产权 |
| `company_judgement` | 1,113,572 | 277 MiB | 一对多司法事件 |
| `company_change` | 1,342,030 | 554 MiB | 一对多工商变更事件 |
| `company_patent` | 1,254,368 | 1.31 GiB | 一对多知识产权 |
| `company_software_copyright` | 666,100 | 127 MiB | 一对多知识产权 |
| `company_main_person` | 614,523 | 80 MiB | 一对多人员关系 |
| `company_shareholder` | 588,730 | 90 MiB | 一对多股东关系 |
| `company_base` | 421,380 | 1.16 GiB | 企业主档 |

这说明“所有信息嵌入企业文档”的方案会产生明显问题：

- 单个企业文档大小不受控；
- 子记录更新会重写整个企业文档；
- 大数组容易触发 nested 数量和文档限制；
- 股东、司法、专利等查询无法独立分页；
- 任何明细变化都会导致高写放大；
- 企业主档和历史明细无法使用不同生命周期。

### 2.2 字典、CSV 与旧 ES 的关系

目前存在三套字段口径：

1. `企业数据字典表.xlsx`：业务概念和较早版本字段；
2. `company_data/*.csv`：当前 MySQL 导出的物理字段；
3. 两份企业 JSON：旧索引 `sentiment_company_online_v1_bak` 的宽文档。

数据字典不能直接当作物理 mapping。例如：

| 业务含义 | 字典字段 | CSV 实际字段 |
|---|---|---|
| 企业名称 | `full_name` | `company_name` |
| 法定代表人 | `corporation` | `legal_personal` |
| 统一信用代码 | `social_identifier` | `credit_code` |
| 注册资本 | `capital` | `register_capital` |
| 实缴资本 | `payed_capital` | `paid_capital` |
| 经营状态 | `operate_status` | `registration_status` |
| 股东持股比例 | `proportion` | `rate` |
| 工商变更项目 | `change_project` | `change_item` |
| 证书到期日期 | `end_date` | `expire_date` |
| 裁判身份 | `case_identity` | `case_identify` |
| 立案日期 | `filing_date` | `filling_date` |

旧 ES 宽文档还存在以下问题：

- `snake_case`、`camelCase`、旧拼写同时存在；
- `isMonitor` 等字段在样本间出现字符串和数字混用；
- `payedCapital` 等字段存在数值、null、字符串等多种状态；
- 工商字段、风险分、人工干预、任务状态和报表字段混在同一文档；
- `riskLabel`、`reportRiskLabels`、`mayRiskLabel` 语义边界不清；
- 历史流程字段和拼写错误字段数量较多。

因此，新 ES 结构以 CSV 为来源事实，以数据字典为业务释义，以旧 ES 为兼容输入，不把旧 ES mapping 当作目标结构。

## 3. 设计原则

### 3.1 企业身份与来源 ID 分离

- `atlas_company_id`：平台生成、不可变 UUID，是所有标准层和新 ES 的企业主键；
- `source_company_id`：保留当前 `company_id`；
- `source_record_id`：保留每张 MySQL 表中的 `id`；
- 统一社会信用代码用于强匹配，但不直接作为内部主键；
- 企业改名、注销、合并不改变 `atlas_company_id`。

初次迁移如果身份服务尚未完成，可临时使用当前 `company_id` 作为路由键，但必须保留替换为 `atlas_company_id` 的迁移位。

### 3.2 ES 不承担关系数据库职责

- 不使用 ES join 类型；
- 不把无限增长的一对多记录嵌入企业主文档；
- 不在 ES 内保存报告版本和人工改分的唯一事实；
- ES 索引可以从标准层完全重建；
- 每个子文档冗余一个小型 `company` 引用，避免查询时跨索引 join。

### 3.3 类型先标准化，再入 ES

- 日期统一为 ISO-8601；无法解析的原值写入 `raw_value` 或错误表；
- 金额同时保留 `raw`、`value`、`currency`、`unit`；
- 持股比例统一为 0–100 的数值，同时保留原字符串；
- 地址坐标解析成 `geo_point`，原 `location` 字符串不直接写入该字段；
- JSON 字符串先解析，解析失败不能静默变成空数组；
- 布尔值只接受明确映射，禁止依赖非空字符串转 boolean；
- 未知扩展字段写入 `extensions`，禁止触发动态字段爆炸。

### 3.4 新旧评分隔离

`company_base.risk_score`、`risk_label`、`overall_rating` 是旧系统结果，进入：

```text
risk_projection.legacy_*
```

新模型结果进入：

```text
risk_projection.current_*
```

原始分、人工分、调整原因和评分规则版本的权威记录保存在 PostgreSQL；ES 只投影当前有效结果，且不得覆盖旧分。

## 4. 目标索引

所有物理索引带版本和滚动后缀，应用只访问别名：

模板中的 1/2/3 个主分片是按当前文件体量与早期物理行估算给出的开发期初值，不是生产容量承诺；生产值需以逻辑记录数、ES 节点数、目标单分片大小、日增量、查询并发和副本策略压测后确定。

| 读别名 | 写别名 | 物理索引示例 |
|---|---|---|
| `atlas-company-read` | `atlas-company-write` | `atlas-company-v1-000001` |
| `atlas-company-event-read` | `atlas-company-event-write` | `atlas-company-event-v1-000001` |
| `atlas-public-intel-read` | `atlas-public-intel-write` | `atlas-public-intel-v1-000001` |
| `atlas-company-contact-read` | `atlas-company-contact-write` | `atlas-company-contact-v1-000001` |
| `atlas-company-relation-read` | `atlas-company-relation-write` | `atlas-company-relation-v1-000001` |
| `atlas-company-asset-read` | `atlas-company-asset-write` | `atlas-company-asset-v1-000001` |

### 4.1 企业主档

`atlas-company-v1-*` 一家企业一条，只保存当前态和有限摘要：

```json
{
  "atlas_company_id": "uuid",
  "identity": {
    "source_company_id": "qc...",
    "credit_code": "9111...",
    "register_no": "...",
    "organization_code": "...",
    "taxpayer_no": "..."
  },
  "name": {
    "canonical": "北京某某有限公司",
    "short": "某某",
    "english": null,
    "aliases": ["原名称"]
  },
  "registration": {
    "status": "存续",
    "legal_representative": "张三",
    "open_date": "2020-01-01",
    "authority": "北京市市场监督管理局",
    "business_scope": "..."
  },
  "risk_projection": {
    "legacy_score": 6.0,
    "legacy_labels": ["..."],
    "current_original_score": 7.2,
    "current_manual_score": 8.0,
    "current_effective_score": 8.0,
    "current_band": "HIGH",
    "score_version": "risk-rule-2026-07",
    "scored_at": "2026-07-29T10:00:00+08:00"
  },
  "freshness": {
    "business_updated_at": "...",
    "event_updated_at": "...",
    "public_intel_updated_at": "..."
  }
}
```

主档不嵌入股东、人员、变更、司法、专利、新闻和全部联系方式。

### 4.2 企业事件

`atlas-company-event-v1-*` 将工商变更和风险事实统一为“一事件一文档”：

```text
event_group
  REGISTRATION  工商变更、注销、经营异常
  JUDICIAL      裁判、立案、执行、失信、限消、破产、拍卖
  REGULATORY    行政处罚、环保处罚、严重违法
  EQUITY        股权冻结、出质、质押
  TAX           税务违法
  OPERATION     后续标准化的失联、欠薪、闭店
```

通用字段包括企业引用、事件类型、发生/发布日期、状态、标题、摘要、机关、案号/文号、金额、当事人、来源和入库元数据。不同事件的特有字段进入受控对象：

- `change_detail`：变更项、变更前、变更后；
- `case_detail`：案号、法院、案由、当事人；
- `penalty_detail`：文号、违法事实、处罚决定；
- `equity_detail`：出质人、质权人、冻结期限等；
- `extensions`：暂未标准化但确有保留价值的字段。

V1 的“失联、欠薪、闭店”不依赖旧表字段硬推断。它们由公开信息或人工证据形成新的 `OPERATION` 事件，并保存证据引用和核验状态。

### 4.3 企业关系

`atlas-company-relation-v1-*` 保存：

- 股东；
- 主要人员；
- 核心人员；
- 对外投资；
- 分支机构。

每条关系独立分页和更新。持股金额、实缴金额及比例作为标准对象保存，原值同时保留。

### 4.4 企业资产

`atlas-company-asset-v1-*` 保存：

- 证书；
- 商标；
- 专利；
- 软件著作权；
- 企业标准；
- 荣誉；
- 产品/品牌；
- 融资；
- 纳税信用。

这些数据体量大、查询频率低于风险事件，独立索引可以单独设置分片、生命周期和导入节奏，不影响 V1 风险报告。

### 4.5 公开信息

`atlas-public-intel-v1-*` 同时容纳内部新闻和未来外部检索结果，但明确区分：

```text
INTERNAL_NEWS
WEB_SEARCH_RESULT
COMPLAINT
LLM_SEARCH_LEAD
MANUAL_UPLOAD
```

大模型搜索结果默认是 `LLM_SEARCH_LEAD`，只能作为线索。只有存在可访问原文并完成主体核验后，才能升级为正式证据或生成风险事件。

每条记录保存：

- 检索提供方、查询词、排名和采集时间；
- 标题、来源、URL、域名、发布日期、摘要；
- 内容哈希和原文对象存储 URI；
- 企业匹配状态及置信度；
- 证据等级和核验状态；
- 情感/风险分类结果及模型版本。

### 4.6 联系方式

`atlas-company-contact-v1-*` 单独设置权限，避免电话和邮箱进入普通全文索引。

- `_source` 可保留加密值或业务允许的原值；
- 检索使用不可逆 `value_hash`；
- 列表展示使用 `masked_value`；
- 联系状态、最后核验时间、失败次数用于后续“失联”研判；
- V1 不把“电话存在”或“电话为空”直接解释为企业失联。

## 5. 38 张表的路由

| 源表 | 目标索引 | 目标类型 | 处理 |
|---|---|---|---|
| `company_base` | company | 主档 | 当前态覆盖更新 |
| `company_contact` | contact | 电话/邮箱/网站 | 单独权限 |
| `company_shareholder` | relation | SHAREHOLDER | 一行一关系 |
| `company_main_person` | relation | MAIN_PERSON | 一行一关系 |
| `company_core_person` | relation | CORE_PERSON | 一行一关系 |
| `company_investment` | relation | OUTBOUND_INVESTMENT | 一行一关系 |
| `company_branch` | relation | BRANCH | 一行一关系 |
| `company_change` | event | REGISTRATION_CHANGE | 权威工商变更来源 |
| `company_change_log` | raw only | - | 与 change 重叠，默认不重复入 ES |
| `company_abnormal` | event | BUSINESS_ABNORMAL | 进入/移出同一事件 |
| `company_administrative_penalty` | event | ADMINISTRATIVE_PENALTY | 处罚事件 |
| `company_environmental_penalty` | event | ENVIRONMENTAL_PENALTY | 处罚事件 |
| `company_illegal` | event | SERIOUS_ILLEGAL | 进入/移出同一事件 |
| `company_tax_illegal` | event | TAX_ILLEGAL | 税务事件 |
| `company_dishonest` | event | DISHONEST | 司法事件 |
| `company_executor` | event | ENFORCEMENT | 司法事件 |
| `company_limit_consumption` | event | LIMIT_CONSUMPTION | 司法事件 |
| `company_filing` | event | CASE_FILING | 司法事件 |
| `company_judgement` | event | JUDGEMENT | 司法事件 |
| `company_bankruptcy` | event | BANKRUPTCY | 司法事件 |
| `company_auction` | event | JUDICIAL_AUCTION | 司法事件 |
| `company_equity_freeze` | event | EQUITY_FREEZE | 股权事件 |
| `company_equity_hostage` | event | EQUITY_HOSTAGE | 名称待业务确认，先不合并 |
| `company_equity_pledge` | event | EQUITY_PLEDGE | 名称待业务确认，先不合并 |
| `company_liquidation` | event | LIQUIDATION | 工商/经营事件 |
| `company_simple_cancellation` | event | SIMPLE_CANCELLATION | 工商事件 |
| `company_certificate` | asset | CERTIFICATE | 一行一资产 |
| `company_trademark` | asset | TRADEMARK | 一行一资产 |
| `company_patent` | asset | PATENT | 一行一资产 |
| `company_software_copyright` | asset | SOFTWARE_COPYRIGHT | 一行一资产 |
| `company_standard` | asset | STANDARD | 一行一资产 |
| `company_honor` | asset | HONOR | 一行一资产 |
| `company_business` | asset | PRODUCT_OR_BRAND | 一行一资产 |
| `company_financing` | asset | FINANCING | 一行一资产 |
| `company_tax_credit` | asset | TAX_CREDIT | 一行一资产 |
| `company_news` | public_intel | INTERNAL_NEWS | 不能默认等同负面舆情 |
| `company_cbd_monitor` | raw / PostgreSQL | 旧运营状态 | 不进入企业事实主档 |
| `company_patent_pct` | ignore | - | 当前为空表 |

## 6. 文档 ID、路由与更新

| 文档 | `_id` | `_routing` |
|---|---|---|
| 企业主档 | `atlas_company_id` | 不需要 |
| 事件 | `source_system:source_table:source_record_id` 的稳定哈希 | `atlas_company_id` |
| 关系 | 同上 | `atlas_company_id` |
| 资产 | 同上 | `atlas_company_id` |
| 公开信息 | 来源记录 ID；无 ID 时用规范化 URL + 内容哈希 | `atlas_company_id` |
| 联系方式 | 来源记录 ID | `atlas_company_id` |

迁移阶段保留 `source.table`、`source.record_id`、`source.company_id`。重复运行同一批次时用相同 `_id` upsert，不产生重复文档。

## 7. 同步链路

### 7.1 首次全量

```text
1. 为每张表登记快照批次和行数
2. 流式读取，不整表加载内存
3. 绑定 atlas_company_id
4. 解析日期、金额、JSON、枚举
5. 生成稳定文档 ID 和内容哈希
6. Bulk 写入新版本索引
7. 对账行数、企业覆盖率、孤儿记录和拒绝记录
8. 抽样比对报告字段
9. 别名原子切换
```

推荐 Bulk 单批 5–15 MiB，而不是固定追求行数；失败项逐条落错误队列，不允许整批静默跳过。

### 7.2 增量同步

优先级：

1. MySQL binlog/CDC；
2. `update_time + id` 复合水位；
3. 定期全量校准。

仅用 `update_time` 有两个缺陷：

- 同一时间戳多行可能漏读；
- 当前表没有统一删除标记，无法发现硬删除。

因此每个源表至少保存：

```text
source_table
last_update_time
last_id
last_full_reconcile_at
last_success_batch_id
```

硬删除在 CDC 未接入前通过周期性主键清单对账发现，ES 使用 delete 或 `ingest.deleted=true`。

## 8. 必须执行的数据质量门禁

### P0：阻断上线

- 同一来源 ID 绑定到多个企业；
- 信用代码冲突且未人工处理；
- 子表 `company_id` 无法绑定企业；
- 同一 `_id` 对应不同来源事实；
- 企业事件串入其他企业。

### P1：阻断报告正式生成

- 关键事件没有事件日期或发布日期；
- 处罚、司法事件缺少案号/文号且没有替代去重指纹；
- 来源查询失败却被处理为 0 条；
- 高风险事实无证据引用；
- 金额或日期解析失败后被错误填成 0。

### P2：允许入库但需告警

- 非关键展示字段缺失；
- 地址无法解析坐标；
- 资产状态枚举未识别；
- 数据字典字段当前物理表不存在。

每批次至少输出：

- 源行数、成功数、拒绝数、重复数；
- 企业绑定成功率、孤儿记录数；
- 日期/金额/JSON 解析失败率；
- 每表最大更新时间和数据新鲜度；
- 源表与 ES 文档数差异；
- 关键字段填充率与上一批次漂移。

## 9. 当前数据中需单独处理的问题

1. `company_change_log` 与 `company_change` 重叠。默认以 `company_change` 为当前权威来源，前者只留在 raw 层，确认差异后再决定是否补历史。
2. `company_patent_pct` 为空，不创建对应类型。
3. `company_contact` 约 225 万行，必须独立索引和权限，不能全部嵌入企业主档。
4. `company_base` 的 `former_names`、`risk_label`、`overall_rating` 等是 JSON 字符串，需要显式解析。
5. `location` 是源字符串，必须确认经纬顺序后再写入 `geo_point`。
6. `company_news` 缺少数据字典中的 `search_site`、`author`、`outline`、`search_words`，不能伪造。
7. 数据字典存在投诉表定义，但当前 `company_data` 没有对应数据文件。
8. 专利字典中的授权日期/公开号与当前 CSV 字段并不完全对应，暂不猜测映射。
9. `equity_hostage` 与 `equity_pledge` 的业务语义需要确认，迁移前保持两个事件类型。
10. 当前表缺少统一软删除字段，必须设计 CDC 或周期对账。

## 10. 对 V1 的最小落地范围

第一阶段只导入：

1. `company_base` → 企业主档；
2. 工商变更、经营异常、司法、处罚等 18 张表 → 企业事件；
3. `company_news` → 公开信息；
4. `company_contact` → 联系方式。

先不导入：

- 关系索引；
- 资产索引；
- `company_cbd_monitor`；
- 空的 `company_patent_pct`；
- 重复的 `company_change_log`。

这能直接满足：

- 核对最新工商信息；
- 读取原有结构化风险；
- 搜索和归档负面舆情；
- 研判失联、欠薪、闭店；
- 保存新旧评分投影；
- 生成 V1 DOCX 报告。

## 11. 验收标准

结构设计验收不以“能查到数据”作为唯一标准，至少满足：

- 同一企业所有文档使用同一 `atlas_company_id`；
- 新索引不存在动态字段和类型漂移；
- 事件、公开信息可以按企业和时间稳定分页；
- 企业主档更新不重写一对多明细；
- 原始分、人工分、有效分可同时追溯；
- 查询失败与 0 条结果严格区分；
- 任意 ES 文档可以追溯到源表、源记录和同步批次；
- 新索引可从 MySQL/标准层完整重建；
- 旧报告所需字段有明确来源或明确缺失状态；
- V1 只启用四个必要索引，未把低频数据迁移变成上线前置条件。

可执行 mapping 与表路由文件位于：

- `design/es-v1/index-templates/`
- `design/es-v1/table-routing.json`
- `design/es-v1/sample-documents.ndjson`
