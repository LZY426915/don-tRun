package com.youshu.app.data.ai

data class AiSearchCriteria(
    val keyword: String = "",
    val categoryName: String? = null,
    val locationName: String? = null,
    val status: String? = null,
    val expiringSoon: Boolean = false
)

data class AiImageRecognitionResult(
    val name: String = "",
    val categoryName: String? = null,
    val locationName: String? = null,
    val quantity: Int? = null,
    val unit: String? = null,
    val expireDays: Int? = null,
    val note: String? = null
)
