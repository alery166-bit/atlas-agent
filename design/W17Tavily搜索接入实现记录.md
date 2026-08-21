# W17 Tavily 搜索接入实现记录

更新时间：2026-08-04

## 结论

Atlas V1 的公开网络搜索主服务采用 Tavily。后端适配、Docker 配置、自动化契约测试和真实联网
联调均已完成；服务已在 `10.210.0.62` 启用，运行能力状态为 `READY`。

## 接入约定

- 接口：`POST https://api.tavily.com/search`
- 鉴权：`Authorization: Bearer <TAVILY_API_KEY>`
- 默认搜索深度：`basic`
- 默认主题：`general`
- 单次最大结果数：10
- 查询主体锚点由已确认的企业全称、曾用名、简称、品牌、门店、网站名和自媒体名组成；多个名称
  使用带双引号的 `OR` 查询，统一社会信用代码仅用于归属核验，不作为新闻检索的主要查询词
- 只有标题或摘要明确命中上述任一已确认身份词的页面进入证据；每条证据记录实际命中的名称和类型
- 系统不根据企业全称自动猜测简称、品牌或门店名；未知关系必须先补录并确认，避免把同名品牌舆情错误
  归到法人主体
- 搜索摘要最多保存 2,000 字，原始命中数量仍保留在搜索批次中用于审计
- `include_answer=false`：不把 Tavily 自动答案直接写入风险证据
- `include_raw_content=false`：V1 只保存搜索摘要和引用地址，控制成本与存储量
- `auto_parameters=false`：参数由 Atlas 明确管理，保证结果和计费行为可解释

搜索结果映射为 Atlas 证据候选：标题、URL、摘要、发布日期和来源元数据进入现有证据归一化流程；
`request_id`、`response_time`、`score` 和 `favicon` 作为追踪元数据保留。

## 配置

部署环境需要设置：

```dotenv
ATLAS_SEARCH_PRIMARY_ENABLED=true
TAVILY_API_KEY=tvly-...
ATLAS_SEARCH_PRIMARY_SEARCH_DEPTH=basic
ATLAS_SEARCH_PRIMARY_TOPIC=general
ATLAS_SEARCH_PRIMARY_MAX_RESULTS=10
```

不要把真实密钥写入代码、Compose 文件或版本化文档。体验环境通过 `/opt/atlas-agent/.env` 注入。

## 故障策略

沿用现有搜索保护机制：连接与请求超时、限流、指数退避重试、熔断和低基数监控指标。
依据当前业务约定，当唯一真实搜索源最终失败时，任务停止，不用模拟结果继续生成报告。

## 验证结果

- Tavily POST 请求体与 Bearer 鉴权契约：通过
- `results[]` 到证据候选及追踪元数据映射：通过
- 不支持的 `search_depth` 参数启动校验：通过
- 搜索相关模块及上游评分/证据测试：25 项通过
- 后端全量回归：70 项通过，0 失败、0 错误、0 跳过
- 体验环境部署：后端健康检查 `UP`，ES、Tavily、评分与报告能力均为 `READY`
- 首轮真实搜索：3 组查询成功，返回 26 条，约 6 秒；结果均为主体不明确的同城/同行业页面
- 质量修正后验证：3 组查询成功，返回 30 条，约 1.65 秒；原始数量完整留痕，0 条主体不明确页面
  进入证据，任务正常进入 `CALCULATING_RISK`
- 主体关系修正：撤销“仅全称/信用代码”的过严口径，新增多身份词查询和归属；自动化样本已验证一条
  只出现品牌名、不出现法人全称的闭店舆情能够进入证据，同时无身份锚点的噪声仍被剔除；全量后端
  70 项回归通过，并已重新部署至 `10.210.0.62`

## 后续质量验收

1. 用 20～50 份真实历史企业样本统计有效证据命中率、噪声率和人工采纳率；
2. 验证 Tavily 超时、限流时的任务停止和重试行为；
3. 持续记录单企业请求次数、返回条数、耗时和 credits 消耗；
4. 根据真实样本决定是否增加 `news` 主题补充查询或调整搜索深度。
5. 企业身份词维护入口和 `company_alias` 持久化闭环已在 W18 完成；北京简熹和食品有限公司当前
   样本尚无此类关系数据，实际使用前仍需运营人员提供真实品牌/门店关系。

## 官方依据

- Tavily Search API：<https://docs.tavily.com/documentation/api-reference/endpoint/search>
- Tavily API 认证：<https://docs.tavily.com/documentation/api-reference/introduction>
