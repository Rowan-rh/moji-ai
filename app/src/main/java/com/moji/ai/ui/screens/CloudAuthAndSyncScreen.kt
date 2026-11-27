package com.moji.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.BuildConfig
import com.moji.ai.data.backup.MoJiCloudConfig
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.data.backup.MoJiCloudSyncClient.DeviceConnectOutcome
import com.moji.ai.data.backup.MoJiCloudSyncClient.FamilyMemberItemDto
import com.moji.ai.data.model.UserProfileConfig
import com.moji.ai.data.model.UserProfileManager
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.ICostUserAvatar
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudAuthAndSyncScreen(
    mojiCloudConfig: MoJiCloudConfig,
    userProfileConfig: UserProfileConfig,
    statusMessage: String?,
    onBack: () -> Unit,
    onOpenProfileEditor: () -> Unit,
    onSaveNickname: (String) -> Unit,
    onSaveConfig: (MoJiCloudConfig) -> Unit,
    onConnectDeviceSk: (MoJiCloudConfig, Boolean, (Result<DeviceConnectOutcome>) -> Unit) -> Unit,
    onClaimMemberAndRestore: (MoJiCloudConfig, FamilyMemberItemDto, (Boolean, String) -> Unit) -> Unit,
    onDisconnectCloudSync: ((String) -> Unit) -> Unit,
    onUploadCloud: (MoJiCloudConfig, Boolean, (String) -> Unit) -> Unit,
    onRestoreCloud: (MoJiCloudConfig, (String) -> Unit) -> Unit,
    onVerifyLegacySyncKey: (MoJiCloudConfig, (String) -> Unit) -> Unit,
    unsyncedCurrentMonthFamilyCount: Int = 0,
    onToggleFamilySync: (Boolean, (String) -> Unit) -> Unit = { _, _ -> },
    onSyncFamilyNow: ((String) -> Unit) -> Unit = {},
    onSyncCurrentMonthToFamily: ((String) -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val deviceFingerprintHint = remember(context) {
        val fp = MoJiCloudSyncClient.getDeviceFingerprint(context)
        MoJiCloudSyncClient.maskedDeviceFingerprintHint(fp)
    }

    var serverUrlInput by rememberSaveable(mojiCloudConfig.serverUrl) {
        mutableStateOf(mojiCloudConfig.serverUrl)
    }
    var serverSkInput by rememberSaveable(mojiCloudConfig.serverAccessCode) {
        mutableStateOf(mojiCloudConfig.serverAccessCode)
    }
    var memberNicknameInput by rememberSaveable(userProfileConfig.nickname, mojiCloudConfig.memberLabel) {
        val preferred = if (userProfileConfig.nickname.isNotBlank() &&
            userProfileConfig.nickname != UserProfileManager.DEFAULT_NICKNAME
        ) {
            userProfileConfig.nickname
        } else {
            mojiCloudConfig.memberLabel.ifBlank { userProfileConfig.nickname }
        }
        mutableStateOf(preferred)
    }
    var autoSyncEnabled by rememberSaveable(mojiCloudConfig.autoSyncEnabled) {
        mutableStateOf(mojiCloudConfig.autoSyncEnabled)
    }
    var showSkPlainText by rememberSaveable { mutableStateOf(false) }

    var showLegacySection by rememberSaveable(mojiCloudConfig.usesLegacySyncKeyAuth) {
        mutableStateOf(mojiCloudConfig.usesLegacySyncKeyAuth)
    }
    var legacySyncKeyInput by rememberSaveable(mojiCloudConfig.syncKey) {
        mutableStateOf(mojiCloudConfig.syncKey)
    }
    var showLegacyKeyPlain by rememberSaveable { mutableStateOf(false) }

    var isWorking by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var errorScrollTrigger by remember { mutableIntStateOf(0) }
    var showSyncMonthFamilyConfirmDialog by remember { mutableStateOf(false) }

    fun updateFeedback(msg: String?, isError: Boolean) {
        feedbackIsError = isError
        feedbackBanner = msg
        if (isError && !msg.isNullOrBlank()) {
            errorScrollTrigger++
        }
    }

    LaunchedEffect(statusMessage) {
        val ext = statusMessage
        if (!ext.isNullOrBlank() && (ext.contains("失败") || ext.contains("错误") || ext.contains("无效"))) {
            errorScrollTrigger++
        }
    }

    LaunchedEffect(errorScrollTrigger) {
        if (errorScrollTrigger > 0) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    var memberSelectionCandidates by remember { mutableStateOf<List<FamilyMemberItemDto>>(emptyList()) }
    var showMemberSelectionSheet by remember { mutableStateOf(false) }
    var pendingClaimTarget by remember { mutableStateOf<FamilyMemberItemDto?>(null) }

    var showPullRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showReconnectPullPromptDialog by remember { mutableStateOf(false) }
    var showForceUploadConfirmDialog by remember { mutableStateOf(false) }
    var forceUploadPromptReason by remember { mutableStateOf("") }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userProfileConfig.nickname) {
        if (userProfileConfig.nickname.isNotBlank()) {
            memberNicknameInput = userProfileConfig.nickname
        }
    }

    fun buildDeviceSkDraft(): MoJiCloudConfig {
        val normalizedNick = UserProfileManager.normalizeNickname(memberNicknameInput)
        return mojiCloudConfig.copy(
            authMode = MoJiCloudSyncClient.AUTH_MODE_DEVICE_SK,
            serverUrl = serverUrlInput.trim(),
            serverAccessCode = serverSkInput.trim(),
            memberLabel = normalizedNick,
            autoSyncEnabled = autoSyncEnabled
        )
    }

    fun buildActiveSyncDraft(): MoJiCloudConfig {
        val normalizedNick = UserProfileManager.normalizeNickname(memberNicknameInput)
        return if (mojiCloudConfig.usesLegacySyncKeyAuth && serverSkInput.isBlank()) {
            mojiCloudConfig.copy(
                authMode = MoJiCloudSyncClient.AUTH_MODE_SYNC_KEY,
                serverUrl = serverUrlInput.trim(),
                syncKey = legacySyncKeyInput.trim(),
                memberLabel = normalizedNick,
                autoSyncEnabled = autoSyncEnabled
            )
        } else {
            buildDeviceSkDraft()
        }
    }

    val activeDraft = buildActiveSyncDraft()
    val isBound = mojiCloudConfig.isTargetBound
    val isDraftMatchingBound = activeDraft.isTargetBound
    val needsInitialPull = isBound &&
        mojiCloudConfig.remoteRevision > 0L &&
        mojiCloudConfig.effectiveLastSyncedRevision != mojiCloudConfig.remoteRevision

    BackHandler(enabled = !showMemberSelectionSheet && !isWorking, onBack = onBack)

    fun triggerConnectFlow(createIfMissing: Boolean) {
        val normalizedNick = UserProfileManager.normalizeNickname(memberNicknameInput)
        memberNicknameInput = normalizedNick
        onSaveNickname(normalizedNick)

        val draft = buildDeviceSkDraft()
        isWorking = true
        updateFeedback(
            if (createIfMissing) {
                "正在创建独立家庭成员云账本…"
            } else {
                "正在校验服务器 SK 与本机设备指纹…"
            },
            isError = false
        )
        onConnectDeviceSk(draft, createIfMissing) { res ->
            isWorking = false
            res.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is DeviceConnectOutcome.Bound -> {
                            showMemberSelectionSheet = false
                            memberSelectionCandidates = emptyList()
                            val cfg = outcome.config
                            if (cfg.remoteRevision > 0L && cfg.effectiveLastSyncedRevision == 0L) {
                                updateFeedback(
                                    "已识别原有云账本（云端 v${cfg.remoteRevision}，共 ${cfg.remoteTxCount} 笔账单），请先从云端拉取恢复。",
                                    isError = false
                                )
                                showReconnectPullPromptDialog = true
                            } else if (cfg.remoteRevision > 0L) {
                                updateFeedback(
                                    "云同步已连接（云端 v${cfg.remoteRevision} · ${cfg.remoteTxCount} 笔账单）",
                                    isError = false
                                )
                            } else {
                                updateFeedback("云同步连接成功！已就绪，可将本机账本推送上云。", isError = false)
                            }
                        }
                        is DeviceConnectOutcome.SelectionRequired -> {
                            memberSelectionCandidates = outcome.members
                            showMemberSelectionSheet = true
                            updateFeedback(
                                "服务器验证通过：检测到已有 ${outcome.members.size} 位家庭成员账本，请选择认领或新建。",
                                isError = false
                            )
                        }
                    }
                },
                onFailure = { err ->
                    updateFeedback(err.message ?: "连接失败，请检查服务器地址与 SK", isError = true)
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ICostCircleIconButton(
                        onClick = { if (!isWorking) onBack() },
                        size = 42.dp
                    ) {
                        ICostAssetImage(
                            assetFile = "record_back_icon.webp",
                            tint = ICostTextPrimary,
                            contentDescription = "返回",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "默记云同步",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary
                    )
                }

                val badgeBg = when {
                    needsInitialPull -> ICostAmberGold.copy(alpha = 0.15f)
                    isBound -> ICostGreenIncome.copy(alpha = 0.14f)
                    else -> ICostBlueAccent.copy(alpha = 0.12f)
                }
                val badgeFg = when {
                    needsInitialPull -> ICostAmberGold
                    isBound -> ICostGreenIncome
                    else -> ICostBlueAccent
                }
                val badgeLabel = when {
                    needsInitialPull -> "待拉取 v${mojiCloudConfig.remoteRevision}"
                    isBound && mojiCloudConfig.usesLegacySyncKeyAuth -> "旧版密钥已连"
                    isBound && mojiCloudConfig.remoteRevision > 0L -> "已连接 · v${mojiCloudConfig.remoteRevision}"
                    isBound -> "已连接云端"
                    else -> "未连接"
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = badgeBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isBound && !needsInitialPull) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = badgeFg,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = badgeLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 36.dp)
            ) {
                // 0. 顶部状态反馈横幅（报错时自动滚动至 item 0，确保大字号或长页面下立即可见）
                val bannerText = feedbackBanner ?: statusMessage
                if (!bannerText.isNullOrBlank()) {
                    item {
                        val bannerError = feedbackIsError ||
                            bannerText.contains("失败") ||
                            bannerText.contains("错误") ||
                            bannerText.contains("无效")
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (bannerError) {
                                ICostCoralRed.copy(alpha = 0.12f)
                            } else {
                                ICostBlueAccent.copy(alpha = 0.10f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = bannerText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (bannerError) ICostCoralRed else ICostTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                }

                // 1. 顶部个人身份、设备指纹与云端摘要卡
                item {
                    ICostFormCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isWorking, onClick = onOpenProfileEditor),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(56.dp)) {
                                ICostUserAvatar(
                                    profile = userProfileConfig,
                                    size = 54.dp,
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(19.dp)
                                        .align(Alignment.BottomEnd)
                                        .clip(CircleShape)
                                        .background(ICostBlueAccent)
                                        .border(1.5.dp, ICostCardWhite, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "编辑头像与昵称",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = userProfileConfig.nickname,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ICostTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (BuildConfig.IS_QA_BUILD) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ICostBlueAccent.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = "QA 隔离环境",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostBlueAccent,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "本机指纹：$deviceFingerprintHint · 支持头像与账本同步",
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = ICostTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (isBound) {
                            HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)
                            val syncTimeStr = remember(mojiCloudConfig.lastSyncTimeMillis) {
                                if (mojiCloudConfig.lastSyncTimeMillis > 0L) {
                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(mojiCloudConfig.lastSyncTimeMillis))
                                } else {
                                    "尚未传输"
                                }
                            }
                            val avatarSyncBadge = when {
                                mojiCloudConfig.remoteAvatarSha256.isNotBlank() -> "自定义头像已上云"
                                userProfileConfig.hasCustomAvatarConfigured -> "自定义头像待同步"
                                else -> "预设插画头像"
                            }
                            Text(
                                text = "云端摘要：v${mojiCloudConfig.remoteRevision} · ${mojiCloudConfig.remoteTxCount} 笔账单 · " +
                                    "${mojiCloudConfig.remoteWalletCount} 个账户 · ${mojiCloudConfig.remoteScreenshotCount} 张原图 · " +
                                    "$avatarSyncBadge（上次同步：$syncTimeStr）",
                                fontSize = 11.sp,
                                color = ICostTextSecondary
                            )
                        }

                        if (needsInitialPull) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ICostAmberGold.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "防误覆盖保护中：云端存在 v${mojiCloudConfig.remoteRevision} 账本（本机同步基线 v${mojiCloudConfig.effectiveLastSyncedRevision}），后台自动推送已暂停。请先点击下方「从云端拉取恢复」对齐账本。",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }

                // 2. 主配置表单：URL + 服务器 SK + 成员昵称
                item {
                    ICostFormCard {
                        Text(
                            text = "免密连接配置（URL + 服务器 SK）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextPrimary
                        )
                        Text(
                            text = "无需注册账号密码：填入自建服务器地址与服务端密钥 SK，手机自动凭硬件指纹免密识别身份；换新手机时点选你的成员昵称即可一键接管恢复。",
                            fontSize = 11.sp,
                            color = ICostTextSecondary
                        )

                        ICostFormField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = "云服务器地址",
                            placeholder = "https://sync.your-domain.com",
                            singleLine = true,
                            supportingText = "公网域名/IP 强制要求 HTTPS 加密传输（仅本地回环调试允许 HTTP）",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )

                        ICostFormField(
                            value = serverSkInput,
                            onValueChange = { serverSkInput = it },
                            label = "服务器密钥 SK（必填）",
                            placeholder = "对应服务端 .env 中的 MOJI_SERVER_ACCESS_CODE",
                            singleLine = true,
                            visualTransformation = if (showSkPlainText) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingContent = {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ICostCardWhite,
                                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                                        showSkPlainText = !showSkPlainText
                                    }
                                ) {
                                    Text(
                                        text = if (showSkPlainText) "隐藏" else "显示",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostBlueAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ICostFormField(
                            value = memberNicknameInput,
                            onValueChange = { raw ->
                                val cpCount = raw.codePointCount(0, raw.length)
                                memberNicknameInput = if (cpCount <= UserProfileManager.MAX_NICKNAME_LENGTH) {
                                    raw
                                } else {
                                    val endIdx = raw.offsetByCodePoints(0, UserProfileManager.MAX_NICKNAME_LENGTH)
                                    raw.substring(0, endIdx)
                                }
                            },
                            label = "成员昵称（用于在家庭成员列表中区分）",
                            placeholder = UserProfileManager.DEFAULT_NICKNAME,
                            singleLine = true,
                            supportingText = "与个人资料昵称联动；家人共享同一台服务器时将按昵称区分各自独立账本",
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 家人配对口令快捷复制/粘贴
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ICostCellGray,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .iCostPressScale(pressedScale = 0.95f) {
                                        MoJiCloudSyncClient.encodeFamilyPairingText(
                                            serverUrl = serverUrlInput,
                                            serverAccessCode = serverSkInput
                                        ).fold(
                                            onSuccess = { token ->
                                                if (serverSkInput.isBlank()) {
                                                    updateFeedback("请先填写服务器密钥 SK 再复制家人配对口令", isError = true)
                                                } else {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cm.setPrimaryClip(ClipData.newPlainText("moji_family_pair", token))
                                                    updateFeedback("已复制家人配对口令（含服务器地址与 SK），发给家人粘贴即可连接", isError = false)
                                                }
                                            },
                                            onFailure = { e ->
                                                updateFeedback(e.message ?: "生成配对口令失败", isError = true)
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "复制家人配对口令",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ICostBlueAccent,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ICostCellGray,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .iCostPressScale(pressedScale = 0.95f) {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val rawText = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        MoJiCloudSyncClient.decodeFamilyPairingText(rawText).fold(
                                            onSuccess = { (parsedUrl, parsedCode) ->
                                                serverUrlInput = parsedUrl
                                                if (parsedCode.isNotBlank()) {
                                                    serverSkInput = parsedCode
                                                }
                                                updateFeedback(
                                                    if (parsedCode.isNotBlank()) {
                                                        "已填入服务器地址与密钥 SK，请点击下方连接按钮"
                                                    } else {
                                                        "已填入服务器地址，请补充服务器密钥 SK"
                                                    },
                                                    isError = false
                                                )
                                            },
                                            onFailure = { e ->
                                                updateFeedback(e.message ?: "剪贴板未识别到有效的配对口令", isError = true)
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "粘贴家人配对口令",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ICostTextPrimary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }

                        val connectBtnLabel = when {
                            isWorking -> "正在连接验证…"
                            isBound && !mojiCloudConfig.usesLegacySyncKeyAuth && isDraftMatchingBound -> "重新验证连接"
                            isBound && mojiCloudConfig.usesLegacySyncKeyAuth -> "升级切换为免密指纹连接"
                            else -> "连接云服务器"
                        }

                        Button(
                            enabled = !isWorking && serverUrlInput.isNotBlank(),
                            onClick = { triggerConnectFlow(createIfMissing = false) },
                            colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                            shape = RoundedCornerShape(15.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "连接云服务器" }
                        ) {
                            Text(
                                text = connectBtnLabel,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 2.5 已绑定时的「🏠 家庭共享账本」卡片
                if (isBound) {
                    item {
                        ICostFormCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🏠 家庭共享账本",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (mojiCloudConfig.familySyncEnabled) {
                                        ICostGreenIncome.copy(alpha = 0.14f)
                                    } else {
                                        ICostCellGray
                                    }
                                ) {
                                    Text(
                                        text = if (mojiCloudConfig.familySyncEnabled) "自动共享已开启" else "仅手动共享",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (mojiCloudConfig.familySyncEnabled) ICostGreenIncome else ICostTextSecondary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = "「个人管资产，家庭看收支」：开启后新记的日常支出/收入会自动共享至家庭账本；个人账户余额、转账还款、借贷、工资条医保/公积金及识屏原图 100% 留在本机，绝不混用。",
                                fontSize = 11.sp,
                                color = ICostTextSecondary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = "新记日常收支自动共享给家人",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ICostTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "首次开启不会自动上传过往历史账单；单笔账单可在记账面板或详情页随时设为「仅个人」",
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Switch(
                                    checked = mojiCloudConfig.familySyncEnabled,
                                    enabled = !isWorking && isDraftMatchingBound,
                                    onCheckedChange = { checked ->
                                        isWorking = true
                                        onToggleFamilySync(checked) { msg ->
                                            isWorking = false
                                            updateFeedback(
                                                msg,
                                                isError = msg.contains("失败") || msg.contains("错误")
                                            )
                                        }
                                    },
                                    colors = iCostUnifiedSwitchColors()
                                )
                            }

                            if (mojiCloudConfig.familyMembers.isNotEmpty()) {
                                HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)
                                Text(
                                    text = "当前家庭服务器成员（共 ${mojiCloudConfig.familyMembers.size} 人 · 增量游标 #${mojiCloudConfig.familySyncCursor}）",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    mojiCloudConfig.familyMembers.forEach { m ->
                                        val isMe = m.userId == mojiCloudConfig.userId
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isMe) ICostBlueAccent.copy(alpha = 0.12f) else ICostCellGray
                                        ) {
                                            Text(
                                                text = if (isMe) {
                                                    "${m.displayName.ifBlank { "我" }}（本机）"
                                                } else {
                                                    m.displayName.ifBlank { "家人" }
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isMe) ICostBlueAccent else ICostTextPrimary,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    enabled = !isWorking && isDraftMatchingBound,
                                    onClick = {
                                        isWorking = true
                                        updateFeedback("正在与云端同步家庭共享账本…", isError = false)
                                        onSyncFamilyNow { msg ->
                                            isWorking = false
                                            updateFeedback(
                                                msg,
                                                isError = msg.contains("失败") || msg.contains("错误")
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                ) {
                                    Text(
                                        text = "刷新家庭账本",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = ICostBlueAccent,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                OutlinedButton(
                                    enabled = !isWorking && isDraftMatchingBound && unsyncedCurrentMonthFamilyCount > 0,
                                    onClick = { showSyncMonthFamilyConfirmDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .heightIn(min = 46.dp)
                                ) {
                                    Text(
                                        text = if (unsyncedCurrentMonthFamilyCount > 0) {
                                            "一键同步本月已有出入账（$unsyncedCurrentMonthFamilyCount 笔）"
                                        } else {
                                            "本月出入账已全部同步"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (unsyncedCurrentMonthFamilyCount > 0) ICostBlueAccent else ICostTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 已绑定时的日常同步操作与安全断开卡
                if (isBound) {
                    item {
                        ICostFormCard {
                            Text(
                                text = "同步操作与后台自动备份",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    enabled = !isWorking && isDraftMatchingBound,
                                    onClick = {
                                        val draft = buildActiveSyncDraft()
                                        isWorking = true
                                        updateFeedback("正在同步账本、识屏原图与自定义头像到云端…", isError = false)
                                        onUploadCloud(draft, false) { msg ->
                                            isWorking = false
                                            if (msg.contains("冲突") || msg.contains("拒绝空账本")) {
                                                forceUploadPromptReason = msg
                                                showForceUploadConfirmDialog = true
                                            } else {
                                                updateFeedback(
                                                    msg,
                                                    isError = msg.contains("失败") || msg.contains("错误") || msg.contains("无效")
                                                )
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                ) {
                                    Text(
                                        text = "立即同步上云",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                OutlinedButton(
                                    enabled = !isWorking && isDraftMatchingBound,
                                    onClick = { showPullRestoreConfirmDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                ) {
                                    Text(
                                        text = "从云端拉取恢复",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = ICostBlueAccent,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            if (!isDraftMatchingBound) {
                                Text(
                                    text = "提示：你修改了上方服务器地址或密钥草稿，请先点击「连接云服务器」验证生效后再执行同步。",
                                    fontSize = 11.sp,
                                    color = ICostAmberGold
                                )
                            }

                            HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "记账后后台自动同步上云",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ICostTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "日常记账、改分类或更换头像 6 秒后静默上云（版本未对齐前自动暂停）",
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Switch(
                                    checked = autoSyncEnabled,
                                    onCheckedChange = { checked ->
                                        autoSyncEnabled = checked
                                        onSaveConfig(mojiCloudConfig.copy(autoSyncEnabled = checked))
                                    },
                                    colors = iCostUnifiedSwitchColors()
                                )
                            }

                            HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ICostCoralRed.copy(alpha = 0.10f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "断开云同步连接" }
                                    .iCostPressScale(pressedScale = 0.96f) {
                                        if (!isWorking) {
                                            showDisconnectConfirmDialog = true
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "断开云同步连接（保留本机全部数据）",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostCoralRed
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. 底部可折叠「高级 / 旧版专属密钥兼容」区
                item {
                    ICostFormCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLegacySection = !showLegacySection },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "高级 / 旧版专属密钥兼容",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary
                                )
                                Text(
                                    text = if (mojiCloudConfig.usesLegacySyncKeyAuth) {
                                        "当前正使用旧版专属密钥模式，可在上方填入 SK 无损升级至设备指纹免密模式"
                                    } else {
                                        "曾使用旧版 ${MoJiCloudSyncClient.expectedKeyPrefix}XXXX 密钥的用户可在此凭旧密钥找回"
                                    },
                                    fontSize = 11.sp,
                                    color = ICostTextMuted
                                )
                            }
                            Icon(
                                imageVector = if (showLegacySection) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = "展开或收起旧版密钥兼容区",
                                tint = ICostTextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        AnimatedVisibility(visible = showLegacySection) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)
                                ICostFormField(
                                    value = legacySyncKeyInput,
                                    onValueChange = { legacySyncKeyInput = it.uppercase(Locale.ROOT) },
                                    label = "旧版专属同步密钥（Sync Key）",
                                    placeholder = "${MoJiCloudSyncClient.expectedKeyPrefix}XXXX-XXXX-XXXX-XXXX",
                                    singleLine = true,
                                    visualTransformation = if (showLegacyKeyPlain) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingContent = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = ICostCardWhite,
                                            modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                                                showLegacyKeyPlain = !showLegacyKeyPlain
                                            }
                                        ) {
                                            Text(
                                                text = if (showLegacyKeyPlain) "隐藏" else "显示",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostBlueAccent,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedButton(
                                    enabled = !isWorking && serverUrlInput.isNotBlank() && legacySyncKeyInput.isNotBlank(),
                                    onClick = {
                                        val legacyDraft = mojiCloudConfig.copy(
                                            authMode = MoJiCloudSyncClient.AUTH_MODE_SYNC_KEY,
                                            serverUrl = serverUrlInput.trim(),
                                            syncKey = legacySyncKeyInput.trim(),
                                            serverAccessCode = serverSkInput.trim(),
                                            memberLabel = UserProfileManager.normalizeNickname(memberNicknameInput),
                                            autoSyncEnabled = autoSyncEnabled
                                        )
                                        isWorking = true
                                        updateFeedback("正在验证旧版专属密钥…", isError = false)
                                        onVerifyLegacySyncKey(legacyDraft) { msg ->
                                            isWorking = false
                                            updateFeedback(
                                                msg,
                                                isError = msg.contains("失败") || msg.contains("无效") || msg.contains("错误")
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                ) {
                                    Text(
                                        text = "使用旧版专属密钥验证连接",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 换新机检测到已有成员列表选择抽屉（selection_required）
        ICostSmoothBottomSheet(
            visible = showMemberSelectionSheet,
            onDismissRequest = {
                if (!isWorking) {
                    showMemberSelectionSheet = false
                }
            }
        ) { requestDismiss ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "检测到家庭服务器已有成员账本",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ICostTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "换新机请点选你的原有账本认领恢复；新成员请创建独立账本",
                            fontSize = 12.sp,
                            color = ICostTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ICostCircleIconButton(
                        onClick = { if (!isWorking) requestDismiss() },
                        size = 34.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = ICostTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(memberSelectionCandidates, key = { it.userId }) { member ->
                        val updatedText = remember(member.updatedAtMs) {
                            if (member.updatedAtMs > 0L) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(member.updatedAtMs))
                            } else {
                                "尚无快照时间"
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = ICostCardWhite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .iCostPressScale(pressedScale = 0.97f) {
                                    if (!isWorking) {
                                        pendingClaimTarget = member
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(ICostBlueAccent.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = ICostBlueAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = member.displayName.ifBlank { "家庭成员" },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ICostTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (member.hasAvatar) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = ICostGreenIncome.copy(alpha = 0.13f)
                                            ) {
                                                Text(
                                                    text = "含自定义头像",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ICostGreenIncome,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${member.txCount} 笔账单 · ${member.walletCount} 个账户 · ${member.screenshotCount} 张原图 · v${member.latestRevision}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ICostTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val deviceDesc = member.lastDeviceName.ifBlank { "历史设备" }
                                    Text(
                                        text = "最近同步：$deviceDesc · $updatedText",
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = ICostBlueAccent.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "认领接管",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostBlueAccent,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            enabled = !isWorking,
                            onClick = {
                                showMemberSelectionSheet = false
                                triggerConnectFlow(createIfMissing = true)
                            },
                            shape = RoundedCornerShape(15.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "我是新家人，创建独立账本" }
                        ) {
                            Text(
                                text = "我是新家人，创建独立账本",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // 二次确认：认领接管已有家庭成员账本并立即拉取恢复
    val claimCandidate = pendingClaimTarget
    if (claimCandidate != null && !isWorking) {
        val displayTitle = claimCandidate.displayName.ifBlank { "该成员" }
        AlertDialog(
            onDismissRequest = { pendingClaimTarget = null },
            containerColor = ICostCardWhite,
            title = {
                Text("确认接管「$displayTitle」的账本？", fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Text(
                    buildString {
                        append("确认后将把这部手机绑定至「$displayTitle」名下")
                        if (claimCandidate.latestRevision > 0L) {
                            append("，并立即从云端拉取恢复全部账本、${claimCandidate.txCount} 笔账单、识屏原图与个人头像（本机当前数据将被云端账本替换）。")
                        } else {
                            append("（该成员云端暂未上传快照，绑定后本机数据不会丢失，可直接同步上云）。")
                        }
                        append("\n\n若你是其他家庭成员，请点取消并选择「我是新家人，创建独立账本」。")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = claimCandidate
                        pendingClaimTarget = null
                        showMemberSelectionSheet = false
                        val draft = buildDeviceSkDraft()
                        isWorking = true
                        updateFeedback("正在接管「$displayTitle」并拉取恢复账本、原图与头像…", isError = false)
                        onClaimMemberAndRestore(draft, target) { ok, msg ->
                            isWorking = false
                            updateFeedback(msg, isError = !ok)
                        }
                    }
                ) {
                    Text("确认接管并恢复", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClaimTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSyncMonthFamilyConfirmDialog && !isWorking) {
        AlertDialog(
            onDismissRequest = { showSyncMonthFamilyConfirmDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("同步本月已有出入账？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "将把本机本月尚未共享的 $unsyncedCurrentMonthFamilyCount 笔日常支出/收入脱敏摘要同步至家庭账本。\n\n内部转账、信用卡还款、借入借出、余额平账及医保个账拆分会被自动排除，个人账户余额不受任何影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncMonthFamilyConfirmDialog = false
                        isWorking = true
                        updateFeedback("正在标记并同步本月日常出入账至家庭账本…", isError = false)
                        onSyncCurrentMonthToFamily { msg ->
                            isWorking = false
                            updateFeedback(
                                msg,
                                isError = msg.contains("失败") || msg.contains("错误")
                            )
                        }
                    }
                ) {
                    Text("确认同步", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncMonthFamilyConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 同机重装免密识别到云端已有账本时，引导立即拉取恢复
    if (showReconnectPullPromptDialog && !isWorking) {
        AlertDialog(
            onDismissRequest = { showReconnectPullPromptDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("已认出你的云端账本", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "本机设备指纹已自动匹配原云端账号（云端版本 v${mojiCloudConfig.remoteRevision}，共 ${mojiCloudConfig.remoteTxCount} 笔账单、${mojiCloudConfig.remoteScreenshotCount} 张原图）。\n\n是否立即从云端拉取恢复完整账本与头像？（拉取前后台自动上传已锁定暂停，绝不会用空账本覆盖云端）"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReconnectPullPromptDialog = false
                        val draft = buildActiveSyncDraft()
                        isWorking = true
                        updateFeedback("正在从云端拉取恢复完整账本、原图与头像…", isError = false)
                        onRestoreCloud(draft) { msg ->
                            isWorking = false
                            updateFeedback(
                                msg,
                                isError = msg.contains("失败") || msg.contains("错误")
                            )
                        }
                    }
                ) {
                    Text("立即拉取恢复", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReconnectPullPromptDialog = false }) {
                    Text("稍后手动拉取")
                }
            }
        )
    }

    // 手动点击「从云端拉取恢复」二次确认
    if (showPullRestoreConfirmDialog && !isWorking) {
        AlertDialog(
            onDismissRequest = { showPullRestoreConfirmDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("从云端拉取并覆盖本机？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("将从云服务器拉取最新备份快照、识屏账单原图与自定义头像，本机当前账户与账单将被云端版本替换。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPullRestoreConfirmDialog = false
                        val draft = buildActiveSyncDraft()
                        isWorking = true
                        updateFeedback("正在从默记云拉取恢复账本、原图与头像…", isError = false)
                        onRestoreCloud(draft) { msg ->
                            isWorking = false
                            updateFeedback(
                                msg,
                                isError = msg.contains("失败") || msg.contains("错误")
                            )
                        }
                    }
                ) {
                    Text("确认拉取恢复", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPullRestoreConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 版本冲突时强制上传覆盖二次确认
    if (showForceUploadConfirmDialog && !isWorking) {
        AlertDialog(
            onDismissRequest = { showForceUploadConfirmDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("以本机数据覆盖云端？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("$forceUploadPromptReason\n\n若确认本机账本才是最新的，可强制上传覆盖云端（服务器会自动保留旧版本历史归档）。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceUploadConfirmDialog = false
                        val draft = buildActiveSyncDraft()
                        isWorking = true
                        updateFeedback("正在以本机数据强制同步覆盖云端…", isError = false)
                        onUploadCloud(draft, true) { msg ->
                            isWorking = false
                            updateFeedback(
                                msg,
                                isError = msg.contains("失败") || msg.contains("错误")
                            )
                        }
                    }
                ) {
                    Text("以本机覆盖云端", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceUploadConfirmDialog = false }) {
                    Text("取消（先拉取云端）")
                }
            }
        )
    }

    // 二次确认：断开云同步连接（100% 保留本机数据）
    if (showDisconnectConfirmDialog && !isWorking) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("断开云同步连接？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("断开后将清除本机保存的云端会话与服务器密钥 SK，并停止后台自动同步。\n\n本机现有的全部账本、资产账户、账单明细、识屏原图与个人头像将 100% 完整保留；日后在本机重新填入服务器地址与 SK 仍可无感重连原账本。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        serverSkInput = ""
                        isWorking = true
                        onDisconnectCloudSync { msg ->
                            isWorking = false
                            updateFeedback(msg, isError = false)
                        }
                    }
                ) {
                    Text("确认断开连接", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
