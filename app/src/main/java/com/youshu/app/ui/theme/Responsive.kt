package com.youshu.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 基准屏幕宽度（dp），以该宽度作为字号/尺寸的设计基准。 */
private const val BASE_SCREEN_WIDTH_DP = 390

/**
 * 依据当前设备屏幕宽度返回缩放系数：
 * - 窄屏（如 320dp/360dp）自动缩小，避免文字在按钮中显得过大；
 * - 宽屏上限为 1.0，避免在大屏上被无限放大。
 */
@Composable
fun rememberResponsiveScale(): Float {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return (widthDp.toFloat() / BASE_SCREEN_WIDTH_DP).coerceIn(0.82f, 1.0f)
}
