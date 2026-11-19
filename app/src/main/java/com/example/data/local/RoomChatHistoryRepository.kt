package com.example.data.local

import com.example.data.local.db.ChatAttachmentEntity
import com.example.data.local.db.ChatHistoryDao
import com.example.data.local.db.ChatMessageEntity
import com.example.data.local.db.ChatMessageWithAttachments
import com.example.data.local.db.ChatSessionSummaryTuple
import com.example.data.security.SecretRedactor
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatAttachmentKind
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatSession
import com.example.domain.model.MessageRole
import com.example.domain.repository.ChatHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [ChatHistoryRepository] storing conversations strictly on device
 * and scrubbing any accidental credential tokens before persistence.
 */
@Singleton
class RoomChatHistoryRepository @Inject constructor(
    private val chatHistoryDao: ChatHistoryDao,
) : ChatHistoryRepository {

    override fun observeSessions(): Flow<List<ChatSession>> {
        return chatHistoryDao.observeSessions().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return chatHistoryDao.observeMessagesWithAttachments(sessionId).map { messages ->
            messages.map { it.toDomain() }
        }
    }

    override suspend fun getLatestSession(): ChatSession? {
        return chatHistoryDao.getLatestSession()?.toDomain()
    }

    override suspend fun getMessages(sessionId: String): List<ChatMessage> {
        return chatHistoryDao.getMessagesWithAttachments(sessionId).map { it.toDomain() }
    }

    override suspend fun saveMessage(
        sessionId: String,
        sessionTitle: String,
        modelId: String?,
        message: ChatMessage,
    ) {
        val safeContent = SecretRedactor.redact(message.content)
        val safeTitle = SecretRedactor.redact(sessionTitle).trim()
            .replace('\n', ' ')
            .take(64)
            .ifBlank { "Chat" }

        val entity = ChatMessageEntity(
            id = message.id,
            sessionId = sessionId,
            role = message.role.name,
            content = safeContent,
            timestampMillis = message.timestampMillis,
            modelId = message.modelId ?: modelId,
            isError = message.isError,
            totalTokens = message.totalTokens,
        )
        val attachmentEntities = message.attachments.map { attachment ->
            ChatAttachmentEntity(
                id = attachment.id,
                messageId = message.id,
                localPath = attachment.localPath,
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                sizeBytes = attachment.sizeBytes,
                width = attachment.width,
                height = attachment.height,
                attachmentKind = attachment.kind.name,
                createdAtMillis = attachment.createdAtMillis,
            )
        }
        chatHistoryDao.saveSessionAndMessage(
            sessionId = sessionId,
            fallbackTitle = safeTitle,
            modelId = modelId,
            updatedAtMillis = message.timestampMillis,
            message = entity,
            attachments = attachmentEntities,
        )
    }

    override suspend fun deleteMessage(messageId: String) {
        chatHistoryDao.deleteMessageById(messageId)
    }

    override suspend fun deleteSession(sessionId: String) {
        chatHistoryDao.deleteSessionById(sessionId)
    }

    override suspend fun clearAllHistory() {
        chatHistoryDao.deleteAllSessions()
    }

    private fun ChatSessionSummaryTuple.toDomain(): ChatSession {
        return ChatSession(
            id = id,
            title = title,
            modelId = modelId,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            messageCount = messageCount,
            lastMessagePreview = lastMessagePreview.orEmpty(),
        )
    }

    private fun ChatMessageWithAttachments.toDomain(): ChatMessage {
        val parsedRole = runCatching { MessageRole.valueOf(message.role) }
            .getOrDefault(MessageRole.ASSISTANT)
        return ChatMessage(
            id = message.id,
            role = parsedRole,
            content = message.content,
            timestampMillis = message.timestampMillis,
            modelId = message.modelId,
            isStreaming = false,
            isError = message.isError,
            totalTokens = message.totalTokens,
            attachments = attachments.map { entity ->
                ChatAttachment(
                    id = entity.id,
                    localPath = entity.localPath,
                    mimeType = entity.mimeType,
                    fileName = entity.fileName,
                    sizeBytes = entity.sizeBytes,
                    width = entity.width,
                    height = entity.height,
                    kind = runCatching { ChatAttachmentKind.valueOf(entity.attachmentKind) }
                        .getOrDefault(ChatAttachmentKind.USER_IMAGE),
                    createdAtMillis = entity.createdAtMillis,
                )
            },
        )
    }
}
