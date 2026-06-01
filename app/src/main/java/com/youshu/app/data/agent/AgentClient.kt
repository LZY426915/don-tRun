package com.youshu.app.data.agent

suspend fun sendAgentMessage(message: String): String {
    // TODO: 后续在这里接入真实智能体接口，例如 GPT / DeepSeek / 后端 Agent API。
    return "智能体能力暂未接入，我已经收到你的问题。之后这里会连接真实 AI 服务。"
}

suspend fun sendAgentImageMessage(
    text: String? = null,
    imageUri: String
): String {
    // TODO: 后续在这里接入多模态智能体接口，上传图片并附带可选文本。
    return "图片能力暂未接入，我已经收到你的图片。之后这里会连接真实 AI 服务。"
}
