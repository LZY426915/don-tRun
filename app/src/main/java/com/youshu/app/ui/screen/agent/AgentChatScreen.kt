package com.youshu.app.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.app.data.agent.sendAgentMessage
import com.youshu.app.ui.components.AppDecorativeBackground
import com.youshu.app.ui.theme.DividerSoft
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.ui.theme.PurpleTint
import com.youshu.app.ui.theme.TextHint
import com.youshu.app.ui.theme.TextPrimary
import com.youshu.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class AgentChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean
)

@Composable
fun AgentChatScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val messages = remember {
        mutableStateListOf(
            AgentChatMessage(
                id = 0L,
                text = "你好，我是小东西，帮你管好每件物品不弄丢。你可以问我：冰箱里有什么快过期？我的充电器放在哪？帮我整理一份购物清单。",
                fromUser = false
            )
        )
    }
    var input by rememberSaveable { mutableStateOf("") }
    var attachmentsExpanded by rememberSaveable { mutableStateOf(false) }
    var nextMessageId by remember { mutableStateOf(1L) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun submitMessage() {
        val message = input.trim()
        if (message.isEmpty()) return

        messages.add(
            AgentChatMessage(
                id = nextMessageId++,
                text = message,
                fromUser = true
            )
        )
        input = ""
        attachmentsExpanded = false

        coroutineScope.launch {
            val reply = sendAgentMessage(message)
            messages.add(
                AgentChatMessage(
                    id = nextMessageId++,
                    text = reply,
                    fromUser = false
                )
            )
        }
    }

    fun handleTakePhoto() {
        attachmentsExpanded = false
        Toast.makeText(context, "拍照能力待接入小东西", Toast.LENGTH_SHORT).show()
    }

    fun handleChooseImage() {
        attachmentsExpanded = false
        Toast.makeText(context, "相册能力待接入小东西", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AgentTopBar(onBack = onBack)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        attachmentsExpanded = false
                    },
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 12.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }

            AgentInputBar(
                value = input,
                onValueChange = { input = it },
                attachmentsExpanded = attachmentsExpanded,
                onToggleAttachments = { attachmentsExpanded = !attachmentsExpanded },
                onSend = ::submitMessage,
                onVoiceClick = {
                    attachmentsExpanded = false
                    Toast.makeText(context, "语音输入能力待接入小东西", Toast.LENGTH_SHORT).show()
                },
                onTakePhoto = ::handleTakePhoto,
                onChooseImage = ::handleChooseImage
            )
        }
    }
}

@Composable
private fun AgentTopBar(
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 6.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextPrimary
            )
        }
        Text(
            text = "小东西",
            modifier = Modifier.align(Alignment.Center),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ChatBubble(
    message: AgentChatMessage
) {
    val bubbleColor = if (message.fromUser) PurpleStart else Color.White.copy(alpha = 0.96f)
    val textColor = if (message.fromUser) Color.White else TextPrimary
    val alignment = if (message.fromUser) Arrangement.End else Arrangement.Start
    val shape = if (message.fromUser) {
        RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.fromUser) {
            AgentAvatar()
            Spacer(modifier = Modifier.size(8.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .clip(shape)
                .background(bubbleColor)
                .then(
                    if (message.fromUser) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, DividerSoft, shape)
                    }
                )
                .padding(horizontal = 15.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun AgentAvatar() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(PurpleTint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = PurpleStart,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AgentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachmentsExpanded: Boolean,
    onToggleAttachments: () -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseImage: () -> Unit
) {
    val canSend = value.trim().isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = PurpleStart.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.08f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.98f))
                .border(1.dp, DividerSoft, RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp, max = 128.dp)
                    .padding(horizontal = 2.dp, vertical = 4.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "发消息或按住说话",
                        color = TextHint,
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(PurpleStart),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    minLines = 1,
                    maxLines = 4
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (attachmentsExpanded) "选择附件" else "小东西",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                RoundIconButton(
                    icon = if (attachmentsExpanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (attachmentsExpanded) "收起附件" else "展开附件",
                    backgroundColor = if (attachmentsExpanded) PurpleTint else Color(0xFFF8F6FC),
                    iconColor = PurpleStart,
                    onClick = onToggleAttachments
                )
                Spacer(modifier = Modifier.size(10.dp))
                RoundIconButton(
                    icon = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                    contentDescription = if (canSend) "发送" else "语音输入",
                    backgroundColor = if (canSend) PurpleStart else Color(0xFFF8F6FC),
                    iconColor = if (canSend) Color.White else TextSecondary,
                    onClick = {
                        if (canSend) {
                            onSend()
                        } else {
                            onVoiceClick()
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = attachmentsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                AttachmentPanel(
                    onTakePhoto = onTakePhoto,
                    onChooseImage = onChooseImage
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    iconColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AttachmentPanel(
    onTakePhoto: () -> Unit,
    onChooseImage: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AttachmentOption(
                title = "拍照",
                icon = Icons.Default.CameraAlt,
                onClick = onTakePhoto,
                modifier = Modifier.weight(1f)
            )
            AttachmentOption(
                title = "相册",
                icon = Icons.Default.Image,
                onClick = onChooseImage,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AttachmentOption(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(PurpleTint.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PurpleStart,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
