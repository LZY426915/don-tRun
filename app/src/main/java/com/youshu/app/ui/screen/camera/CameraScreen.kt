package com.youshu.app.ui.screen.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.youshu.app.ui.theme.PurpleEnd
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.util.CropRectFraction
import com.youshu.app.util.ImageUtil
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onDisposed: () -> Unit = {},
    onSkipPhoto: () -> Unit = {},
    onPhotoTaken: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var focusMarker by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var focusMarkerStamp by remember { mutableLongStateOf(0L) }
    var targetRotation by remember { mutableStateOf(Surface.ROTATION_0) }
    val capturedUris = remember { mutableStateListOf<Uri>() }
    val queuedEditUris = remember { mutableStateListOf<Uri>() }
    var pendingEdit by remember { mutableStateOf<PendingPhotoEdit?>(null) }

    fun queuePhotoForEdit(uri: Uri) {
        if (pendingEdit == null) {
            pendingEdit = PendingPhotoEdit(uri)
        } else {
            queuedEditUris.add(uri)
        }
    }

    fun showNextPendingEdit() {
        pendingEdit = if (queuedEditUris.isNotEmpty()) {
            PendingPhotoEdit(queuedEditUris.removeAt(0))
        } else {
            null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "需要相机权限才能拍照录入", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris
                .filterNot { uri ->
                    uri in capturedUris || uri in queuedEditUris || pendingEdit?.uri == uri
                }
                .forEach(::queuePhotoForEdit)
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(flashEnabled, imageCapture) {
        imageCapture?.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
    }

    LaunchedEffect(focusMarkerStamp) {
        if (focusMarkerStamp == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        focusMarker = null
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            onDisposed()
        }
    }

    DisposableEffect(context, imageCapture) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                targetRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        onDispose {
            orientationListener.disable()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { createdPreviewView ->
                        previewView = createdPreviewView
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            cameraProvider = provider
                            val preview = Preview.Builder()
                                .setTargetRotation(targetRotation)
                                .build()
                                .also {
                                    it.surfaceProvider = createdPreviewView.surfaceProvider
                                }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetRotation(targetRotation)
                                .build()
                            imageCapture = capture

                            try {
                                provider.unbindAll()
                                camera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera, previewView) {
                        detectTapGestures { offset ->
                            val view = previewView ?: return@detectTapGestures
                            val cameraInstance = camera ?: return@detectTapGestures
                            val meteringPoint = view.meteringPointFactory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(
                                meteringPoint,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                            cameraInstance.cameraControl.startFocusAndMetering(action)
                            focusMarker = offset.x to offset.y
                            focusMarkerStamp = System.currentTimeMillis()
                        }
                    }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF181818)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "需要相机权限才能拍照",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp
                )
            }
        }

        focusMarker?.let { (x, y) ->
            Box(
                modifier = Modifier
                    .padding(
                        start = with(density) { (x.toDp() - 36.dp).coerceAtLeast(0.dp) },
                        top = with(density) { (y.toDp() - 36.dp).coerceAtLeast(0.dp) }
                    )
                    .size(72.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.48f), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundCameraToolButton(
                icon = Icons.Default.Close,
                contentDescription = "关闭",
                onClick = onBack
            )

            Text(
                text = if (capturedUris.isEmpty()) "拍照录入" else "已选 ${capturedUris.size} 张",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            RoundCameraToolButton(
                icon = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "闪光灯",
                onClick = { flashEnabled = !flashEnabled }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (capturedUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 2.dp)
                ) {
                    itemsIndexed(capturedUris, key = { index, uri -> "$index-$uri" }) { index, uri ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { pendingEdit = PendingPhotoEdit(uri, replaceIndex = index) }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "缩略图 ${index + 1}",
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .clickable { capturedUris.removeAt(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "删除缩略图",
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    GalleryButton(onClick = { galleryLauncher.launch("image/*") })
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    ShutterButton(
                        iconRotation = targetRotation.toIconRotationDegrees(),
                        enabled = imageCapture != null,
                        onClick = {
                            takePhoto(context, imageCapture, targetRotation) { uri ->
                                queuePhotoForEdit(uri)
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (capturedUris.isNotEmpty()) {
                        NextButton(onClick = { onPhotoTaken(capturedUris.toList()) })
                    }
                }
            }
        }

        pendingEdit?.let { edit ->
            run {
                PhotoEditOverlay(
                    uri = edit.uri,
                    onDismiss = { showNextPendingEdit() },
                    onApply = { rotationDegrees, cropRect ->
                        val editedPath = ImageUtil.createEditedImage(
                            context = context,
                            uri = edit.uri,
                            rotationDegrees = rotationDegrees,
                            cropRect = cropRect
                        )
                        if (editedPath != null) {
                            val editedUri = Uri.fromFile(File(editedPath))
                            val replaceIndex = edit.replaceIndex
                            if (replaceIndex != null && replaceIndex in capturedUris.indices) {
                                capturedUris[replaceIndex] = editedUri
                            } else {
                                capturedUris.add(editedUri)
                            }
                        } else {
                            Toast.makeText(context, "图片编辑失败", Toast.LENGTH_SHORT).show()
                        }
                        showNextPendingEdit()
                    }
                )
            }
        }
    }
}

@Composable
private fun RoundCameraToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White
        )
    }
}

@Composable
private fun GalleryButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = "从图库选择",
            tint = Color.White,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun ShutterButton(
    iconRotation: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .border(2.dp, Color.White.copy(alpha = 0.78f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(PurpleStart, PurpleEnd)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "拍照",
                tint = Color.White,
                modifier = Modifier
                    .size(31.dp)
                    .graphicsLayer(rotationZ = iconRotation)
            )
        }
    }
}

@Composable
private fun NextButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(PurpleStart)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "下一步",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PhotoEditOverlay(
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

private data class PendingPhotoEdit(
    val uri: Uri,
    val replaceIndex: Int? = null
)

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

private fun Int.toIconRotationDegrees(): Float {
    return when (this) {
        Surface.ROTATION_90 -> 90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 270f
        else -> 0f
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    targetRotation: Int,
    onResult: (Uri) -> Unit
) {
    val capture = imageCapture ?: return
    capture.targetRotation = targetRotation
    val imagesDir = File(context.filesDir, "images")
    if (!imagesDir.exists()) imagesDir.mkdirs()

    val file = File(imagesDir, "capture_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onResult(Uri.fromFile(file))
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(context, "拍照失败：${exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )
}
