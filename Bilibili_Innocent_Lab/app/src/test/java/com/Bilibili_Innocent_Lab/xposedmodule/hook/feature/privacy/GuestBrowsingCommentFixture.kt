package com.bilibili.app.comment3.data.model

class CommentSubjectDescription(
    val guestLimit: Boolean = true,
    val data: List<String> = listOf("original"),
    val hasNext: Boolean = false
) {
    fun k(): Boolean = guestLimit
    fun k(index: Int): Boolean = index > 0
}
