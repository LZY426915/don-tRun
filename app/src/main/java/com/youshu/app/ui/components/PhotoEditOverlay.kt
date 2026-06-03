package com.youshu.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.util.CropRectFraction
import com.youshu.app.util.ImageUtil
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoEditOverlay(
    uri: Uri,
    onDismiss: () -> Unit,
    onApply: (rotationDegrees: Int, cropRect: CropRectFraction) -> Unit
) {
    val context = LocalContext.current
    var rotationDegrees by remember(uri) { mutableStateOf(0) }
    var cropRect by remember(uri) {
        mutableStateOf(CropRectFraction(0.06f, 0.06f, 0.94f, 0.94f))
    }
    var imageAspectRatio by remember(uri) { mutableStateOf(1f) }
    var previewBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(context, uri, rotationDegrees) {
        val bitmap = withContext(Dispatchers.IO) {
            ImageUtil.createPreviewBitmap(context, uri, rotationDegrees)
        }
        previewBitmap = bitmap
        imageAspectRatio = bitmap
            ?.let { it.width.toFloat() / it.height.toFloat() }
            ?.takeIf { it > 0f }
            ?: ImageUtil.getDisplayAspectRatio(context, uri, rotationDegrees)
                ?.takeIf { it > 0f }
            ?: 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 18.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF181818))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调整照片",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onDismiss)
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
                contentAlignment = Alignment.Center
            ) {
                val safeAspectRatio = imageAspectRatio.coerceIn(0.05f, 20f)
                val stageAspectRatio = maxWidth.value / maxHeight.value
                val frameWidth = if (safeAspectRatio >= stageAspectRatio) {
                    maxWidth
                } else {
                    maxHeight * safeAspectRatio
                }
                val frameHeight = if (safeAspectRatio >= stageAspectRatio) {
                    maxWidth / safeAspectRatio
                } else {
                    maxHeight
                }
                Box(
                    modifier = Modifier
                        .width(frameWidth)
                        .height(frameHeight)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "照片预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                    CropFrameOverlay(
                        cropRect = cropRect,
                        onCropRectChange = { cropRect = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PhotoEditButton(
                    text = "左转",
                    modifier = Modifier.weight(1f),
                    onClick = { rotationDegrees = (rotationDegrees + 270) % 360 }
                )
                PhotoEditButton(
                    text = "右转",
                    modifier = Modifier.weight(1f),
                    onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }
                )
                PhotoEditButton(
                    text = "重置",
                    modifier = Modifier.weight(1.25f),
                    onClick = { cropRect = CropRectFraction(0.06f, 0.06f, 0.94f, 0.94f) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(PurpleStart)
                    .clickable { onApply(rotationDegrees, cropRect.normalized()) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "应用",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CropFrameOverlay(
    cropRect: CropRectFraction,
    onCropRectChange: (CropRectFraction) -> Unit
) {
    val density = LocalDensity.current
    val edgeTouchSlopPx = with(density) { 34.dp.toPx() }
    val latestCropRect by rememberUpdatedState(cropRect)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(edgeTouchSlopPx) {
                var activeDragMode = CropDragMode.None
                detectDragGestures(
                    onDragStart = { offset ->
                        activeDragMode = latestCropRect.dragModeFor(
                            offset = offset,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            edgeTouchSlopPx = edgeTouchSlopPx
                        )
                    },
                    onDragEnd = { activeDragMode = CropDragMode.None },
                    onDragCancel = { activeDragMode = CropDragMode.None },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val canvasWidth = size.width.toFloat().takeIf { it > 0f } ?: return@detectDragGestures
                        val canvasHeight = size.height.toFloat().takeIf { it > 0f } ?: return@detectDragGestures
                        onCropRectChange(
                            latestCropRect.dragged(
                                mode = activeDragMode,
                                dx = dragAmount.x / canvasWidth,
                                dy = dragAmount.y / canvasHeight
                            )
                        )
                    }
                )
            }
    ) {
        val canvasSize = size
        val left = cropRect.left * canvasSize.width
        val top = cropRect.top * canvasSize.height
        val right = cropRect.right * canvasSize.width
        val bottom = cropRect.bottom * canvasSize.height
        val cropWidth = right - left
        val cropHeight = bottom - top
        val dimColor = Color.Black.copy(alpha = 0.48f)

        drawRect(dimColor, topLeft = Offset.Zero, size = Size(canvasSize.width, top))
        drawRect(
            dimColor,
            topLeft = Offset(0f, bottom),
            size = Size(canvasSize.width, canvasSize.height - bottom)
        )
        drawRect(dimColor, topLeft = Offset(0f, top), size = Size(left, cropHeight))
        drawRect(
            dimColor,
            topLeft = Offset(right, top),
            size = Size(canvasSize.width - right, cropHeight)
        )

        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(cropWidth, cropHeight),
            style = Stroke(width = 2.dp.toPx())
        )

        val handleLength = minOf(72.dp.toPx(), cropWidth * 0.55f, cropHeight * 0.55f)
        val handleStroke = 5.dp.toPx()
        val centerX = left + cropWidth / 2f
        val centerY = top + cropHeight / 2f
        val handleColor = PurpleStart
        drawLine(
            color = handleColor,
            start = Offset(centerX - handleLength / 2f, top),
            end = Offset(centerX + handleLength / 2f, top),
            strokeWidth = handleStroke
        )
        drawLine(
            color = handleColor,
            start = Offset(centerX - handleLength / 2f, bottom),
            end = Offset(centerX + handleLength / 2f, bottom),
            strokeWidth = handleStroke
        )
        drawLine(
            color = handleColor,
            start = Offset(left, centerY - handleLength / 2f),
            end = Offset(left, centerY + handleLength / 2f),
            strokeWidth = handleStroke
        )
        drawLine(
            color = handleColor,
            start = Offset(right, centerY - handleLength / 2f),
            end = Offset(right, centerY + handleLength / 2f),
            strokeWidth = handleStroke
        )
    }
}

@Composable
private fun PhotoEditButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private enum class CropDragMode {
    None,
    Move,
    Left,
    Right,
    Top,
    Bottom
}

private fun CropRectFraction.dragModeFor(
    offset: Offset,
    width: Float,
    height: Float,
    edgeTouchSlopPx: Float
): CropDragMode {
    if (width <= 0f || height <= 0f) return CropDragMode.None
    val leftPx = left * width
    val rightPx = right * width
    val topPx = top * height
    val bottomPx = bottom * height
    val insideX = offset.x in leftPx..rightPx
    val insideY = offset.y in topPx..bottomPx
    if (!insideX || !insideY) return CropDragMode.None

    val edgeDistances = listOf(
        CropDragMode.Left to abs(offset.x - leftPx),
        CropDragMode.Right to abs(offset.x - rightPx),
        CropDragMode.Top to abs(offset.y - topPx),
        CropDragMode.Bottom to abs(offset.y - bottomPx)
    )
    val nearestEdge = edgeDistances.minByOrNull { it.second }
    return if (nearestEdge != null && nearestEdge.second <= edgeTouchSlopPx) {
        nearestEdge.first
    } else {
        CropDragMode.Move
    }
}

private fun CropRectFraction.dragged(
    mode: CropDragMode,
    dx: Float,
    dy: Float
): CropRectFraction {
    val minSize = 0.16f
    return when (mode) {
        CropDragMode.Move -> {
            val cropWidth = right - left
            val cropHeight = bottom - top
            val newLeft = (left + dx).coerceIn(0f, 1f - cropWidth)
            val newTop = (top + dy).coerceIn(0f, 1f - cropHeight)
            copy(
                left = newLeft,
                top = newTop,
                right = newLeft + cropWidth,
                bottom = newTop + cropHeight
            )
        }
        CropDragMode.Left -> copy(left = (left + dx).coerceIn(0f, right - minSize))
        CropDragMode.Right -> copy(right = (right + dx).coerceIn(left + minSize, 1f))
        CropDragMode.Top -> copy(top = (top + dy).coerceIn(0f, bottom - minSize))
        CropDragMode.Bottom -> copy(bottom = (bottom + dy).coerceIn(top + minSize, 1f))
        CropDragMode.None -> this
    }
}
