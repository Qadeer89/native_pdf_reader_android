package com.pdfreader.app.cloud

/** The signed-in cloud account (PDF-011, DEC-003). Never holds a password or raw token. */
data class CloudUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
)
