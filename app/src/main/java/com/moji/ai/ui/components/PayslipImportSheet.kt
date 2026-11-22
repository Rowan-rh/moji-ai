package com.moji.ai.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.PayslipImportEngine
import com.moji.ai.ai.PayslipParseResult
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.DedicatedFundKind
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.DedicatedFundSettings
import com.moji.ai.data.model.PayslipRecognitionDraft
import com.moji.ai.data.repository.PayslipCommitOutcome
import com.moji.ai.data.repository.PayslipCommitRequest
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostIncomeColor
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

/**
 * 1:1 iCost 风格工资条导入与三路自动分账向导底部抽屉（`Spec-B` · `AC-B2` / `AC-B3` / `AC-B5`）：
 * - 支持相册选工资条截图（端侧离线 ML Kit OCR + 同行聚类）、选 CSV/TSV/XLSX 薪资表、粘贴剪贴板、一键填入标准示例及可选云端 AI 校准；
 * - 支持逐项核对与修改发薪日、单位、应发/五险一金/个税/实发金额，以及切换目标钱包和独立开关三路入账支路；
 * - 未建「个人医保账户」或「住房公积金」时自动展示一键建档徽章（`includeInNetWorth = false`，绝不虚增日常净资产）；
 * - 命中同月 `PAYSLIP:<yyyyMM>:<netCents>:<grossCents>` 防重保护时弹窗二次确认。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayslipImportSheet(
    visible: Boolean,
    currentLedger: String,
    wallets: List<WalletEntity>,
    dedicatedFundSettings: DedicatedFundSettings,
    aiConfig: AiConfig,
    onParseImageUri: suspend (Uri) -> Result<PayslipParseResult>,
    onParseDocumentUri: suspend (Uri) -> Result<PayslipParseResult>,
    onParseRawText: suspend (String) -> Result<PayslipParseResult>,
    onCalibrateWithCloudAi: suspend (String) -> Result<PayslipParseResult>,
    onCommitPayslip: suspend (PayslipCommitRequest) -> PayslipCommitOutcome,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp

    val regularAssetWallets = remember(wallets) {
        wallets.filter { !it.type.isLiability && !DedicatedFundManager.isDedicatedFundWallet(it) }
    }
    val medicalWallets = remember(wallets) {
        wallets.filter { DedicatedFundManager.isMedicalInsuranceWallet(it) }
    }
    val providentWallets = remember(wallets) {
        wallets.filter { DedicatedFundManager.isProvidentFundWallet(it) }
    }
    val defaultRouting = remember(wallets, dedicatedFundSettings) {
        DedicatedFundManager.resolvePayslipTargetWallets(
            wallets = wallets,
            draft = null,
            settings = dedicatedFundSettings
        )
    }

    var isWorking by remember { mutableStateOf(false) }
    var workingLabel by remember { mutableStateOf("") }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var isErrorBanner by remember { mutableStateOf(false) }
    var detectedFormatLabel by remember { mutableStateOf<String?>(null) }
    var parseHints by remember { mutableStateOf<List<String>>(emptyList()) }

    var showRawTextEditor by remember { mutableStateOf(false) }
    var rawPayslipText by remember { mutableStateOf("") }
    var useVerticalSampleNext by remember { mutableStateOf(false) }

    // 可编辑草稿字段（元为单位展示，提交时精确转分）
    var paydayText by remember { mutableStateOf(PayslipImportEngine.formatDateYmd(System.currentTimeMillis())) }
    var employerNameText by remember { mutableStateOf("") }
    var customNoteText by remember { mutableStateOf("") }

    var grossYuanText by remember { mutableStateOf("") }
    var taxYuanText by remember { mutableStateOf("") }
    var pensionUnempYuanText by remember { mutableStateOf("") }
    var personalMedicalYuanText by remember { mutableStateOf("") }
    var medicalDepositYuanText by remember { mutableStateOf("") }
    var personalProvidentYuanText by remember { mutableStateOf("") }
    var employerProvidentYuanText by remember { mutableStateOf("") }
    var netPayYuanText by remember { mutableStateOf("") }

    // 三路路由开关与目标钱包选择
    var enableNetPayBranch by remember { mutableStateOf(true) }
    var selectedPayrollWalletId by remember(defaultRouting.payrollDebitWallet?.id) {
        mutableStateOf(defaultRouting.payrollDebitWallet?.id)
    }

    var enableMedicalBranch by remember { mutableStateOf(true) }
    var selectedMedicalWalletId by remember(defaultRouting.personalMedicalWallet?.id) {
        mutableStateOf(defaultRouting.personalMedicalWallet?.id)
    }

    var enableProvidentBranch by remember { mutableStateOf(true) }
    var selectedProvidentWalletId by remember(defaultRouting.housingProvidentWallet?.id) {
        mutableStateOf(defaultRouting.housingProvidentWallet?.id)
    }

    // 同月重复工资条二次确认弹窗状态
    var pendingDuplicateInfo by remember {
        mutableStateOf<Pair<PayslipCommitRequest, PayslipCommitOutcome.DuplicateDetected>?>(null)
    }

    LaunchedEffect(regularAssetWallets) {
        if (selectedPayrollWalletId != null && regularAssetWallets.none { it.id == selectedPayrollWalletId }) {
            selectedPayrollWalletId = defaultRouting.payrollDebitWallet?.id
        }
    }
    LaunchedEffect(medicalWallets) {
        if (selectedMedicalWalletId == null && medicalWallets.isNotEmpty()) {
            selectedMedicalWalletId = medicalWallets.first().id
        } else if (selectedMedicalWalletId != null && medicalWallets.none { it.id == selectedMedicalWalletId }) {
            selectedMedicalWalletId = medicalWallets.firstOrNull()?.id
        }
    }
    LaunchedEffect(providentWallets) {
        if (selectedProvidentWalletId == null && providentWallets.isNotEmpty()) {
            selectedProvidentWalletId = providentWallets.first().id
        } else if (selectedProvidentWalletId != null && providentWallets.none { it.id == selectedProvidentWalletId }) {
            selectedProvidentWalletId = providentWallets.firstOrNull()?.id
        }
    }

    fun centsToInputText(cents: Long): String {
        if (cents <= 0L) return ""
        return String.format(Locale.US, "%.2f", cents / 100.0)
    }

    fun inputTextToCents(raw: String): Long {
        val cleaned = raw.trim().replace(",", "").replace("¥", "").replace("￥", "")
        if (cleaned.isEmpty()) return 0L
        val v = cleaned.toDoubleOrNull() ?: return 0L
        if (!v.isFinite() || v <= 0.0) return 0L
        return (v * 100.0).roundToLong()
    }

    fun applyParsedResultToFields(result: PayslipParseResult) {
        val d = result.draft
        val routing = DedicatedFundManager.resolvePayslipTargetWallets(
            wallets = wallets,
            draft = d,
            settings = dedicatedFundSettings
        )
        rawPayslipText = result.normalizedText
        detectedFormatLabel = result.detectedFormat
        parseHints = result.hints
        paydayText = PayslipImportEngine.formatDateYmd(d.paydayTimestampMillis)
        employerNameText = d.employerName
        grossYuanText = centsToInputText(d.grossSalaryCents)
        taxYuanText = centsToInputText(d.incomeTaxCents)
        pensionUnempYuanText = centsToInputText(d.pensionAndUnemploymentDeductionCents)
        personalMedicalYuanText = centsToInputText(d.personalMedicalDeductionCents)
        medicalDepositYuanText = centsToInputText(routing.medicalAccountDepositCents)
        personalProvidentYuanText = centsToInputText(d.personalProvidentDeductionCents)
        employerProvidentYuanText = centsToInputText(d.employerProvidentMatchCents)
        netPayYuanText = centsToInputText(routing.netPayDepositCents)

        selectedPayrollWalletId = routing.payrollDebitWallet?.id ?: selectedPayrollWalletId
        selectedMedicalWalletId = routing.personalMedicalWallet?.id
        selectedProvidentWalletId = routing.housingProvidentWallet?.id

        enableNetPayBranch = routing.netPayDepositCents > 0L
        enableMedicalBranch = routing.medicalAccountDepositCents > 0L
        enableProvidentBranch = routing.providentAccountDepositCents > 0L

        isErrorBanner = false
        bannerMessage = "已通过「${result.detectedFormat}」完成提取，请核对下方金额与三路账户后确认入账"
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                workingLabel = "正在端侧离线 OCR 识别工资条截图…"
                bannerMessage = null
                try {
                    onParseImageUri(uri).fold(
                        onSuccess = { parsed -> applyParsedResultToFields(parsed) },
                        onFailure = { err ->
                            isErrorBanner = true
                            bannerMessage = err.message ?: "工资条截图识别失败"
                        }
                    )
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                workingLabel = "正在解码并解析薪资表…"
                bannerMessage = null
                try {
                    onParseDocumentUri(uri).fold(
                        onSuccess = { parsed -> applyParsedResultToFields(parsed) },
                        onFailure = { err ->
                            isErrorBanner = true
                            bannerMessage = err.message ?: "薪资表解析失败"
                        }
                    )
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val grossCents = inputTextToCents(grossYuanText)
    val taxCents = inputTextToCents(taxYuanText)
    val pensionUnempCents = inputTextToCents(pensionUnempYuanText)
    val personalMedicalCents = inputTextToCents(personalMedicalYuanText)
    val medicalDepositCents = inputTextToCents(medicalDepositYuanText)
    val personalProvidentCents = inputTextToCents(personalProvidentYuanText)
    val employerProvidentCents = inputTextToCents(employerProvidentYuanText)
    val bilateralProvidentCents = personalProvidentCents + employerProvidentCents
    val netPayCents = inputTextToCents(netPayYuanText)

    val parsedPaydayMillis = remember(paydayText, dedicatedFundSettings) {
        PayslipImportEngine.parsePayslipDateMillis(
            rawDate = paydayText,
            defaultDayOfMonth = dedicatedFundSettings.providentPlan.depositDayOfMonth
        )
    }
    val paydayFormatError = paydayText.isNotBlank() && parsedPaydayMillis == null

    val effectiveNetBranchCents = if (enableNetPayBranch) netPayCents else 0L
    val effectiveMedBranchCents = if (enableMedicalBranch) medicalDepositCents else 0L
    val effectiveProvBranchCents = if (enableProvidentBranch) bilateralProvidentCents else 0L
    val activeBranchCount = listOf(
        effectiveNetBranchCents > 0L,
        effectiveMedBranchCents > 0L,
        effectiveProvBranchCents > 0L
    ).count { it }

    val selectedPayrollWallet = regularAssetWallets.firstOrNull { it.id == selectedPayrollWalletId }
    val selectedMedicalWallet = medicalWallets.firstOrNull { it.id == selectedMedicalWalletId }
    val selectedProvidentWallet = providentWallets.firstOrNull { it.id == selectedProvidentWalletId }

    val netWorthDeltaCents = buildList {
        if (effectiveNetBranchCents > 0L && (selectedPayrollWallet?.includeInNetWorth != false)) {
            add(effectiveNetBranchCents)
        }
        if (effectiveMedBranchCents > 0L && selectedMedicalWallet?.includeInNetWorth == true) {
            add(effectiveMedBranchCents)
        }
        if (effectiveProvBranchCents > 0L && selectedProvidentWallet?.includeInNetWorth == true) {
            add(effectiveProvBranchCents)
        }
    }.sum()

    val canUseCloudAi = aiConfig.modelName.isNotBlank() &&
        (aiConfig.apiKey.isNotBlank() || aiConfig.allowsEmptyApiKey())

    fun buildCurrentCommitRequest(forceDuplicate: Boolean): PayslipCommitRequest {
        val resolvedPayday = parsedPaydayMillis ?: System.currentTimeMillis()
        val draft = PayslipRecognitionDraft(
            grossSalaryCents = grossCents,
            incomeTaxCents = taxCents,
            pensionAndUnemploymentDeductionCents = pensionUnempCents,
            personalMedicalDeductionCents = personalMedicalCents,
            medicalPersonalAccountDepositCents = medicalDepositCents,
            personalProvidentDeductionCents = personalProvidentCents,
            employerProvidentMatchCents = employerProvidentCents,
            netTakeHomePayCents = netPayCents,
            paydayTimestampMillis = resolvedPayday,
            employerName = employerNameText.trim()
        )
        return PayslipCommitRequest(
            ledgerName = currentLedger,
            draft = draft,
            enableNetPay = enableNetPayBranch,
            payrollWalletId = selectedPayrollWalletId,
            enableMedicalDeposit = enableMedicalBranch,
            medicalWalletId = selectedMedicalWalletId,
            autoCreateMedicalWallet = true,
            medicalDepositCents = medicalDepositCents,
            enableProvidentDeposit = enableProvidentBranch,
            providentWalletId = selectedProvidentWalletId,
            autoCreateProvidentWallet = true,
            providentDepositCents = bilateralProvidentCents,
            customNote = customNoteText.trim(),
            forceDuplicate = forceDuplicate
        )
    }

    ICostSmoothBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = "工资条导入 · 三路分账",
                subtitle = "离线 OCR 截图 / CSV·TSV 薪资表 · 单事务原子入账",
                onClose = requestDismiss
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // 1. 多源离线优先导入操作卡
                item {
                    ICostFormCard {
                        Text(
                            text = "1. 选择工资条来源（100% 支持端侧离线解析）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                enabled = !isWorking,
                                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("选工资条截图", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf(
                                            "text/*",
                                            "application/csv",
                                            "text/comma-separated-values",
                                            "text/tab-separated-values",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "*/*"
                                        )
                                    )
                                },
                                enabled = !isWorking,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("选 CSV / TSV 表", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                enabled = !isWorking,
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = cm?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        .orEmpty()
                                    if (clip.isBlank()) {
                                        isErrorBanner = true
                                        bannerMessage = "剪贴板为空，请先复制工资条文本或薪资表行"
                                    } else {
                                        scope.launch {
                                            isWorking = true
                                            workingLabel = "正在解析剪贴板工资条…"
                                            try {
                                                onParseRawText(clip).fold(
                                                    onSuccess = { parsed -> applyParsedResultToFields(parsed) },
                                                    onFailure = { err ->
                                                        rawPayslipText = clip
                                                        showRawTextEditor = true
                                                        isErrorBanner = true
                                                        bannerMessage = err.message ?: "剪贴板文本未能识别为工资条"
                                                    }
                                                )
                                            } finally {
                                                isWorking = false
                                            }
                                        }
                                    }
                                },
                                label = { Text("粘贴剪贴板", fontSize = 12.sp) }
                            )

                            AssistChip(
                                enabled = !isWorking,
                                onClick = {
                                    val sample = if (useVerticalSampleNext) {
                                        PayslipImportEngine.SAMPLE_VERTICAL_TEXT
                                    } else {
                                        PayslipImportEngine.SAMPLE_HORIZONTAL_CSV
                                    }
                                    useVerticalSampleNext = !useVerticalSampleNext
                                    scope.launch {
                                        onParseRawText(sample).onSuccess { parsed ->
                                            applyParsedResultToFields(parsed)
                                        }
                                    }
                                },
                                label = { Text("填入标准示例", fontSize = 12.sp) }
                            )

                            AssistChip(
                                enabled = !isWorking,
                                onClick = { showRawTextEditor = !showRawTextEditor },
                                label = {
                                    Text(
                                        text = if (showRawTextEditor) "收起原文粘贴框" else "手动粘贴文本",
                                        fontSize = 12.sp
                                    )
                                }
                            )

                            if (canUseCloudAi && rawPayslipText.isNotBlank()) {
                                AssistChip(
                                    enabled = !isWorking,
                                    onClick = {
                                        scope.launch {
                                            isWorking = true
                                            workingLabel = "正在请求云端 AI 二次结构化校准…"
                                            try {
                                                onCalibrateWithCloudAi(rawPayslipText).fold(
                                                    onSuccess = { calibrated -> applyParsedResultToFields(calibrated) },
                                                    onFailure = { err ->
                                                        isErrorBanner = true
                                                        bannerMessage = err.message ?: "AI 校准失败"
                                                    }
                                                )
                                            } finally {
                                                isWorking = false
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = ICostBlueAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    label = { Text("AI 二次校准", fontSize = 12.sp, color = ICostBlueAccent) }
                                )
                            }
                        }

                        if (isWorking) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ICostBlueAccent
                                )
                                Text(
                                    text = workingLabel,
                                    fontSize = 12.sp,
                                    color = ICostBlueAccent,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        bannerMessage?.let { msg ->
                            val bannerBg = if (isErrorBanner) {
                                ICostCoralRed.copy(alpha = 0.12f)
                            } else {
                                ICostGreenIncome.copy(alpha = 0.12f)
                            }
                            val bannerFg = if (isErrorBanner) ICostCoralRed else ICostGreenIncome
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = bannerBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = msg,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = bannerFg
                                    )
                                    parseHints.forEach { hint ->
                                        Text(
                                            text = "· $hint",
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = showRawTextEditor) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ICostFormField(
                                    value = rawPayslipText,
                                    onValueChange = { rawPayslipText = it },
                                    label = "工资条原文（支持横向 CSV/TSV 或纵向项目-金额文本）",
                                    placeholder = "例如：\n应发合计 20000.00\n医疗保险个人 400.00\n住房公积金个人 1200.00  单位公积金 1200.00\n个人所得税 590.00\n实发工资 16110.00",
                                    singleLine = false,
                                    minLines = 4,
                                    maxLines = 7,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            isWorking = true
                                            workingLabel = "正在解析工资条文本…"
                                            try {
                                                onParseRawText(rawPayslipText).fold(
                                                    onSuccess = { parsed -> applyParsedResultToFields(parsed) },
                                                    onFailure = { err ->
                                                        isErrorBanner = true
                                                        bannerMessage = err.message ?: "解析失败"
                                                    }
                                                )
                                            } finally {
                                                isWorking = false
                                            }
                                        }
                                    },
                                    enabled = rawPayslipText.isNotBlank() && !isWorking,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("解析上方工资条文本", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. 薪资与五险一金拆解微调卡
                item {
                    ICostFormCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "2. 核对与微调工资条明细（单位：元）",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            detectedFormatLabel?.let { fmt ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ICostBlueAccent.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = fmt,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostBlueAccent,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ICostFormField(
                                value = paydayText,
                                onValueChange = { paydayText = it },
                                label = "发薪日期",
                                placeholder = "2026-11-15",
                                isError = paydayFormatError,
                                supportingText = if (paydayFormatError) "请输入 YYYY-MM-DD" else null,
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = employerNameText,
                                onValueChange = { employerNameText = it },
                                label = "发薪单位（选填）",
                                placeholder = "如：星辰科技",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ICostFormField(
                                value = grossYuanText,
                                onValueChange = { grossYuanText = sanitizeMoneyInput(it) },
                                label = "应发薪资总额",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = netPayYuanText,
                                onValueChange = { netPayYuanText = sanitizeMoneyInput(it) },
                                label = "实发到手工资",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        val derivedNetCandidateCents = (
                            grossCents - taxCents - pensionUnempCents - personalMedicalCents - personalProvidentCents
                            ).coerceAtLeast(0L)
                        if (grossCents > 0L && derivedNetCandidateCents > 0L && derivedNetCandidateCents != netPayCents) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ICostCellGray,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        netPayYuanText = centsToInputText(derivedNetCandidateCents)
                                        enableNetPayBranch = true
                                    }
                            ) {
                                Text(
                                    text = "按「应发 - 个人五险一金与个税」填入参考实发：${formatMoneyCents(derivedNetCandidateCents)} ›",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ICostBlueAccent,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ICostFormField(
                                value = taxYuanText,
                                onValueChange = { taxYuanText = sanitizeMoneyInput(it) },
                                label = "个人所得税扣款",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = pensionUnempYuanText,
                                onValueChange = { pensionUnempYuanText = sanitizeMoneyInput(it) },
                                label = "养老+失业(个人扣款)",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ICostFormField(
                                value = personalMedicalYuanText,
                                onValueChange = { next ->
                                    val sanitized = sanitizeMoneyInput(next)
                                    val prevSame = medicalDepositYuanText.isBlank() || medicalDepositYuanText == personalMedicalYuanText
                                    personalMedicalYuanText = sanitized
                                    if (prevSame) {
                                        medicalDepositYuanText = sanitized
                                    }
                                },
                                label = "医疗保险(个人扣款)",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = medicalDepositYuanText,
                                onValueChange = {
                                    medicalDepositYuanText = sanitizeMoneyInput(it)
                                    if (inputTextToCents(medicalDepositYuanText) > 0L) {
                                        enableMedicalBranch = true
                                    }
                                },
                                label = "医保个账划入额",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ICostFormField(
                                value = personalProvidentYuanText,
                                onValueChange = {
                                    personalProvidentYuanText = sanitizeMoneyInput(it)
                                    if (inputTextToCents(personalProvidentYuanText) > 0L) {
                                        enableProvidentBranch = true
                                    }
                                },
                                label = "住房公积金(个人)",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = employerProvidentYuanText,
                                onValueChange = {
                                    employerProvidentYuanText = sanitizeMoneyInput(it)
                                    if (inputTextToCents(employerProvidentYuanText) > 0L) {
                                        enableProvidentBranch = true
                                    }
                                },
                                label = "住房公积金(单位配缴)",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ICostFormField(
                            value = customNoteText,
                            onValueChange = { customNoteText = it },
                            label = "附加备注（选填）",
                            placeholder = "如：11月月薪 + 绩效奖金",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 3. 三路自动分账路由预览与专项账户自动建档卡
                item {
                    ICostFormCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "3. 三路自动分账路由预览",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "净资产预计 +${formatMoneyCents(netWorthDeltaCents)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostIncomeColor
                            )
                        }

                        // 支路 ① 实发工资入账
                        PayslipBranchCard(
                            stepBadge = "①",
                            title = "实发工资入账",
                            subtitle = "收入 · 分类「工资」· 标签 #工资",
                            amountCents = netPayCents,
                            accentColor = ICostGreenIncome,
                            enabled = enableNetPayBranch,
                            onToggle = { enableNetPayBranch = it }
                        ) {
                            if (regularAssetWallets.isEmpty()) {
                                Text(
                                    text = "当前暂无储蓄卡/普通资产账户，将记录工资流水（不关联账户）",
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    regularAssetWallets.forEach { w ->
                                        FilterChip(
                                            selected = selectedPayrollWalletId == w.id,
                                            onClick = { selectedPayrollWalletId = w.id },
                                            label = { Text(w.name, fontSize = 11.sp) }
                                        )
                                    }
                                    FilterChip(
                                        selected = selectedPayrollWalletId == null,
                                        onClick = { selectedPayrollWalletId = null },
                                        label = { Text("不关联账户", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = ICostDivider)

                        // 支路 ② 医保个人账户划入
                        PayslipBranchCard(
                            stepBadge = "②",
                            title = "医保个账划入",
                            subtitle = "关联子单 #PAYSLIP_MED · 标签 #医保 #工资条",
                            amountCents = medicalDepositCents,
                            accentColor = Color(0xFF14B8A6),
                            enabled = enableMedicalBranch,
                            onToggle = { enableMedicalBranch = it }
                        ) {
                            if (medicalWallets.isEmpty()) {
                                AutoCreateDedicatedBadge(
                                    kind = DedicatedFundKind.MEDICAL_INSURANCE,
                                    accentColor = Color(0xFF14B8A6)
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    medicalWallets.forEach { w ->
                                        FilterChip(
                                            selected = selectedMedicalWalletId == w.id,
                                            onClick = { selectedMedicalWalletId = w.id },
                                            leadingIcon = { WalletBrandAvatar(wallet = w, size = 18.dp) },
                                            label = { Text("${w.name}（不计净资产）", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = ICostDivider)

                        // 支路 ③ 住房公积金双边缴存
                        val provDetailSub = if (personalProvidentCents > 0L || employerProvidentCents > 0L) {
                            "关联子单 #PAYSLIP_PROV · 个人 ${formatMoneyCents(personalProvidentCents)} + 单位 ${formatMoneyCents(employerProvidentCents)}"
                        } else {
                            "关联子单 #PAYSLIP_PROV · 标签 #公积金 #工资条"
                        }
                        PayslipBranchCard(
                            stepBadge = "③",
                            title = "双边公积金缴存",
                            subtitle = provDetailSub,
                            amountCents = bilateralProvidentCents,
                            accentColor = ICostBlueAccent,
                            enabled = enableProvidentBranch,
                            onToggle = { enableProvidentBranch = it }
                        ) {
                            if (providentWallets.isEmpty()) {
                                AutoCreateDedicatedBadge(
                                    kind = DedicatedFundKind.HOUSING_PROVIDENT,
                                    accentColor = ICostBlueAccent
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    providentWallets.forEach { w ->
                                        FilterChip(
                                            selected = selectedProvidentWalletId == w.id,
                                            onClick = { selectedProvidentWalletId = w.id },
                                            leadingIcon = { WalletBrandAvatar(wallet = w, size = 18.dp) },
                                            label = { Text("${w.name}（不计净资产）", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 底部确认三路原子入账按钮
                item {
                    Button(
                        onClick = {
                            val req = buildCurrentCommitRequest(forceDuplicate = false)
                            scope.launch {
                                isWorking = true
                                workingLabel = "正在写入三路分账流水…"
                                try {
                                    when (val outcome = onCommitPayslip(req)) {
                                        is PayslipCommitOutcome.Success -> {
                                            requestDismiss()
                                        }
                                        is PayslipCommitOutcome.DuplicateDetected -> {
                                            pendingDuplicateInfo = req to outcome
                                        }
                                        is PayslipCommitOutcome.ValidationError -> {
                                            isErrorBanner = true
                                            bannerMessage = outcome.message
                                        }
                                    }
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                        enabled = activeBranchCount > 0 && !paydayFormatError && !isWorking,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        val btnText = if (activeBranchCount > 0) {
                            "确认一步入账（共 $activeBranchCount 路流水 · 支持一键撤销）"
                        } else {
                            "请先导入工资条或填写至少一路入账金额"
                        }
                        Text(btnText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // AC-B5 同月同金额重复导入防重确认弹窗
        pendingDuplicateInfo?.let { (originalReq, dup) ->
            val existDateStr = remember(dup.existingTransaction.timestamp) {
                PayslipImportEngine.formatDateYmd(dup.existingTransaction.timestamp)
            }
            AlertDialog(
                onDismissRequest = { pendingDuplicateInfo = null },
                containerColor = ICostCardWhite,
                title = {
                    Text(
                        text = "检测到本月已导入过相同工资条",
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary
                    )
                },
                text = {
                    Text(
                        text = "当前账本已存在同月同金额工资条记录（发薪日 $existDateStr，防重标识 ${dup.orderKey}）。\n\n为避免账户余额与专项资金重复入账，已自动拦截。若本月确有第二笔同额薪资发放，请点击「确认强制导入」。",
                        fontSize = 13.sp,
                        color = ICostTextSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val forcedReq = originalReq.copy(forceDuplicate = true)
                            pendingDuplicateInfo = null
                            scope.launch {
                                isWorking = true
                                workingLabel = "正在执行强制二次导入…"
                                try {
                                    when (val res = onCommitPayslip(forcedReq)) {
                                        is PayslipCommitOutcome.Success -> requestDismiss()
                                        is PayslipCommitOutcome.ValidationError -> {
                                            isErrorBanner = true
                                            bannerMessage = res.message
                                        }
                                        is PayslipCommitOutcome.DuplicateDetected -> Unit
                                    }
                                } finally {
                                    isWorking = false
                                }
                            }
                        }
                    ) {
                        Text("确认强制导入", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDuplicateInfo = null }) {
                        Text("取消", color = ICostTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    }
}

@Composable
private fun PayslipBranchCard(
    stepBadge: String,
    title: String,
    subtitle: String,
    amountCents: Long,
    accentColor: Color,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    selectorContent: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepBadge,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) ICostTextPrimary else ICostTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "+${formatMoneyCents(amountCents)}",
                            fontFamily = ICDinFontFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled && amountCents > 0L) accentColor else ICostTextMuted
                        )
                    }
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = ICostTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = iCostUnifiedSwitchColors()
            )
        }

        if (enabled) {
            selectorContent()
        }
    }
}

@Composable
private fun AutoCreateDedicatedBadge(
    kind: DedicatedFundKind,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.10f),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.8.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "＋ 未检测到「${kind.defaultName}」，确认入账时将自动新建该专项账户（默认不计入日常净资产）",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accentColor
            )
        }
    }
}
