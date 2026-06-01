package com.youshu.app.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.youshu.app.data.agent.AgentItemCard
import com.youshu.app.data.agent.AgentMockReply
import com.youshu.app.data.agent.AgentMockService
import com.youshu.app.data.agent.ChatConversation
import com.youshu.app.data.agent.ChatHistoryService
import com.youshu.app.data.agent.ChatMessage
import com.youshu.app.data.agent.ChatMessageStatus
import com.youshu.app.data.agent.ChatRole
import com.youshu.app.ui.components.AppDecorativeBackground
import com.youshu.app.ui.theme.DividerSoft
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.ui.theme.PurpleTint
import com.youshu.app.ui.theme.TextHint
import com.youshu.app.ui.theme.TextPrimary
import com.youshu.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentChatScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var activeConversation by remember { mutableStateOf<ChatConversation?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var searchKeyword by rememberSaveable { mutableStateOf("") }
    var attachmentsExpanded by rememberSaveable { mutableStateOf(false) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var isReplying by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val messages = activeConversation?.messages.orEmpty()
    val displayMessages = if (isReplying) {
        messages + ChatMessage(
            id = "agent-loading-${activeConversation?.id.orEmpty()}",
            role = ChatRole.ASSISTANT,
            content = "小东西正在整理答案...",
            createdAt = System.currentTimeMillis(),
            status = ChatMessageStatus.LOADING
        )
    } else {
        messages
    }

    fun replaceConversation(conversation: ChatConversation) {
        activeConversation = conversation
        conversations = conversations
            .filterNot { it.id == conversation.id }
            .plus(conversation)
            .sortedByDescending { it.updatedAt }
    }

    fun submitTextMessage(rawMessage: String) {
        val message = rawMessage.trim()
        val current = activeConversation ?: return
        if (message.isEmpty() || isReplying) return

        val userMessage = ChatHistoryService.createUserMessage(content = message)
        val firstUserMessage = current.messages.none { it.role == ChatRole.USER }
        val withUserMessage = current.copy(
            title = if (firstUserMessage || current.title == ChatHistoryService.DEFAULT_TITLE) {
                ChatHistoryService.titleFromMessage(message)
            } else {
                current.title
            },
            updatedAt = userMessage.createdAt,
            messages = current.messages + userMessage
        )

        input = ""
        attachmentsExpanded = false
        isReplying = true
        replaceConversation(withUserMessage)

        coroutineScope.launch {
            ChatHistoryService.saveConversation(context, withUserMessage)
            val reply = runCatching {
                AgentMockService.sendText(message)
            }.getOrElse {
                AgentMockReply(
                    content = "小东西暂时没有拿到回复，请稍后重试。",
                    status = ChatMessageStatus.ERROR
                )
            }
            val assistantMessage = ChatHistoryService.createAssistantMessage(
                content = reply.content,
                status = reply.status,
                itemCards = reply.itemCards
            )
            val withAssistantMessage = withUserMessage.copy(
                updatedAt = assistantMessage.createdAt,
                messages = withUserMessage.messages + assistantMessage
            )
            replaceConversation(withAssistantMessage)
            ChatHistoryService.saveConversation(context, withAssistantMessage)
            isReplying = false
        }
    }

    fun submitMessage() {
        submitTextMessage(input)
    }

    fun submitImageMessage(source: String) {
        val current = activeConversation ?: return
        if (isReplying) return

        val userMessage = ChatHistoryService.createUserMessage(
            content = "$source 图片",
            imageUri = "mock://agent-image/${source}/${System.currentTimeMillis()}"
        )
        val firstUserMessage = current.messages.none { it.role == ChatRole.USER }
        val withImageMessage = current.copy(
            title = if (firstUserMessage || current.title == ChatHistoryService.DEFAULT_TITLE) {
                ChatHistoryService.titleFromMessage("$source 图片识别")
            } else {
                current.title
            },
            updatedAt = userMessage.createdAt,
            messages = current.messages + userMessage
        )

        input = ""
        attachmentsExpanded = false
        isReplying = true
        replaceConversation(withImageMessage)

        coroutineScope.launch {
            ChatHistoryService.saveConversation(context, withImageMessage)
            val reply = AgentMockService.sendImage(source)
            val assistantMessage = ChatHistoryService.createAssistantMessage(
                content = reply.content,
                status = reply.status,
                itemCards = reply.itemCards
            )
            val withAssistantMessage = withImageMessage.copy(
                updatedAt = assistantMessage.createdAt,
                messages = withImageMessage.messages + assistantMessage
            )
            replaceConversation(withAssistantMessage)
            ChatHistoryService.saveConversation(context, withAssistantMessage)
            isReplying = false
        }
    }

    fun handleTakePhoto() {
        submitImageMessage("拍照")
        Toast.makeText(context, "已插入拍照图片占位", Toast.LENGTH_SHORT).show()
    }

    fun handleChooseImage() {
        submitImageMessage("相册")
        Toast.makeText(context, "已插入相册图片占位", Toast.LENGTH_SHORT).show()
    }

    fun startNewConversation() {
        coroutineScope.launch {
            val conversation = ChatHistoryService.createConversation(context)
            input = ""
            attachmentsExpanded = false
            historyVisible = false
            replaceConversation(conversation)
        }
    }

    fun selectConversation(conversation: ChatConversation) {
        input = ""
        attachmentsExpanded = false
        activeConversation = conversation
        historyVisible = false
    }

    fun deleteConversation(conversation: ChatConversation) {
        coroutineScope.launch {
            ChatHistoryService.deleteConversation(context, conversation.id)
            val remaining = conversations.filterNot { it.id == conversation.id }
            if (remaining.isEmpty()) {
                val freshConversation = ChatHistoryService.createConversation(context)
                conversations = listOf(freshConversation)
                activeConversation = freshConversation
            } else {
                conversations = remaining
                if (activeConversation?.id == conversation.id) {
                    activeConversation = remaining.first()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val loadedConversations = ChatHistoryService.getConversations(context)
        val initialConversation = loadedConversations.firstOrNull()
            ?: ChatHistoryService.createConversation(context)
        conversations = if (loadedConversations.isEmpty()) {
            listOf(initialConversation)
        } else {
            loadedConversations
        }
        activeConversation = initialConversation
    }

    LaunchedEffect(displayMessages.size, activeConversation?.id, isReplying) {
        if (displayMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayMessages.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AgentTopBar(
                onBack = onBack,
                onOpenHistory = { historyVisible = true }
            )

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
                    top = 24.dp,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(displayMessages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }

            RecommendedQuestionBar(
                questions = AgentMockService.recommendedQuestions,
                onQuestionClick = { question ->
                    submitTextMessage(question)
                    attachmentsExpanded = false
                }
            )

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

        AnimatedVisibility(
            visible = historyVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable { historyVisible = false }
            )
        }

        AnimatedVisibility(
            visible = historyVisible,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            ConversationHistoryPanel(
                conversations = conversations,
                activeConversationId = activeConversation?.id,
                searchKeyword = searchKeyword,
                onSearchKeywordChange = { searchKeyword = it },
                onNewConversation = ::startNewConversation,
                onSelectConversation = ::selectConversation,
                onDeleteConversation = ::deleteConversation,
                onDismiss = { historyVisible = false }
            )
        }
    }
}

@Composable
private fun AgentTopBar(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit
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
        IconButton(
            onClick = onOpenHistory,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "历史对话",
                tint = TextPrimary
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage
) {
    val fromUser = message.role == ChatRole.USER
    val isError = message.status == ChatMessageStatus.ERROR
    val bubbleColor = when {
        isError -> Color(0xFFFFF4F4)
        fromUser -> Color(0xFFF1EAFE)
        else -> Color.White.copy(alpha = 0.97f)
    }
    val textColor = when {
        isError -> Color(0xFFB3261E)
        fromUser -> Color(0xFF3B225A)
        else -> TextPrimary
    }
    val alignment = if (fromUser) Arrangement.End else Arrangement.Start
    val shape = if (fromUser) {
        RoundedCornerShape(22.dp, 8.dp, 22.dp, 22.dp)
    } else {
        RoundedCornerShape(8.dp, 22.dp, 22.dp, 22.dp)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.75f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = alignment,
            verticalAlignment = Alignment.Top
        ) {
            if (!fromUser) {
                AgentAvatar()
                Spacer(modifier = Modifier.size(8.dp))
            }
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                    .background(bubbleColor)
                    .then(
                        if (fromUser) {
                            Modifier.border(1.dp, Color(0xFFE6D8FB), shape)
                        } else if (isError) {
                            Modifier.border(1.dp, Color(0xFFFFD6D6), shape)
                        } else {
                            Modifier.border(1.dp, DividerSoft, shape)
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    message.imageUri?.let { imageUri ->
                        ChatImagePreview(imageUri = imageUri)
                    }

                    if (message.status == ChatMessageStatus.LOADING) {
                        LoadingMessageContent()
                    } else {
                        Text(
                            text = message.content,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }

                    if (message.itemCards.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            message.itemCards.forEach { item ->
                                AgentItemResultCard(item = item)
                            }
                        }
                    }
                }
            }
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
private fun LoadingMessageContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = PurpleStart,
            strokeWidth = 2.dp
        )
        Text(
            text = "小东西正在整理答案...",
            color = TextSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun ChatImagePreview(imageUri: String) {
    val isMockImage = imageUri.startsWith("mock://")

    if (isMockImage) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PurpleTint)
                .border(1.dp, Color(0xFFE6D8FB), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = PurpleStart,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "图片消息占位",
                    color = PurpleStart,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    } else {
        AsyncImage(
            model = imageUri,
            contentDescription = "图片消息",
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun AgentItemResultCard(item: AgentItemCard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFBF8FF))
            .border(1.dp, Color(0xFFEDE3FB), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF1EAFE)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.name.take(1),
                color = PurpleStart,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.location,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AgentMiniTag(text = item.category)
                AgentMiniTag(text = item.quantity)
            }
            if (item.expireHint != null || item.note.isNotBlank()) {
                Text(
                    text = listOfNotNull(item.expireHint, item.note.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    color = if (item.expireHint != null) Color(0xFFB26A00) else TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AgentMiniTag(text: String) {
    Text(
        text = text,
        color = PurpleStart,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFF1EAFE))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun RecommendedQuestionBar(
    questions: List<String>,
    onQuestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
    ) {
        Text(
            text = "推荐问题",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(questions, key = { it }) { question ->
                Text(
                    text = question,
                    color = PurpleStart,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.96f))
                        .border(1.dp, Color(0xFFE6D8FB), RoundedCornerShape(999.dp))
                        .clickable { onQuestionClick(question) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
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
private fun ConversationHistoryPanel(
    conversations: List<ChatConversation>,
    activeConversationId: String?,
    searchKeyword: String,
    onSearchKeywordChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (ChatConversation) -> Unit,
    onDeleteConversation: (ChatConversation) -> Unit,
    onDismiss: () -> Unit
) {
    val keyword = searchKeyword.trim()
    val filteredConversations = remember(conversations, keyword) {
        if (keyword.isEmpty()) {
            conversations
        } else {
            conversations.filter { conversation ->
                conversation.title.contains(keyword, ignoreCase = true) ||
                    conversation.messages.any { it.content.contains(keyword, ignoreCase = true) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(318.dp)
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                spotColor = Color.Black.copy(alpha = 0.16f)
            )
            .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "历史对话",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭历史",
                    tint = TextSecondary
                )
            }
        }

        HistorySearchField(
            value = searchKeyword,
            onValueChange = onSearchKeywordChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(PurpleTint)
                .clickable(onClick = onNewConversation)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = PurpleStart,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "新建对话",
                color = PurpleStart,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(filteredConversations, key = { it.id }) { conversation ->
                HistoryConversationItem(
                    conversation = conversation,
                    selected = conversation.id == activeConversationId,
                    onClick = { onSelectConversation(conversation) },
                    onDelete = { onDeleteConversation(conversation) }
                )
            }
        }
    }
}

@Composable
private fun HistorySearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF7F5FA))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "搜索对话内容...",
                    color = TextHint,
                    fontSize = 14.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(PurpleStart)
            )
        }
    }
}

@Composable
private fun HistoryConversationItem(
    conversation: ChatConversation,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFF1EAFE) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                color = TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatConversationDate(conversation.updatedAt),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除对话",
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
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

private fun formatConversationDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))
}
