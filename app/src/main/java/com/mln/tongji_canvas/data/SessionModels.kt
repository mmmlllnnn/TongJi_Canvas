package com.mln.tongji_canvas.data

data class UserSession(
    val id: String,
    val displayName: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val lastVerifiedAt: Long? = null
)

data class ScanResult(
    val url: String
)

data class OAuth2Tokens(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresIn: Long,
    val tokenType: String = "Bearer"
)


