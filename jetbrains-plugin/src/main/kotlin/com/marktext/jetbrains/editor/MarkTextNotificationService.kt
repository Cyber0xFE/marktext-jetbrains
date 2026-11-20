package com.marktext.jetbrains.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

@Service(Service.Level.PROJECT)
internal class MarkTextNotificationService {
    private val logger = Logger.getInstance(MarkTextNotificationService::class.java)

    fun report(message: String) {
        logger.warn("[MarkText] $message")
    }
}
