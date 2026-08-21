package com.atlas.enterprise.risk.application;

import java.util.List;
import org.springframework.stereotype.Component;

/** Read-only audit catalogue connecting the received legacy code to the V1 runtime. */
@Component
public class LegacyRiskTraceabilityCatalog {
    public Traceability traceability() {
        return new Traceability(
            "FEATURE_COMPLETE_RECALCULATE_OTHERWISE_MATERIALIZED_FALLBACK",
            "特征完整时执行迁移规则重算；特征不完整时沿用ES物化旧模型分，再应用已确认事件最低分。",
            factScoringCatalog(),
            dictionary(),
            activeHardCodedLabels(),
            featureRequirements(),
            rules()
        );
    }

    private static List<FactScoringItem> factScoringCatalog() {
        return List.of(
            fact("股权冻结", "EQUITY_FREEZE", "ES结构化司法事件", "企业主体匹配且存在有效股权冻结记录", "风险事件窗口，默认365天", "完整特征重算时进入旧模型基础权重；否则由ES物化旧分承接", "已接入，评分依赖完整特征"),
            fact("失信人", "DISHONEST", "ES结构化司法事件", "企业主体命中失信被执行记录", "按有效记录与旧标签口径", "进入旧标签优先级；特征不完整时不凭标签臆造新分", "已接入，沿用旧评分边界"),
            fact("被执行人", "JUDGMENT_DEBTOR", "ES结构化司法事件", "企业主体命中被执行记录", "按有效记录与旧标签口径", "进入旧标签优先级；特征不完整时由物化旧分承接", "已接入，沿用旧评分边界"),
            fact("限制高消费", "LIMIT_CONSUMPTION", "ES结构化司法事件", "企业主体命中限制消费记录", "按有效记录与旧标签口径", "进入旧标签优先级；不由联网模型直接改分", "已接入，沿用旧评分边界"),
            fact("经营异常", "BUSINESS_ABNORMAL", "ES结构化经营异常", "企业主体匹配且存在有效异常名录记录", "风险事件窗口，默认365天", "完整特征重算时进入基础权重与标签优先级", "已接入，评分依赖完整特征"),
            fact("行政处罚", "ADMINISTRATIVE_PENALTY", "ES结构化监管事件", "企业主体匹配且存在有效行政处罚记录", "风险事件窗口，默认365天", "完整特征重算时进入旧模型基础权重；当前未设置独立最低分", "已接入，未设置事件保底"),
            fact("环保处罚", "ENVIRONMENTAL_PENALTY", "ES结构化监管事件", "企业主体匹配且存在环保处罚记录", "报告展示真实事件时间", "当前旧模型未设置独立基础权重或最低分", "已接入展示，不独立计分"),
            fact("失联", "OUT_OF_CONTACT", "结构化事实或公开证据", "主体明确、来源可引用并经Agent高置信确认或人工确认", "风险事件窗口，默认365天", "已确认事件最低6分", "已生效"),
            fact("拖欠工资", "WAGE_ARREARS", "公开网页、投诉平台或结构化事实", "正文明确指向企业自身欠薪，且证据已确认", "风险事件窗口，默认365天", "已确认事件最低6分", "已生效"),
            fact("门店关闭", "STORE_CLOSURE", "公开网页、投诉平台或结构化事实", "正文明确为企业经营性闭店，且证据已确认", "风险事件窗口，默认365天", "已确认事件最低8分", "已生效"),
            fact("网络投诉", "COMPLAINT_LEAD", "黑猫投诉、啄木鸟消费投诉、消费保等", "先聚合正文，再判断主体、事实类型和证据强度", "保留发布时间，缺失时明确标记", "投诉本身不直接计分；只有形成已确认风险事实后进入对应规则", "证据来源已纳入，非独立评分项")
        );
    }

    private static FactScoringItem fact(
        String riskName,
        String riskType,
        String primarySource,
        String recognitionCondition,
        String timeWindow,
        String scoreHandling,
        String runtimeState
    ) {
        return new FactScoringItem(
            riskName, riskType, primarySource, recognitionCondition,
            timeWindow, scoreHandling, runtimeState
        );
    }

    private static List<DictionaryItem> dictionary() {
        return List.of(
            item("LAW_INFO", "102101105", "严重违法", "SERIOUS_ILLEGAL", "基础权重/标签优先级"),
            item("PUNISH", "102101102", "行政处罚", "ADMINISTRATIVE_PENALTY", "基础权重/标签优先级"),
            item("EXECUTOR", "101102102", "被执行人", "JUDGMENT_DEBTOR", "标签优先级"),
            item("DOCUMENT", "101101101", "被起诉", "JUDICIAL_DOCUMENT", "司法基础权重"),
            item("DISHONEST", "101102101", "失信人", "DISHONEST", "标签优先级"),
            item("ABNORMAL", "102101101", "经营异常", "BUSINESS_ABNORMAL", "基础权重/标签优先级"),
            item("LIQUIDATION", "102102103", "清算", "LIQUIDATION", "标签字典"),
            item("BANKRUPTCY", "102102104", "破产", "BANKRUPTCY", "标签字典"),
            item("GQ_PLEDGE", "102103101", "股权出质", "EQUITY_PLEDGE", "基础权重"),
            item("GQ_FREEZE", "102103103", "股权冻结", "EQUITY_FREEZE", "基础权重"),
            item("GQ_ZHIYA", "102103102", "股权质押", "STOCK_PLEDGE", "基础权重"),
            item("ENVIRONMENTAL", "102101103", "环保处罚", "ENVIRONMENTAL_PENALTY", "标签字典"),
            item("JUDICIAL_SALE", "101101102", "司法拍卖", "JUDICIAL_AUCTION", "标签字典"),
            item("CONSUMPTION", "101102103", "限制高消费", "LIMIT_CONSUMPTION", "标签优先级"),
            item("TAXATION", "102101104", "税收违法", "TAX_ILLEGAL", "标签字典"),
            item("CANCEL_LICENSE", "102102105", "简易注销", "SIMPLE_CANCELLATION", "标签优先级"),
            item("SIMPLE_CANCEL_LICENSE", "102102102", "注销", "CANCELLATION", "标签优先级"),
            item("REVOKE_LICENSE", "102102101", "吊销", "REVOKED", "标签字典"),
            item("GONG_AN", "104101101", "公安立案", "PUBLIC_SECURITY_CASE", "标签优先级"),
            item("GONG_AN_RELATION", "104102101", "关联公安立案", "RELATED_PUBLIC_SECURITY_CASE", "标签优先级"),
            item("BAO_LEI", "103102101", "暴雷", "BUSINESS_CRISIS", "标签字典"),
            item("BAO_LEI_RELATION", "103102102", "关联暴雷", "RELATED_BUSINESS_CRISIS", "标签字典"),
            item("LICENSE_CANCEL", "103110101", "牌照注销", "LICENSE_CANCELLATION", "标签优先级"),
            item("OFFLINE_TOUSU", "103112110", "12345投诉", "OFFLINE_COMPLAINT", "基础权重"),
            item("SITE_STATE", "102105101", "官网异常", "WEBSITE_ABNORMAL", "标签优先级"),
            item("ADDR_REMOTE", "102104101", "异地经营", "REMOTE_OPERATION", "标签字典"),
            item("ILLEGAL_FUNDS", "103104104", "非法集资", "ILLEGAL_FUNDRAISING", "标签字典"),
            item("ILLEGAL_FUNDS_SUSPECTED", "103104101", "(疑似)非法集资", "SUSPECTED_ILLEGAL_FUNDRAISING", "标签优先级"),
            item("BACKPAY", "103112113", "拖欠工资", "WAGE_ARREARS", "事件最低分"),
            item("ZHAIWU_WEIYUE", "103112104", "债务违约", "DEBT_DEFAULT", "标签优先级"),
            item("PYRAMID_SELLING", "103105102", "传销", "PYRAMID_SELLING", "标签字典"),
            item("PYRAMID_SELLING_SUSPECTED", "103105101", "(疑似)传销", "SUSPECTED_PYRAMID_SELLING", "标签优先级"),
            item("SWINDLE", "103106102", "诈骗", "FRAUD", "标签字典"),
            item("SWINDLE_SUSPECTED", "103106101", "(疑似)诈骗", "SUSPECTED_FRAUD", "标签优先级"),
            item("CLEAR_AWAY", "103104103", "清退", "CLEARANCE", "标签字典"),
            item("SPECIAL_SWINDLE", "103112108", "杀猪盘", "ROMANCE_SCAM", "标签优先级"),
            item("GAMBLING", "103112102", "博彩", "GAMBLING", "标签优先级"),
            item("MONEY_LAUNDER", "103112101", "洗钱", "MONEY_LAUNDERING", "标签优先级"),
            item("OUT_OF_CANTACT", "103112107", "失联", "OUT_OF_CONTACT", "标签优先级/事件最低分")
        );
    }

    private static DictionaryItem item(
        String legacyName,
        String labelNo,
        String labelName,
        String canonicalType,
        String runtimeHandling
    ) {
        return new DictionaryItem(
            legacyName,
            labelNo,
            labelName,
            canonicalType,
            "已迁移",
            runtimeHandling,
            "RiskEnum.java → RiskType.java"
        );
    }

    private static List<RuleItem> rules() {
        return List.of(
            rule("LEGACY_DICTIONARY_39", "RiskEnum 39项风险字典", "RiskType别名及标签编号映射", "已迁移", false, "所有任务", "39项逐项可追溯；门店关闭是V1新增类型。"),
            rule("LEGACY_BASE_WEIGHTS", "RiskScoreService.getBaseRiskScore", "LegacyPureScoreCalculator.baseScore", "已迁移", true, "仅完整特征快照", "舆情、投诉、司法、异常、违法、处罚、出质/质押、冻结权重已迁移。"),
            rule("LEGACY_EVENT_WINDOW", "近一年有效记录过滤", "LegacyRiskFeatureExtractor + risk_event_days", "已迁移", true, "仅完整特征快照", "默认365天。"),
            rule("LEGACY_EVENT_DATE_FILTER", "异常/违法/处罚日期过滤", "按真实有效日期统计", "已纠错迁移", false, "仅完整特征快照", "旧方法getModuleDataNotNull实际只统计空日期，与方法名、近一年口径和其他事件过滤相反，判定为实现缺陷，不复制错误行为。"),
            rule("LEGACY_INDUSTRY_BASE", "RiskScoreService.getIndustryScore", "LegacyPureScoreCalculator.industryScore", "已迁移", false, "仅完整特征快照", "行业ID分档7/6.4/3.5/2/1保持一致。"),
            rule("LEGACY_LABEL_PRIORITY", "标准/朝阳/西安标签优先级", "LegacyPureScoreCalculator.riskLabelScore", "已迁移", true, "仅完整特征快照", "沿用旧标签编号和地区分支；RiskEnum外16个活动编号已按RiskScoreService硬编码分支单独登记。"),
            rule("LEGACY_COMPANY_CHANGE", "近180天股东/住所变化", "LegacyRiskFeatureExtractor", "已迁移", true, "仅完整特征快照", "新实现额外识别“地址”表述。"),
            rule("LEGACY_FACT_MULTIPLIERS", "实缴资本/成立年限/上市/工商变化倍率", "LegacyPureScoreCalculator", "已迁移", false, "仅完整特征快照", "分段倍率与旧代码一致。"),
            rule("LEGACY_RELATED_BLEND", "关联股东/投资企业0.2/0.5/0.3加权", "LegacyPureScoreCalculator", "缺数据依赖", false, "需要上游关联企业分", "计算公式已迁移；旧口径只取持股比例大于20%、排除白名单后的股东和投资企业最高分，当前缺少完整关系数据与白名单。"),
            rule("LEGACY_OVERFLOW", "超过10分循环乘0.95并HALF_DOWN", "LegacyPureScoreCalculator", "已迁移", false, "仅完整特征快照", "随后统一限制在0到10分。"),
            rule("LEGACY_BRANCH_HEAD", "分支/总部风险分继承", "V1不执行跨企业写分", "V1不恢复", false, "不执行", "旧实现只对朝阳地区分支与总部相互抬高分数并批量写回其他企业；与V1单企业旧报告更新边界冲突。"),
            rule("LEGACY_CY_REDUCE", "朝阳12345减分cyReduceScore", "尚无对应执行逻辑", "缺失", false, "不执行", "未提供依赖服务和完整业务口径。"),
            rule("LEGACY_USED_NAME_INTERVENTION", "曾用名及人工干预覆盖", "原始分与人工分分离保存", "V1审计修正", false, "人工确认阶段", "产品口径已明确保留原始分和人工分；曾用名只作为主体关系与证据来源，不覆盖机器原始分。"),
            rule("LEGACY_LABEL_BUILD", "updateRiskInfo动态构建风险标签", "读取ES快照物化标签", "缺数据依赖", false, "当前DEV样本", "基础事件标签可从ES读取；牌照、官网、关联黑名单、异地经营、风险名单和曾用名继承仍缺完整上游来源，因此完整特征标志保持false。"),
            rule("V1_EVENT_FLOORS", "旧代码无此统一规则", "失联6/欠薪6/闭店8", "V1新增", true, "已确认事件且有证据", "业务已明确确认。")
        );
    }

    private static List<ActiveLabelItem> activeHardCodedLabels() {
        return List.of(
            active("103112120", "经营风险", "9", "标准/朝阳/西安"),
            active("103109101", "聚众风险", "8.5", "标准/朝阳/西安"),
            active("103108101", "非法催收", "8.5", "标准/朝阳/西安"),
            active("103104102", "兑付异常", "8", "标准/朝阳/西安"),
            active("103107101", "高利贷（非P2P）", "7.5", "标准/朝阳/西安"),
            active("103107102", "套路贷", "7.5", "标准/朝阳/西安"),
            active("103107103", "校园贷", "7.5", "标准/朝阳/西安"),
            active("103112112", "培训贷", "7.5", "标准/朝阳/西安"),
            active("103107104", "非法放贷", "7.5", "标准/朝阳/西安"),
            active("103112111", "贷款诈骗", "7.5", "标准/朝阳/西安"),
            active("103112122", "提现退款问题", "7", "标准/朝阳/西安"),
            active("103101101", "虚假宣传", "4", "标准/朝阳/西安"),
            active("103101102", "无牌经营", "4", "标准/朝阳/西安"),
            active("103112103", "诱导消费", "4", "标准/朝阳/西安"),
            active("103110102", "违规处罚", "4", "标准/朝阳/西安"),
            active("103112109", "亏损", "4", "标准/朝阳/西安")
        );
    }

    private static ActiveLabelItem active(
        String labelNo,
        String labelName,
        String priorityScore,
        String profiles
    ) {
        return new ActiveLabelItem(
            labelNo,
            labelName,
            priorityScore,
            profiles,
            "RiskScoreService.setRiskLabel/setRiskLabelCy",
            "活动硬编码标签，参与标签优先级；未进入RiskEnum 39项，不自动推断新的标准风险类型。"
        );
    }

    private static List<FeatureRequirement> featureRequirements() {
        return List.of(
            feature("负面舆情与投诉计数", "旧舆情索引及12345关键词计数", "Tavily证据与快照扩展字段", "口径未等价", true, "搜索结果不能直接冒充旧索引年度计数和关键词计数。"),
            feature("司法/异常/违法/处罚事件", "riskMap各模块近一年记录", "ES标准事件索引", "已接入", true, "按有效事件日期统计。"),
            feature("股权出质/冻结/质押事件", "riskMap近一年记录", "ES标准事件索引", "已接入", true, "冻结按标准事件日期统计，不沿用案号年份近似。"),
            feature("行业编号", "industryIds", "ES企业索引industry.industry_id", "已接入", true, "缺值时不能声称完整重算。"),
            feature("风险标签", "updateRiskInfo动态标签", "ES risk_projection.legacy_labels", "部分接入", true, "动态标签上游未完整迁移。"),
            feature("工商变化", "近180天法人/股东/住所变更", "ES标准事件索引", "已接入", true, "识别法人、负责人、股东、投资人、住所和地址。"),
            feature("资本/成立年限/上市情况", "企业工商与上市字段", "ES企业索引", "部分接入", true, "实缴资本和成立日期已接入；上市信息依赖extensions.listing_info。"),
            feature("地区评分分支", "朝阳/西安地区判断", "extensions.legacy_scoring_profile", "部分接入", true, "默认STANDARD；没有明确旧地区口径时不推断CHAOYANG或XIAN。"),
            feature("关联股东/投资企业最高分", ">20%关系、白名单、关联企业旧分", "尚无完整上游", "缺失", true, "缺关系范围、白名单和关联企业分。"),
            feature("ES物化旧模型分", "旧系统riskScore", "risk_projection.legacy_score", "已接入回退", false, "特征不完整时作为安全基线；空值时按0分并明确告警。")
        );
    }

    private static FeatureRequirement feature(
        String featureName,
        String legacySource,
        String atlasSource,
        String readiness,
        boolean requiredForFullRecalculation,
        String note
    ) {
        return new FeatureRequirement(
            featureName,
            legacySource,
            atlasSource,
            readiness,
            requiredForFullRecalculation,
            note
        );
    }

    private static RuleItem rule(
        String ruleCode,
        String legacySource,
        String newImplementation,
        String migrationStatus,
        boolean configurable,
        String runtimeCondition,
        String note
    ) {
        return new RuleItem(
            ruleCode,
            legacySource,
            newImplementation,
            migrationStatus,
            configurable,
            runtimeCondition,
            note
        );
    }

    public record Traceability(
        String currentRuntimeMode,
        String currentRuntimeDescription,
        List<FactScoringItem> factScoringCatalog,
        List<DictionaryItem> riskDictionary,
        List<ActiveLabelItem> activeHardCodedLabels,
        List<FeatureRequirement> featureRequirements,
        List<RuleItem> calculationRules
    ) {}

    public record FactScoringItem(
        String riskName,
        String riskType,
        String primarySource,
        String recognitionCondition,
        String timeWindow,
        String scoreHandling,
        String runtimeState
    ) {}

    public record DictionaryItem(
        String legacyName,
        String legacyLabelNo,
        String labelName,
        String canonicalType,
        String migrationStatus,
        String runtimeHandling,
        String sourceEvidence
    ) {}

    public record RuleItem(
        String ruleCode,
        String legacySource,
        String newImplementation,
        String migrationStatus,
        boolean configurable,
        String runtimeCondition,
        String note
    ) {}

    public record ActiveLabelItem(
        String legacyLabelNo,
        String labelName,
        String priorityScore,
        String scoringProfiles,
        String sourceEvidence,
        String note
    ) {}

    public record FeatureRequirement(
        String featureName,
        String legacySource,
        String atlasSource,
        String readiness,
        boolean requiredForFullRecalculation,
        String note
    ) {}
}
