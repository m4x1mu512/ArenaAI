package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.CoarenaDatabase
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatAttachmentKind
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomChatHistoryRepositoryTest {

    private lateinit var database: CoarenaDatabase
    private lateinit var repository: RoomChatHistoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CoarenaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomChatHistoryRepository(database.chatHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saves session and messages in chronological order and updates preview`() = runTest {
        val userMsg = ChatMessage(
            id = "m1",
            role = MessageRole.USER,
            content = "Explain Kotlin Flow",
            timestampMillis = 1000L,
        )
        val assistantMsg = ChatMessage(
            id = "m2",
            role = MessageRole.ASSISTANT,
            content = "Flow is a cold asynchronous stream.",
            timestampMillis = 2000L,
            modelId = "coarena-pro",
            totalTokens = 48,
        )

        repository.saveMessage("s1", "Explain Kotlin Flow", "coarena-pro", userMsg)
        repository.saveMessage("s1", "Explain Kotlin Flow", "coarena-pro", assistantMsg)

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals("Explain Kotlin Flow", sessions[0].title)
        assertEquals(2, sessions[0].messageCount)
        assertEquals("Flow is a cold asynchronous stream.", sessions[0].lastMessagePreview)

        val messages = repository.getMessages("s1")
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(48, messages[1].totalTokens)
    }

    @Test
    fun `redacts api key patterns before saving to database`() = runTest {
        val leakedKey = "sk-secret-key-987654321"
        repository.saveMessage(
            sessionId = "s_sec",
            sessionTitle = "My key $leakedKey",
            modelId = "coarena-pro",
            message = ChatMessage(
                id = "m_sec",
                role = MessageRole.USER,
                content = "Check $leakedKey",
                timestampMillis = 1000L,
            ),
        )

        val messages = repository.getMessages("s_sec")
        assertFalse(messages[0].content.contains(leakedKey))
        assertTrue(messages[0].content.contains("[REDACTED]"))
    }

    @Test
    fun `deletes individual session and clears all history`() = runTest {
        repository.saveMessage(
            "s1",
            "First",
            "m1",
            ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestampMillis = 100L),
        )
        repository.saveMessage(
            "s2",
            "Second",
            "m1",
            ChatMessage(id = "2", role = MessageRole.USER, content = "World", timestampMillis = 200L),
        )

        assertEquals("s2", repository.getLatestSession()?.id)

        repository.deleteSession("s2")
        assertTrue(repository.getMessages("s2").isEmpty())
        assertEquals(1, repository.observeSessions().first().size)

        repository.clearAllHistory()
        assertTrue(repository.observeSessions().first().isEmpty())
        assertNull(repository.getLatestSession())
    }

    @Test
    fun `persists image attachment metadata with its message`() = runTest {
        val attachment = ChatAttachment(
            id = "att-1",
            localPath = "/data/user/0/com.example/files/chat-images/att-1.jpg",
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
            sizeBytes = 245_760L,
            width = 1280,
            height = 720,
            kind = ChatAttachmentKind.USER_IMAGE,
            createdAtMillis = 900L,
        )
        val message = ChatMessage(
            id = "m-image",
            role = MessageRole.USER,
            content = "What is in this picture?",
            timestampMillis = 1_000L,
            attachments = listOf(attachment),
        )

        repository.saveMessage("s-image", "Image question", "vision-model", message)

        val restored = repository.getMessages("s-image").single()
        assertEquals("What is in this picture?", restored.content)
        assertEquals(listOf(attachment), restored.attachments)
        assertEquals(listOf(attachment), repository.observeMessages("s-image").first().single().attachments)
    }

    @Test
    fun `deleting a message also deletes its attachment metadata`() = runTest {
        repository.saveMessage(
            sessionId = "s-image",
            sessionTitle = "Image question",
            modelId = "vision-model",
            message = ChatMessage(
                id = "m-image",
                role = MessageRole.USER,
                content = "",
                timestampMillis = 1_000L,
                attachments = listOf(
                    ChatAttachment(
                        id = "att-1",
                        localPath = "/private/chat-images/att-1.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 1_024L,
                        kind = ChatAttachmentKind.USER_IMAGE,
                    ),
                ),
            ),
        )

        repository.deleteMessage("m-image")

        assertTrue(repository.getMessages("s-image").isEmpty())
        assertTrue(database.chatHistoryDao().getMessagesWithAttachments("s-image").isEmpty())
    }

}
