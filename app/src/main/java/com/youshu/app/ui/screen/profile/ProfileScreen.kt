package com.youshu.app.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.ui.components.AppDecorativeBackground
import com.youshu.app.ui.components.AppDialog
import com.youshu.app.ui.components.AppSurfaceCard
import com.youshu.app.ui.theme.PurpleEnd
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.ui.theme.StatusExpired
import com.youshu.app.ui.theme.TextHint
import com.youshu.app.ui.theme.TextPrimary
import com.youshu.app.ui.theme.TextSecondary
import com.youshu.app.ui.viewmodel.ProfileViewModel
import com.youshu.app.util.DateUtil

@Composable
fun ProfileScreen(
    onOpenExpiry: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val totalCount by viewModel.totalCount.collectAsState()
    val expiringCount by viewModel.expiringCount.collectAsState()
    val totalValue by viewModel.totalValue.collectAsState()
    val trashCount by viewModel.trashCount.collectAsState()
    val models by viewModel.aiModels.collectAsState()

    var showModelDialog by remember { mutableStateOf(false) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogMessage by remember { mutableStateOf<String?>(null) }
    var newAlias by remember { mutableStateOf("") }
    var newProvider by remember { mutableStateOf("") }
    var newEndpoint by remember { mutableStateOf("") }
    var newModelName by remember { mutableStateOf("") }
    var newPurpose by remember { mutableStateOf(AiModelConfig.PURPOSE_TEXT_SEARCH) }
    var newApiKey by remember { mutableStateOf("") }
    var editingModelId by remember { mutableStateOf<Long?>(null) }

    Box {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PurpleStart, PurpleEnd)
                        )
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .align(Alignment.TopEnd)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "东",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column {
                            Text(
                                text = "东西不跑用户",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "记录生活中的每一件物品",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CompactStat(label = "物品总数", value = totalCount.toString())
                    CompactStat(label = "即将过期", value = expiringCount.toString(), color = StatusExpired)
                    CompactStat(label = "物品价值", value = DateUtil.formatCurrency(totalValue))
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "API-Key 管理系统",
                    subtitle = "管理天气、AI 等第三方服务的 API Key",
                    onClick = { showModelDialog = true }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Notifications,
                    title = "到期提醒",
                    subtitle = "查看即将到期的物品与提醒状态",
                    onClick = onOpenExpiry
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.History,
                    title = "回收站",
                    subtitle = if (trashCount > 0) "当前有 $trashCount 项可在 30 天内恢复" else "30 天内可恢复最近删除的物品",
                    onClick = onOpenTrash
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Settings,
                    title = "设置",
                    subtitle = "备份数据、检查更新与偏好设置",
                    onClick = onOpenSettings
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.AutoMirrored.Filled.Help,
                    title = "帮助与反馈",
                    subtitle = "常见问题与功能建议",
                    onClick = {
                        infoDialogTitle = "帮助与反馈"
                        infoDialogMessage = "后续会补充常见问题、使用说明和反馈渠道。"
                    }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Info,
                    title = "关于我们",
                    subtitle = "版本 1.1.0",
                    onClick = {
                        infoDialogTitle = "关于我们"
                        infoDialogMessage = "东西不跑：拍一下、存一下，东西永远不跑。\n开源地址：https://github.com/LZY426915/don-tRun"
                    }
                )
            }

            Text(
                text = "东西不跑，永远不丢",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showModelDialog) {
        AppDialog(
            title = "API-Key 管理系统",
            subtitle = "管理各服务的连接信息与 API Key。",
            onDismissRequest = { showModelDialog = false },
            confirmText = "新增模型",
            onConfirm = {
                editingModelId = null
                newAlias = ""
                newProvider = ""
                newEndpoint = ""
                newModelName = ""
                newPurpose = AiModelConfig.PURPOSE_TEXT_SEARCH
                newApiKey = ""
                showAddModelDialog = true
            }
        ) {
            if (models.isEmpty()) {
                Text(
                    text = "还没有添加模型。",
                    fontSize = 14.sp,
                    color = TextHint
                )
            } else {
                models.forEach { model ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.alias,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${model.provider} · ${model.endpoint}",
                                fontSize = 12.sp,
                                color = TextHint
                            )
                            Text(
                                text = "${AiModelConfig.purposeLabel(model.purpose)} · ${model.modelName}",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = if (model.apiKey.isBlank()) "未配置 API Key" else "API Key: ${model.apiKey.take(4)}••••${model.apiKey.takeLast(2)}",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(PurpleStart.copy(alpha = 0.12f))
                                .clickable {
                                    editingModelId = model.id
                                    newAlias = model.alias
                                    newProvider = model.provider
                                    newEndpoint = model.endpoint
                                    newModelName = model.modelName
                                    newPurpose = model.purpose
                                    newApiKey = model.apiKey
                                    showAddModelDialog = true
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "编辑",
                                color = PurpleStart,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(StatusExpired.copy(alpha = 0.12f))
                                .clickable { viewModel.deleteAiModel(model.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "移除",
                                color = StatusExpired,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddModelDialog) {
        AiModelEditorDialog(
            title = if (editingModelId == null) "新增 API-Key" else "编辑 API-Key",
            confirmText = if (editingModelId == null) "保存" else "更新",
            alias = newAlias,
            provider = newProvider,
            endpoint = newEndpoint,
            modelName = newModelName,
            purpose = newPurpose,
            apiKey = newApiKey,
            onAliasChange = { newAlias = it },
            onProviderChange = { newProvider = it },
            onEndpointChange = { newEndpoint = it },
            onModelNameChange = { newModelName = it },
            onPurposeChange = { newPurpose = it },
            onApiKeyChange = { newApiKey = it },
            onDismissRequest = {
                showAddModelDialog = false
                editingModelId = null
            },
            onConfirm = {
                val modelId = editingModelId
                if (modelId == null) {
                    viewModel.addAiModel(
                        alias = newAlias,
                        provider = newProvider,
                        endpoint = newEndpoint,
                        modelName = newModelName,
                        purpose = newPurpose,
                        apiKey = newApiKey
                    )
                } else {
                    viewModel.updateAiModel(
                        id = modelId,
                        alias = newAlias,
                        provider = newProvider,
                        endpoint = newEndpoint,
                        modelName = newModelName,
                        purpose = newPurpose,
                        apiKey = newApiKey
                    )
                }
                showAddModelDialog = false
                editingModelId = null
            }
        )
    }

    if (infoDialogTitle != null && infoDialogMessage != null) {
        AppDialog(
            title = infoDialogTitle!!,
            onDismissRequest = {
                infoDialogTitle = null
                infoDialogMessage = null
            },
            confirmText = "知道了",
            onConfirm = {
                infoDialogTitle = null
                infoDialogMessage = null
            }
        ) {
            Text(
                text = infoDialogMessage!!,
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun CompactStat(
    label: String,
    value: String,
    color: Color = PurpleStart
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PurpleStart.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PurpleStart,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextHint
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextHint
        )
    }
}

@Composable
private fun DividerSpacer() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color(0xFFF1EEF7))
            .height(1.dp)
    )
}

@Composable
private fun AiModelEditorDialog(
    title: String,
    confirmText: String,
    alias: String,
    provider: String,
    endpoint: String,
    modelName: String,
    purpose: String,
    apiKey: String,
    onAliasChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onPurposeChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AppDialog(
        title = title,
        subtitle = "先把必要连接信息录入，后续再接真实调用。",
        onDismissRequest = onDismissRequest,
        confirmText = confirmText,
        confirmEnabled = alias.isNotBlank() &&
            provider.isNotBlank() &&
            endpoint.isNotBlank() &&
            modelName.isNotBlank(),
        onConfirm = onConfirm
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PurposeChip(
                text = "文字搜索",
                selected = purpose == AiModelConfig.PURPOSE_TEXT_SEARCH,
                onClick = { onPurposeChange(AiModelConfig.PURPOSE_TEXT_SEARCH) },
                modifier = Modifier.weight(1f)
            )
            PurposeChip(
                text = "图片识别",
                selected = purpose == AiModelConfig.PURPOSE_IMAGE_RECOGNITION,
                onClick = { onPurposeChange(AiModelConfig.PURPOSE_IMAGE_RECOGNITION) },
                modifier = Modifier.weight(1f)
            )
            PurposeChip(
                text = "天气服务",
                selected = purpose == AiModelConfig.PURPOSE_WEATHER,
                onClick = { onPurposeChange(AiModelConfig.PURPOSE_WEATHER) },
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = alias,
            onValueChange = onAliasChange,
            singleLine = true,
            label = { Text("模型别名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = provider,
            onValueChange = onProviderChange,
            singleLine = true,
            label = { Text("模型来源") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            singleLine = true,
            label = { Text("接口地址") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = modelName,
            onValueChange = onModelNameChange,
            singleLine = true,
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            singleLine = true,
            label = { Text("API Key") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = TextHint
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PurposeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) PurpleStart.copy(alpha = 0.16f) else Color(0xFFF8F6FC))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) PurpleStart else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
