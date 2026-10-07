package com.mln.tongji_canvas.data

data class UserSession(
    val id: String,
    val displayName: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    /**
     * 最近一次「凭据验证成功」的时间戳；null 表示当前保存的凭据从未验证成功过。
     * 注意：修改了凭据（accessToken）后会被清空，避免用旧结论标记新凭据。
     */
    val lastVerifiedAt: Long? = null,
    /**
     * 稳定账号标识，形如 "sid:2551830"（学号/工号）。
     * null 表示旧数据或尚未验证的账号，匹配时回退到 displayName / Canvas 会话 Cookie 指纹。
     */
    val identityKey: String? = null
)

data class OAuth2Tokens(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresIn: Long,
    val tokenType: String = "Bearer"
)

data class Course(
    val id: String,
    val name: String,
    val memberIds: List<String> = emptyList()
)
