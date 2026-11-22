package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 专项保障账户类型（个人医保账户 / 住房公积金账户）
 */
enum class DedicatedFundKind(
    val brandCode: String,
    val defaultName: String,
    val subTypeLabel: String,
    val defaultColorHex: Long,
    val defaultAliases: String,
    val recurringRuleTitle: String,
    val defaultIncomeCategory: String,
    val defaultTag: String
) {
    MEDICAL_INSURANCE(
        brandCode = "MEDICAL_CARD",
        defaultName = "个人医保账户",
        subTypeLabel = "医保账户",
        defaultColorHex = 0xFF14B8A6L,
        defaultAliases = "个人医保,医保账户,医保卡,医保个账,医疗保险,社保卡,医保电子凭证",
        recurringRuleTitle = "个人医保月度划入",
        defaultIncomeCategory = "其他收入",
        defaultTag = "医保"
    ),
    HOUSING_PROVIDENT(
        brandCode = "PROVIDENT",
        defaultName = "住房公积金",
        subTypeLabel = "住房公积金",
        defaultColorHex = 0xFF1677FFL,
        defaultAliases = "住房公积金,公积金,个人公积金,公积金账户,补充公积金",
        recurringRuleTitle = "住房公积金月度缴存",
        defaultIncomeCategory = "其他收入",
        defaultTag = "公积金"
    )
}

/**
 * 专项保障账户月度双边缴存参考配置（BackupSchemaGuard 全字段安全默认值）
 */
@Serializable
data class DedicatedFundPlanConfig(
    val enabled: Boolean = false,
    /** 个人月缴存/划入金额（分） */
    val employeeMonthlyCents: Long = 0L,
    /** 单位月划入/配缴金额（分） */
    val employerMonthlyCents: Long = 0L,
    /** 预计每月入账日（1..28，规避大小月月末越界） */
    val depositDayOfMonth: Int = 15,
    /** 关联的代发工资储蓄卡 ID（供工资条实发入账路由共用） */
    val linkedPayrollWalletId: Long? = null,
    /** 是否自动生成/维护月度固定入账周期规则 */
    val autoCreateMonthlyRule: Boolean = false,
    /** 已绑定的月度周期规则 ID */
    val linkedRecurringRuleId: Long? = null
) {
    val totalMonthlyDepositCents: Long
        get() = employeeMonthlyCents.coerceAtLeast(0L) + employerMonthlyCents.coerceAtLeast(0L)

    fun sanitized(): DedicatedFundPlanConfig = copy(
        employeeMonthlyCents = employeeMonthlyCents.coerceAtLeast(0L),
        employerMonthlyCents = employerMonthlyCents.coerceAtLeast(0L),
        depositDayOfMonth = depositDayOfMonth.coerceIn(1, 28),
        linkedPayrollWalletId = linkedPayrollWalletId?.takeIf { it > 0L },
        linkedRecurringRuleId = linkedRecurringRuleId?.takeIf { it > 0L }
    )
}

@Serializable
data class DedicatedFundSettings(
    val schemaVersion: Int = 1,
    val medicalPlan: DedicatedFundPlanConfig = DedicatedFundPlanConfig(),
    val providentPlan: DedicatedFundPlanConfig = DedicatedFundPlanConfig()
) {
    fun sanitized(): DedicatedFundSettings = copy(
        medicalPlan = medicalPlan.sanitized(),
        providentPlan = providentPlan.sanitized()
    )

    fun planOf(kind: DedicatedFundKind): DedicatedFundPlanConfig = when (kind) {
        DedicatedFundKind.MEDICAL_INSURANCE -> medicalPlan
        DedicatedFundKind.HOUSING_PROVIDENT -> providentPlan
    }
}

/**
 * 工资条识别草稿标准契约（预留供下一阶段工资条截图 OCR / Excel 薪资表导入直接调用）
 */
@Serializable
data class PayslipRecognitionDraft(
    /** 应发薪资总额（分） */
    val grossSalaryCents: Long = 0L,
    /** 个人所得税扣款（分） */
    val incomeTaxCents: Long = 0L,
    /** 养老与失业保险个人扣款（分） */
    val pensionAndUnemploymentDeductionCents: Long = 0L,
    /** 医疗保险个人扣款（分） */
    val personalMedicalDeductionCents: Long = 0L,
    /** 当月划入个人医保账户金额（分；若工资条未单列划入额，可由上游或配置回退） */
    val medicalPersonalAccountDepositCents: Long = 0L,
    /** 住房公积金个人扣款（分） */
    val personalProvidentDeductionCents: Long = 0L,
    /** 住房公积金单位配缴金额（分） */
    val employerProvidentMatchCents: Long = 0L,
    /** 实发到手工资（分） */
    val netTakeHomePayCents: Long = 0L,
    /** 发薪日时间戳（毫秒） */
    val paydayTimestampMillis: Long = System.currentTimeMillis(),
    /** 发薪单位名称（选填） */
    val employerName: String = ""
) {
    /** 双边公积金合计入账额 = 个人扣缴 + 单位配缴 */
    val bilateralProvidentDepositCents: Long
        get() = personalProvidentDeductionCents.coerceAtLeast(0L) + employerProvidentMatchCents.coerceAtLeast(0L)
}

/**
 * 工资条三路分账目标账户路由结果：
 * 1. 实发工资 -> 代发工资储蓄卡
 * 2. 医保划入 -> 个人医保账户
 * 3. 双边公积金 -> 住房公积金账户
 */
data class PayslipWalletRouting(
    val payrollDebitWallet: WalletEntity?,
    val personalMedicalWallet: WalletEntity?,
    val housingProvidentWallet: WalletEntity?,
    val netPayDepositCents: Long,
    val medicalAccountDepositCents: Long,
    val providentAccountDepositCents: Long
)

/**
 * 从账单 `note` 中解析出的结构化医保拆解金额（单位：分）
 */
data class StructuredMedicalNoteAmounts(
    val originalCostCents: Long = 0L,
    val poolSubsidyCents: Long = 0L,
    val personalAccountCents: Long = 0L,
    val unspecifiedMedicalCents: Long = 0L,
    val selfPayZeroExplicit: Boolean = false
) {
    val hasExplicitBreakdown: Boolean
        get() = poolSubsidyCents > 0L || personalAccountCents > 0L || unspecifiedMedicalCents > 0L
}

/**
 * 医疗与医保费用四维透视汇总结果
 */
data class MedicalExpenseInsights(
    /** 1. 医疗总费用（统筹报销 + 个账支出 + 现金自付退费后基数） */
    val totalMedicalCostCents: Long = 0L,
    /** 2. 医保统筹报销 */
    val poolReimbursedCents: Long = 0L,
    /** 3. 医保个账支出 */
    val personalAccountPaidCents: Long = 0L,
    /** 4. 个人现金自付（已扣除关联退款与已结算报销） */
    val cashOutOfPocketNetCents: Long = 0L,
    /** 涉及的独立医疗消费笔数（组合支付父子单合并计 1 笔） */
    val medicalBillCount: Int = 0
) {
    val hasData: Boolean
        get() = medicalBillCount > 0 && (
            totalMedicalCostCents > 0L ||
                poolReimbursedCents > 0L ||
                personalAccountPaidCents > 0L ||
                cashOutOfPocketNetCents > 0L
            )
}

/**
 * 个人医保账户 & 住房公积金专项保障账户管理中心
 */
object DedicatedFundManager {
    const val PREFS_NAME = "moji_dedicated_fund_prefs"
    private const val KEY_SETTINGS_JSON = "dedicated_fund_settings_json_v1"

    const val COMPOSITE_MEDICAL_PERSONAL_SUFFIX = "#MED_PERSONAL"
    const val COMPOSITE_CHILD_NOTE_PREFIX = "关联组合支付 · 医保个账扣款"

    const val PAYSLIP_ORDER_PREFIX = "PAYSLIP:"
    const val PAYSLIP_MEDICAL_CHILD_SUFFIX = "#PAYSLIP_MED"
    const val PAYSLIP_PROVIDENT_CHILD_SUFFIX = "#PAYSLIP_PROV"
    const val PAYSLIP_MEDICAL_CHILD_NOTE_PREFIX = "关联工资条 · 医保个账划入"
    const val PAYSLIP_PROVIDENT_CHILD_NOTE_PREFIX = "关联工资条 · 公积金双边缴存"

    private val MEDICAL_WALLET_KEYWORDS = listOf(
        "个人医保", "医保账户", "医保卡", "医保个账", "医疗保险", "社保卡", "医保电子凭证", "医保"
    )
    fun isMedicalPaymentMethodHint(raw: String): Boolean =
        raw.isNotBlank() && MEDICAL_WALLET_KEYWORDS.any { raw.contains(it, ignoreCase = true) }
    private val PROVIDENT_WALLET_KEYWORDS = listOf(
        "住房公积金", "补充公积金", "个人公积金", "公积金账户", "公积金"
    )

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settingsFlow = MutableStateFlow(DedicatedFundSettings())
    val settingsFlow: StateFlow<DedicatedFundSettings> = _settingsFlow.asStateFlow()

    val currentSettings: DedicatedFundSettings
        get() = _settingsFlow.value

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initWithPrefs(sp)
    }

    @Synchronized
    internal fun initWithPrefs(sp: SharedPreferences) {
        prefs = sp
        val raw = sp.getString(KEY_SETTINGS_JSON, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                jsonCodec.decodeFromString<DedicatedFundSettings>(raw).sanitized()
            }.getOrNull()?.let { decoded ->
                _settingsFlow.value = decoded
                return
            }
        }
        _settingsFlow.value = DedicatedFundSettings()
    }

    @Synchronized
    private fun persist(settings: DedicatedFundSettings): DedicatedFundSettings {
        val clean = settings.sanitized()
        _settingsFlow.value = clean
        prefs?.edit()?.putString(KEY_SETTINGS_JSON, jsonCodec.encodeToString(clean))?.apply()
        return clean
    }

    @Synchronized
    fun updatePlan(kind: DedicatedFundKind, plan: DedicatedFundPlanConfig): DedicatedFundSettings {
        val current = _settingsFlow.value
        val updated = when (kind) {
            DedicatedFundKind.MEDICAL_INSURANCE -> current.copy(medicalPlan = plan.sanitized())
            DedicatedFundKind.HOUSING_PROVIDENT -> current.copy(providentPlan = plan.sanitized())
        }
        return persist(updated)
    }

    fun exportConfigJson(): String {
        return jsonCodec.encodeToString(_settingsFlow.value.sanitized())
    }

    @Synchronized
    fun restoreFromBackupJson(rawJson: String): Boolean {
        if (rawJson.isBlank()) return false
        val parsed = runCatching {
            jsonCodec.decodeFromString<DedicatedFundSettings>(rawJson).sanitized()
        }.getOrNull() ?: return false
        persist(parsed)
        return true
    }

    @Synchronized
    fun resetToDefaults(): DedicatedFundSettings {
        return persist(DedicatedFundSettings())
    }

    /**
     * 删除账户时清理悬空引用（工资卡关联 ID，或最后一个同类专项保障账户对应的月度周期规则状态）。
     */
    @Synchronized
    fun clearReferencesForDeletedWallet(
        deletedWalletId: Long,
        deletedKind: DedicatedFundKind?,
        hasRemainingWalletOfSameKind: Boolean
    ): DedicatedFundSettings {
        val current = _settingsFlow.value
        fun sanitizePlan(kind: DedicatedFundKind, plan: DedicatedFundPlanConfig): DedicatedFundPlanConfig {
            val clearedPayroll = if (plan.linkedPayrollWalletId == deletedWalletId) null else plan.linkedPayrollWalletId
            return if (deletedKind == kind && !hasRemainingWalletOfSameKind) {
                plan.copy(
                    enabled = false,
                    autoCreateMonthlyRule = false,
                    linkedRecurringRuleId = null,
                    linkedPayrollWalletId = clearedPayroll
                )
            } else if (clearedPayroll != plan.linkedPayrollWalletId) {
                plan.copy(linkedPayrollWalletId = clearedPayroll)
            } else {
                plan
            }
        }
        val next = current.copy(
            medicalPlan = sanitizePlan(DedicatedFundKind.MEDICAL_INSURANCE, current.medicalPlan),
            providentPlan = sanitizePlan(DedicatedFundKind.HOUSING_PROVIDENT, current.providentPlan)
        )
        return if (next != current) persist(next) else current
    }

    @Synchronized
    internal fun resetForTesting() {
        prefs = null
        _settingsFlow.value = DedicatedFundSettings()
    }

    /**
     * 新建账户时，专项保障预设（`MEDICAL_CARD` / `PROVIDENT`）默认不计入日常可支配净资产（`false`）
     */
    fun defaultIncludeInNetWorthForBrand(brandCode: String): Boolean {
        return brandCode != DedicatedFundKind.MEDICAL_INSURANCE.brandCode &&
            brandCode != DedicatedFundKind.HOUSING_PROVIDENT.brandCode
    }

    fun defaultAliasesForBrand(brandCode: String): String {
        return when (brandCode) {
            DedicatedFundKind.MEDICAL_INSURANCE.brandCode -> DedicatedFundKind.MEDICAL_INSURANCE.defaultAliases
            DedicatedFundKind.HOUSING_PROVIDENT.brandCode -> DedicatedFundKind.HOUSING_PROVIDENT.defaultAliases
            else -> ""
        }
    }

    fun mergeDefaultAliases(brandCode: String, existingAliases: String): String {
        val presetAliases = defaultAliasesForBrand(brandCode)
        if (presetAliases.isBlank()) return existingAliases
        return (existingAliases.split(",", "，", " ") + presetAliases.split(","))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
    }

    fun isMedicalInsuranceWallet(wallet: WalletEntity): Boolean {
        if (wallet.type.isLiability) return false
        if (wallet.brandCode.equals(DedicatedFundKind.MEDICAL_INSURANCE.brandCode, ignoreCase = true)) {
            return true
        }
        val combined = "${wallet.name} ${wallet.subTypeLabel} ${wallet.aliases}"
        return MEDICAL_WALLET_KEYWORDS.any { combined.contains(it) }
    }

    fun isProvidentFundWallet(wallet: WalletEntity): Boolean {
        if (wallet.type.isLiability) return false
        if (wallet.brandCode.equals(DedicatedFundKind.HOUSING_PROVIDENT.brandCode, ignoreCase = true)) {
            return true
        }
        val combined = "${wallet.name} ${wallet.subTypeLabel} ${wallet.aliases}"
        return PROVIDENT_WALLET_KEYWORDS.any { combined.contains(it) }
    }

    fun isDedicatedFundWallet(wallet: WalletEntity): Boolean {
        return isMedicalInsuranceWallet(wallet) || isProvidentFundWallet(wallet)
    }

    fun resolveDedicatedFundKind(wallet: WalletEntity): DedicatedFundKind? {
        return when {
            isMedicalInsuranceWallet(wallet) -> DedicatedFundKind.MEDICAL_INSURANCE
            isProvidentFundWallet(wallet) -> DedicatedFundKind.HOUSING_PROVIDENT
            else -> null
        }
    }

    fun findPersonalMedicalWallet(wallets: List<WalletEntity>): WalletEntity? {
        return wallets.firstOrNull {
            !it.type.isLiability && it.brandCode.equals(DedicatedFundKind.MEDICAL_INSURANCE.brandCode, ignoreCase = true)
        } ?: wallets.firstOrNull { isMedicalInsuranceWallet(it) }
    }

    fun findHousingProvidentWallet(wallets: List<WalletEntity>): WalletEntity? {
        return wallets.firstOrNull {
            !it.type.isLiability && it.brandCode.equals(DedicatedFundKind.HOUSING_PROVIDENT.brandCode, ignoreCase = true)
        } ?: wallets.firstOrNull { isProvidentFundWallet(it) }
    }

    /**
     * 判断某笔交易是否为组合支付自动拆出的「医保个账关联子单」
     */
    fun isCompositeMedicalPersonalChild(tx: TransactionEntity): Boolean {
        if (tx.orderKey.endsWith(COMPOSITE_MEDICAL_PERSONAL_SUFFIX)) return true
        if (tx.dedupFingerprint.endsWith(COMPOSITE_MEDICAL_PERSONAL_SUFFIX)) return true
        if (tx.note.startsWith(COMPOSITE_CHILD_NOTE_PREFIX)) return true
        return (tx.relatedTransactionId ?: 0L) > 0L &&
            TagCatalog.containsTag(tx.tags, "医保个账") &&
            TagCatalog.containsTag(tx.tags, "组合支付")
    }

    /**
     * 判断某笔交易是否为组合支付的「个人自费主单」
     */
    fun isCompositeMedicalMain(tx: TransactionEntity): Boolean {
        return !isCompositeMedicalPersonalChild(tx) &&
            TagCatalog.containsTag(tx.tags, "组合支付") &&
            TagCatalog.containsTag(tx.tags, "医保")
    }

    /**
     * 生成工资条同月同金额幂等防重 `orderKey`：`PAYSLIP:<yyyyMM>:<netCents>:<grossCents>`
     */
    fun formatPayslipYearMonth(
        paydayTimestampMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = paydayTimestampMillis
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d%02d", year, month)
    }

    fun buildPayslipOrderKey(
        paydayTimestampMillis: Long,
        netTakeHomePayCents: Long,
        grossSalaryCents: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val ym = formatPayslipYearMonth(paydayTimestampMillis, timeZone)
        val net = netTakeHomePayCents.coerceAtLeast(0L)
        val gross = grossSalaryCents.coerceAtLeast(0L)
        return "$PAYSLIP_ORDER_PREFIX$ym:$net:$gross"
    }

    /**
     * 判断某笔交易是否为工资条分账生成的「医保个账划入子单」（`#PAYSLIP_MED`）
     */
    fun isPayslipMedicalChild(tx: TransactionEntity): Boolean {
        if (tx.orderKey.endsWith(PAYSLIP_MEDICAL_CHILD_SUFFIX)) return true
        if (tx.dedupFingerprint.endsWith(PAYSLIP_MEDICAL_CHILD_SUFFIX)) return true
        return tx.note.startsWith(PAYSLIP_MEDICAL_CHILD_NOTE_PREFIX)
    }

    /**
     * 判断某笔交易是否为工资条分账生成的「双边公积金缴存子单」（`#PAYSLIP_PROV`）
     */
    fun isPayslipProvidentChild(tx: TransactionEntity): Boolean {
        if (tx.orderKey.endsWith(PAYSLIP_PROVIDENT_CHILD_SUFFIX)) return true
        if (tx.dedupFingerprint.endsWith(PAYSLIP_PROVIDENT_CHILD_SUFFIX)) return true
        return tx.note.startsWith(PAYSLIP_PROVIDENT_CHILD_NOTE_PREFIX)
    }

    /**
     * 判断某笔交易是否为工资条自动分账生成的专项保障子单（`#PAYSLIP_MED` 或 `#PAYSLIP_PROV`）
     */
    fun isPayslipDedicatedChild(tx: TransactionEntity): Boolean {
        return isPayslipMedicalChild(tx) || isPayslipProvidentChild(tx)
    }

    /**
     * 判断某笔交易是否为工资条导入的「实发工资主单」
     */
    fun isPayslipMainTransaction(tx: TransactionEntity): Boolean {
        return !isPayslipDedicatedChild(tx) &&
            tx.type == TransactionType.INCOME &&
            tx.orderKey.startsWith(PAYSLIP_ORDER_PREFIX)
    }

    fun formatPayslipMainNote(
        draft: PayslipRecognitionDraft,
        customNote: String = ""
    ): String {
        fun fmt(cents: Long): String = String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)
        val parts = mutableListOf<String>()
        if (draft.grossSalaryCents > 0L) {
            parts.add("应发¥${fmt(draft.grossSalaryCents)}")
        }
        if (draft.incomeTaxCents > 0L) {
            parts.add("个税¥${fmt(draft.incomeTaxCents)}")
        }
        val socialPersonal = draft.pensionAndUnemploymentDeductionCents.coerceAtLeast(0L) +
            draft.personalMedicalDeductionCents.coerceAtLeast(0L)
        if (socialPersonal > 0L) {
            parts.add("社保个人¥${fmt(socialPersonal)}")
        }
        if (draft.personalProvidentDeductionCents > 0L) {
            parts.add("公积金个人¥${fmt(draft.personalProvidentDeductionCents)}")
        }
        if (draft.netTakeHomePayCents > 0L) {
            parts.add("实发¥${fmt(draft.netTakeHomePayCents)}")
        }
        val summary = parts.joinToString(" · ")
        val tail = customNote.trim()
        return when {
            summary.isNotEmpty() && tail.isNotEmpty() -> "$summary | $tail".take(160)
            summary.isNotEmpty() -> summary.take(160)
            tail.isNotEmpty() -> tail.take(160)
            else -> "工资条导入实发薪资"
        }
    }

    fun formatPayslipMedicalChildNote(
        personalMedicalDeductionCents: Long,
        depositCents: Long,
        customTail: String = ""
    ): String {
        fun fmt(cents: Long): String = String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)
        val base = buildString {
            append(PAYSLIP_MEDICAL_CHILD_NOTE_PREFIX)
            append("¥")
            append(fmt(depositCents))
            if (personalMedicalDeductionCents > 0L) {
                append("（工资个人扣缴¥")
                append(fmt(personalMedicalDeductionCents))
                append("）")
            }
        }
        val tail = customTail.trim()
        return if (tail.isNotEmpty()) "$base | $tail".take(160) else base.take(160)
    }

    fun formatPayslipProvidentChildNote(
        personalProvidentCents: Long,
        employerProvidentCents: Long,
        totalDepositCents: Long,
        customTail: String = ""
    ): String {
        fun fmt(cents: Long): String = String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)
        val p = personalProvidentCents.coerceAtLeast(0L)
        val e = employerProvidentCents.coerceAtLeast(0L)
        val base = buildString {
            append(PAYSLIP_PROVIDENT_CHILD_NOTE_PREFIX)
            append("¥")
            append(fmt(totalDepositCents))
            if (p > 0L || e > 0L) {
                append("（个人¥")
                append(fmt(p))
                append(" + 单位¥")
                append(fmt(e))
                append("）")
            }
        }
        val tail = customTail.trim()
        return if (tail.isNotEmpty()) "$base | $tail".take(160) else base.take(160)
    }

    /**
     * 工资条识别结果 -> 三路目标账户标准路由映射
     */
    fun resolvePayslipTargetWallets(
        wallets: List<WalletEntity>,
        draft: PayslipRecognitionDraft? = null,
        settings: DedicatedFundSettings = _settingsFlow.value
    ): PayslipWalletRouting {
        val regularWallets = wallets.filter { !it.type.isLiability && !isDedicatedFundWallet(it) }
        val configuredPayrollId = settings.providentPlan.linkedPayrollWalletId
            ?: settings.medicalPlan.linkedPayrollWalletId
        val payrollWallet = regularWallets.firstOrNull { it.id == configuredPayrollId }
            ?: regularWallets.firstOrNull { w ->
                val text = "${w.name} ${w.subTypeLabel} ${w.aliases}"
                w.type == WalletType.DEBIT_BANK && (text.contains("工资") || text.contains("代发"))
            }
            ?: regularWallets.firstOrNull { it.type == WalletType.DEBIT_BANK }
            ?: regularWallets.firstOrNull()

        val medicalWallet = findPersonalMedicalWallet(wallets)
        val providentWallet = findHousingProvidentWallet(wallets)

        val netPayCents = draft?.netTakeHomePayCents?.coerceAtLeast(0L) ?: 0L
        val medicalDepositCents = when {
            draft == null -> settings.medicalPlan.totalMonthlyDepositCents
            draft.medicalPersonalAccountDepositCents > 0L -> draft.medicalPersonalAccountDepositCents
            draft.personalMedicalDeductionCents > 0L ->
                draft.personalMedicalDeductionCents + settings.medicalPlan.employerMonthlyCents.coerceAtLeast(0L)
            else -> 0L
        }
        val providentDepositCents = when {
            draft == null -> settings.providentPlan.totalMonthlyDepositCents
            else -> draft.bilateralProvidentDepositCents.coerceAtLeast(0L)
        }

        return PayslipWalletRouting(
            payrollDebitWallet = payrollWallet,
            personalMedicalWallet = medicalWallet,
            housingProvidentWallet = providentWallet,
            netPayDepositCents = netPayCents,
            medicalAccountDepositCents = medicalDepositCents,
            providentAccountDepositCents = providentDepositCents
        )
    }

    private val ORIG_NOTE_REGEX = Regex("""原价¥\s*(\d+(?:\.\d{1,2})?)""")
    private val POOL_NOTE_REGEX = Regex("""医保统筹¥\s*(\d+(?:\.\d{1,2})?)""")
    private val PERSONAL_NOTE_REGEX = Regex("""医保个账(?:扣款)?¥\s*(\d+(?:\.\d{1,2})?)""")
    private val UNSPECIFIED_MED_NOTE_REGEX = Regex("""医保支付¥\s*(\d+(?:\.\d{1,2})?)""")
    private val ZERO_SELF_PAY_NOTE_REGEX = Regex("""(?:个人实付|自费实扣)¥\s*0(?:\.0{1,2})?(?!\d)""")

    private fun parseYuanMatchToCents(match: MatchResult?): Long {
        val raw = match?.groupValues?.getOrNull(1) ?: return 0L
        val v = raw.toDoubleOrNull() ?: return 0L
        if (v.isNaN() || v.isInfinite() || v <= 0.0) return 0L
        return (v * 100.0).roundToLong()
    }

    fun parseStructuredMedicalNote(note: String): StructuredMedicalNoteAmounts {
        if (note.isBlank()) return StructuredMedicalNoteAmounts()
        val header = note.substringBefore("|")
        return StructuredMedicalNoteAmounts(
            originalCostCents = parseYuanMatchToCents(ORIG_NOTE_REGEX.find(header)),
            poolSubsidyCents = parseYuanMatchToCents(POOL_NOTE_REGEX.find(header)),
            personalAccountCents = parseYuanMatchToCents(PERSONAL_NOTE_REGEX.find(header)),
            unspecifiedMedicalCents = parseYuanMatchToCents(UNSPECIFIED_MED_NOTE_REGEX.find(header)),
            selfPayZeroExplicit = ZERO_SELF_PAY_NOTE_REGEX.containsMatchIn(header)
        )
    }

    fun formatStructuredMedicalNote(
        originalCostCents: Long,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long,
        commercialDiscountCents: Long,
        cashSelfPayCents: Long,
        tailSummary: String
    ): String {
        fun fmt(cents: Long): String = String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)
        val parts = mutableListOf<String>()
        if (originalCostCents > 0L) {
            parts.add("原价¥${fmt(originalCostCents)}")
        }
        if (poolSubsidyCents > 0L) {
            parts.add("医保统筹¥${fmt(poolSubsidyCents)}")
        }
        if (personalAccountCents > 0L) {
            parts.add("医保个账¥${fmt(personalAccountCents)}")
        }
        if (unspecifiedMedicalCents > 0L) {
            parts.add("医保支付¥${fmt(unspecifiedMedicalCents)}")
        }
        if (commercialDiscountCents > 0L) {
            parts.add("优惠¥${fmt(commercialDiscountCents)}")
        }
        if (cashSelfPayCents > 0L) {
            parts.add("自费实扣¥${fmt(cashSelfPayCents)}")
        } else {
            parts.add("个人实付¥0.00")
        }
        val prefix = parts.joinToString(" · ")
        val cleanTail = tailSummary.trim()
        return if (cleanTail.isNotEmpty()) "$prefix | $cleanTail" else prefix
    }

    /**
     * 账本列表行（`ICostLedgerRow`）医保结构化费用胶囊文案生成：
     * - 对 `#MED_PERSONAL` 医保个账关联子单返回 `null`（子单本身已带 `#医保 #医保个账 #组合支付` 标签，不重复挂总费胶囊）；
     * - 对包含结构化医保拆解（统筹 / 个账 / 医保基金汇总）或存在关联 `#MED_PERSONAL` 子单的主单，输出与账单详情页一致的口径，
     *   例如：`总费¥100.00 · 统筹¥50.00 · 个账¥30.00`（若同时存在商家优惠则追加 ` · 优惠¥X.XX`）；
     * - 对普通非医保商品立减单返回 `null`，由列表继续走原有 `原价¥X · 优惠¥Y` 橙色胶囊。
     */
    fun buildLedgerMedicalBadgeSummary(
        tx: TransactionEntity,
        childPersonalSumCents: Long = 0L,
        formatCents: (Long) -> String = { cents ->
            "¥" + String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)
        }
    ): String? {
        if (tx.type != TransactionType.EXPENSE && tx.type != TransactionType.REFUND_OFFSET) return null
        if (isCompositeMedicalPersonalChild(tx)) return null

        val parsed = parseStructuredMedicalNote(tx.note)
        val cleanChildPersonal = childPersonalSumCents.coerceAtLeast(0L)
        val effectivePersonalCents = if (cleanChildPersonal > 0L) {
            cleanChildPersonal
        } else {
            parsed.personalAccountCents
        }
        val hasMedicalBreakdown = parsed.poolSubsidyCents > 0L ||
            effectivePersonalCents > 0L ||
            parsed.unspecifiedMedicalCents > 0L
        if (!hasMedicalBreakdown) return null

        val effectiveOrigCents = parsed.originalCostCents.takeIf { it > 0L }
            ?: (tx.amountCents + tx.discountCents + cleanChildPersonal)
        val commercialDiscountCents = if (cleanChildPersonal > 0L) {
            (tx.discountCents - parsed.poolSubsidyCents - parsed.unspecifiedMedicalCents).coerceAtLeast(0L)
        } else {
            (tx.discountCents - parsed.poolSubsidyCents - parsed.personalAccountCents - parsed.unspecifiedMedicalCents)
                .coerceAtLeast(0L)
        }

        val parts = buildList {
            add("总费${formatCents(effectiveOrigCents)}")
            if (parsed.poolSubsidyCents > 0L) {
                add("统筹${formatCents(parsed.poolSubsidyCents)}")
            } else if (parsed.unspecifiedMedicalCents > 0L) {
                add("医保${formatCents(parsed.unspecifiedMedicalCents)}")
            }
            if (effectivePersonalCents > 0L) {
                add("个账${formatCents(effectivePersonalCents)}")
            }
            if (commercialDiscountCents > 0L) {
                add("优惠${formatCents(commercialDiscountCents)}")
            }
        }
        return parts.joinToString(" · ")
    }
}

/**
 * 医疗与医保费用四维透视纯函数聚合器：
 * 正确处理「主单 + `#MED_PERSONAL` 子单」组合双扣、「未建医保卡时的单笔混合医保」、「纯医保 100% 统筹」与「普通自费医疗支出」，
 * 保证原价、统筹报销、医保个账与个人现金自付绝不翻倍或遗漏。
 */
object MedicalExpenseInsightsCalculator {

    fun isMedicalOrInsuranceTransaction(tx: TransactionEntity): Boolean {
        if (tx.type != TransactionType.EXPENSE || tx.isSystemBalanceAnchor()) return false
        return tx.category == "医疗" ||
            tx.category.startsWith("医疗-") ||
            TagCatalog.containsTag(tx.tags, "医保") ||
            TagCatalog.containsTag(tx.tags, "医保个账")
    }

    fun compute(
        transactions: List<TransactionEntity>,
        wallets: List<WalletEntity>
    ): MedicalExpenseInsights {
        val medicalTxs = transactions.filter { isMedicalOrInsuranceTransaction(it) }
        if (medicalTxs.isEmpty()) return MedicalExpenseInsights()

        val allWalletIds = wallets.mapTo(hashSetOf()) { it.id }
        val medicalWalletIds = wallets
            .filter { DedicatedFundManager.isMedicalInsuranceWallet(it) }
            .mapTo(hashSetOf()) { it.id }

        val medicalTxIds = medicalTxs.mapTo(hashSetOf()) { it.id }
        val childIdsWithPresentParent = hashSetOf<Long>()
        val parentsWithPresentChild = hashSetOf<Long>()

        for (tx in medicalTxs) {
            if (DedicatedFundManager.isCompositeMedicalPersonalChild(tx)) {
                val pid = tx.relatedTransactionId
                if (pid != null && pid in medicalTxIds) {
                    childIdsWithPresentParent.add(tx.id)
                    parentsWithPresentChild.add(pid)
                }
            }
        }

        var totalMedicalCost = 0L
        var poolReimbursed = 0L
        var personalAccountPaid = 0L
        var cashOutOfPocketNet = 0L
        var billCount = 0

        for (tx in medicalTxs) {
            val rate = if (tx.exchangeRateToCny > 0.0) tx.exchangeRateToCny else 1.0
            val netBaseCny = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
            val discountCny = (tx.discountCents * rate).roundToLong().coerceAtLeast(0L)

            // 1. 组合支付的 `#MED_PERSONAL` 医保个账子单：仅贡献「医保个账支出」与对应部分的医疗总费用
            if (DedicatedFundManager.isCompositeMedicalPersonalChild(tx)) {
                personalAccountPaid += netBaseCny
                totalMedicalCost += netBaseCny
                if (tx.id !in childIdsWithPresentParent) {
                    billCount += 1
                }
                continue
            }

            billCount += 1
            val parsedNote = DedicatedFundManager.parseStructuredMedicalNote(tx.note)
            val poolCny = (parsedNote.poolSubsidyCents * rate).roundToLong().coerceAtLeast(0L)
            val personalNoteCny = ((parsedNote.personalAccountCents + parsedNote.unspecifiedMedicalCents) * rate)
                .roundToLong()
                .coerceAtLeast(0L)
            val isPaidByMedicalWallet = tx.sourceWalletId != null && (
                tx.sourceWalletId in medicalWalletIds ||
                    (tx.sourceWalletId !in allWalletIds && DedicatedFundManager.isMedicalPaymentMethodHint(tx.paymentMethodRaw))
                )
            val isCompositeMain = DedicatedFundManager.isCompositeMedicalMain(tx) || tx.id in parentsWithPresentChild

            when {
                // 2. 组合支付自费主单（其个账部分已拆为独立 `#MED_PERSONAL` 子单，此处绝不重复计入个账）
                isCompositeMain -> {
                    val effectivePool = if (parsedNote.hasExplicitBreakdown) poolCny else discountCny
                    val cashNet = if (tx.reimbursed) 0L else netBaseCny
                    poolReimbursed += effectivePool
                    cashOutOfPocketNet += cashNet
                    totalMedicalCost += netBaseCny + effectivePool
                }

                // 3. 直接使用个人医保账户扣款的纯医保或药店支出（含纯医保「统筹 + 个账」扣医保卡个账部分）
                isPaidByMedicalWallet -> {
                    val effectivePool = if (poolCny > 0L) poolCny else 0L
                    personalAccountPaid += netBaseCny
                    poolReimbursed += effectivePool
                    totalMedicalCost += netBaseCny + effectivePool
                }

                // 4. 纯医保（自费实付 0.00，且未扣减任何钱包，如 100% 医保统筹报销或未建医保卡时的纯医保单）
                tx.sourceWalletId == null && (
                    parsedNote.selfPayZeroExplicit ||
                        (TagCatalog.containsTag(tx.tags, "医保") && discountCny == 0L && parsedNote.hasExplicitBreakdown)
                    ) -> {
                    if (poolCny > 0L || personalNoteCny > 0L) {
                        poolReimbursed += poolCny
                        personalAccountPaid += personalNoteCny
                        totalMedicalCost += (poolCny + personalNoteCny).coerceAtLeast(netBaseCny)
                    } else {
                        // 旧版纯未细分医保单（无结构化拆分字样，全额归入医保个账/基金支出）
                        personalAccountPaid += netBaseCny
                        totalMedicalCost += netBaseCny
                    }
                }

                // 5. 旧版未建医保卡的纯医保单（sourceWalletId == null 且带 #医保 标签且无自费账户）
                tx.sourceWalletId == null && TagCatalog.containsTag(tx.tags, "医保") && discountCny == 0L && !parsedNote.hasExplicitBreakdown -> {
                    personalAccountPaid += netBaseCny
                    totalMedicalCost += netBaseCny
                }

                // 6. 未建个人医保账户时的混合医保单（单笔记录，备注/优惠携带统筹与个账）或普通自费医疗支出
                else -> {
                    val cashNet = if (tx.reimbursed) 0L else netBaseCny
                    val effectivePool: Long
                    val effectivePersonal: Long
                    if (parsedNote.hasExplicitBreakdown) {
                        effectivePool = poolCny
                        effectivePersonal = personalNoteCny
                    } else if (TagCatalog.containsTag(tx.tags, "医保") && discountCny > 0L) {
                        // 兼容 v1.1.4 旧版混合医保单（当时医保抵扣写在 discountCents）
                        effectivePool = 0L
                        effectivePersonal = discountCny
                    } else {
                        effectivePool = 0L
                        effectivePersonal = 0L
                    }
                    poolReimbursed += effectivePool
                    personalAccountPaid += effectivePersonal
                    cashOutOfPocketNet += cashNet
                    totalMedicalCost += netBaseCny + effectivePool + effectivePersonal
                }
            }
        }

        return MedicalExpenseInsights(
            totalMedicalCostCents = totalMedicalCost,
            poolReimbursedCents = poolReimbursed,
            personalAccountPaidCents = personalAccountPaid,
            cashOutOfPocketNetCents = cashOutOfPocketNet,
            medicalBillCount = billCount
        )
    }
}
