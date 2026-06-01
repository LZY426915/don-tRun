package com.youshu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.ui.theme.TextHint
import com.youshu.app.ui.theme.TextPrimary

@Composable
fun ItemImageGallery(
    imagePaths: List<String>,
    modifier: Modifier = Modifier,
    onAddPhoto: () -> Unit,
    onRemoveImage: (Int) -> Unit
) {
    if (imagePaths.isEmpty()) {
        ActionImageSlot(
            title = "拍照片",
            subtitle = "添加物品照片",
            icon = Icons.Default.PhotoCamera,
            onClick = onAddPhoto,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    Column(modifier = modifier) {
        Text(
            text = "已添加 ${imagePaths.size} 张照片",
            color = TextHint,
            fontSize = 12.sp
        )
        LazyRow(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(imagePaths, key = { index, path -> "$index-$path" }) { index, path ->
                ImagePreviewCard(
                    imagePath = path,
                    title = "照片 ${index + 1}",
                    onRemove = { onRemoveImage(index) },
                    modifier = Modifier.size(width = 120.dp, height = 120.dp)
                )
            }
            item {
                ActionImageSlot(
                    title = "添加照片",
                    subtitle = "继续拍摄",
                    icon = Icons.Default.Add,
                    onClick = onAddPhoto,
                    modifier = Modifier.size(width = 120.dp, height = 120.dp)
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewCard(
    imagePath: String,
    title: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(120.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        AsyncImage(
            model = imagePath,
            contentDescription = title,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.12f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除图片",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ActionImageSlot(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(164.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF8F6FC))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(PurpleStart.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PurpleStart,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
