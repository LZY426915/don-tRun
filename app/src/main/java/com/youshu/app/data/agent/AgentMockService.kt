package com.youshu.app.data.agent

import kotlinx.coroutines.delay

data class AgentMockReply(
    val content: String,
    val status: ChatMessageStatus = ChatMessageStatus.NORMAL,
    val itemCards: List<AgentItemCard> = emptyList()
)

object AgentMockService {
    val recommendedQuestions = listOf(
        "冰箱里有什么快过期？",
        "我的充电器放在哪？",
        "帮我整理一份购物清单",
        "演示错误提示",
        "演示图片消息"
    )

    suspend fun sendText(message: String): AgentMockReply {
        delay(1100)
        val normalized = message.trim()

        return when {
            normalized.contains("错误") || normalized.contains("失败") -> AgentMockReply(
                content = "小东西暂时没有连上智能体服务。你可以稍后重试，或先检查网络和接口配置。",
                status = ChatMessageStatus.ERROR
            )

            normalized.contains("充电器") || normalized.contains("剪刀") ||
                normalized.contains("物品卡片") || normalized.contains("放在哪") -> AgentMockReply(
                    content = "我先用假数据帮你演示物品结果卡片。真实接入后，这里会展示从库房检索到的物品。",
                    itemCards = demoItemCards
                )

            normalized.contains("图片") -> AgentMockReply(
                content = "你可以点左下角加号里的拍照或相册，我会先插入一条图片消息占位，再返回 mock 回复。"
            )

            normalized.contains("过期") || normalized.contains("冰箱") -> AgentMockReply(
                content = "根据演示数据，冰箱里有 2 件需要留意：牛奶还有 2 天过期，酸奶还有 5 天过期。",
                itemCards = demoItemCards.take(2)
            )

            normalized.contains("购物清单") || normalized.contains("清单") -> AgentMockReply(
                content = "我整理了一份演示购物清单：牛奶、纸巾、数据线。后续接入真实智能体后，可以结合你的库存自动补全。"
            )

            else -> AgentMockReply(
                content = "这是小东西的 mock 回复。我已经收到你的问题：$normalized。你可以试试“充电器”“冰箱快过期”“演示错误提示”来查看不同状态。"
            )
        }
    }

    suspend fun sendImage(source: String): AgentMockReply {
        delay(900)
        return AgentMockReply(
            content = "我已经收到这张来自“$source”的图片。图片识别能力还没接入真实智能体，所以这里先展示图片消息入口和 mock 回复。"
        )
    }

    private val demoItemCards = listOf(
        AgentItemCard(
            id = "mock-charger",
            name = "Type-C 充电器",
            location = "卧室 / 床头柜第二层",
            category = "数码配件",
            quantity = "1 个",
            note = "白色 65W，常用",
            expireHint = null
        ),
        AgentItemCard(
            id = "mock-milk",
            name = "鲜牛奶",
            location = "厨房 / 冰箱冷藏层",
            category = "食品",
            quantity = "2 盒",
            note = "优先喝掉开封那盒",
            expireHint = "还有 2 天过期"
        ),
        AgentItemCard(
            id = "mock-scissors",
            name = "剪刀",
            location = "客厅 / 电视柜抽屉",
            category = "工具",
            quantity = "1 把",
            note = "绿色手柄"
        )
    )
}
