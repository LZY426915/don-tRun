package com.youshu.app.ui.screen.camera

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.youshu.app.ui.components.PhotoEditOverlay
import com.youshu.app.ui.theme.PurpleEnd
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.util.ImageUtil
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    var currentZoomRatio by remember { mutableStateOf(1f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var zoomIndicatorStamp by remember { mutableLongStateOf(0L) }
    var flashEnabled by remember { mutableStateOf(false) }
    var focusMarker by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var focusMarkerStamp by remember { mutableLongStateOf(0L) }
    var targetRotation by remember { mutableStateOf(Surface.ROTATION_0) }
    var isTakingPhoto by remember { mutableStateOf(false) }
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

    LaunchedEffect(zoomIndicatorStamp) {
        if (zoomIndicatorStamp == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        showZoomIndicator = false
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
                                val boundCamera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                                camera = boundCamera
                                currentZoomRatio = boundCamera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (abs(zoomChange - 1f) < 0.001f) return@detectTransformGestures
                            val cameraInstance = camera ?: return@detectTransformGestures
                            val zoomState = cameraInstance.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            val minZoomRatio = maxOf(1f, zoomState.minZoomRatio)
                            val maxZoomRatio = maxOf(minZoomRatio, zoomState.maxZoomRatio)
                            val targetZoom = (zoomState.zoomRatio * zoomChange)
                                .coerceIn(minZoomRatio, maxZoomRatio)
                            if (abs(targetZoom - zoomState.zoomRatio) < 0.01f) return@detectTransformGestures

                            runCatching {
                                cameraInstance.cameraControl.setZoomRatio(targetZoom)
                            }.onSuccess { zoomFuture ->
                                currentZoomRatio = targetZoom
                                showZoomIndicator = true
                                zoomIndicatorStamp = System.currentTimeMillis()
                                zoomFuture.addListener(
                                    {
                                        runCatching { zoomFuture.get() }
                                        currentZoomRatio = cameraInstance.cameraInfo.zoomState.value
                                            ?.zoomRatio
                                            ?: targetZoom
                                    },
                                    ContextCompat.getMainExecutor(context)
                                )
                            }.onFailure {
                                currentZoomRatio = cameraInstance.cameraInfo.zoomState.value
                                    ?.zoomRatio
                                    ?: zoomState.zoomRatio
                                showZoomIndicator = true
                                zoomIndicatorStamp = System.currentTimeMillis()
                            }
                        }
                    }
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

        if (showZoomIndicator) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${"%.1f".format(currentZoomRatio)}x",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
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
                        enabled = imageCapture != null && !isTakingPhoto,
                        onClick = {
                            if (isTakingPhoto) return@ShutterButton
                            isTakingPhoto = true
                            takePhoto(
                                context = context,
                                imageCapture = imageCapture,
                                targetRotation = targetRotation,
                                onResult = { uri ->
                                    queuePhotoForEdit(uri)
                                    isTakingPhoto = false
                                },
                                onFinishedWithoutResult = {
                                    isTakingPhoto = false
                                }
                            )
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
            contentDescription = "浠庡浘搴撻€夋嫨",
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
            .graphicsLayer(alpha = if (enabled) 1f else 0.54f)
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


private data class PendingPhotoEdit(
    val uri: Uri,
    val replaceIndex: Int? = null
)


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
    onResult: (Uri) -> Unit,
    onFinishedWithoutResult: () -> Unit
) {
    val capture = imageCapture ?: run {
        onFinishedWithoutResult()
        return
    }
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
                onFinishedWithoutResult()
            }
        }
    )
}
