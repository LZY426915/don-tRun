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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogMessage by remember { mutableStateOf<String?>(null) }

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
                    title = "AI 服务状态",
                    subtitle = "DeepSeek、千问和高德服务由云端安全管理",
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
            title = "AI 服务状态",
            subtitle = "访问凭证保存在云端，应用和安装包中不包含 API Key。",
            onDismissRequest = { showModelDialog = false },
            confirmText = "关闭",
            onConfirm = { showModelDialog = false }
        ) {
            if (models.isEmpty()) {
                Text(
                    text = "服务配置正在初始化。",
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
                                text = model.provider,
                                fontSize = 12.sp,
                                color = TextHint
                            )
                            Text(
                                text = "云端安全托管 · 已启用",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
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
