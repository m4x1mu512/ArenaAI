package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.PreferencesManager
import com.example.data.local.RoomChatHistoryRepository
import com.example.data.local.SettingsRepositoryImpl
import com.example.data.local.db.ChatHistoryDao
import com.example.data.local.db.CoarenaDatabase
import com.example.data.remote.CoarenaApiParser
import com.example.data.remote.CoarenaChatProviderAdapter
import com.example.data.remote.CoarenaRepositoryImpl
import com.example.data.remote.CoarenaRetrofitService
import com.example.data.security.AndroidKeystoreSecureStorage
import com.example.data.security.ProviderCredentialStorage
import com.example.data.security.SecureKeyStorage
import com.example.domain.repository.ChatHistoryRepository
import com.example.domain.repository.ChatProvider
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt singleton module providing storage, Room database, serialization, networking, and repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Adds image attachment metadata without altering or discarding existing chat history.
     * Image bytes remain in app-private files; Room stores only their metadata and paths.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_attachments (
                    id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    file_name TEXT,
                    size_bytes INTEGER NOT NULL,
                    width INTEGER,
                    height INTEGER,
                    attachment_kind TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(message_id) REFERENCES chat_messages(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_attachments_message_id ON chat_attachments(message_id)",
            )
        }
    }

    @Provides
    @Singleton
    fun provideJson(): Json = CoarenaApiParser.DefaultJson

    @Provides
    @Singleton
    fun provideApiParser(json: Json): CoarenaApiParser = CoarenaApiParser(json)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofitService(
        okHttpClient: OkHttpClient,
        json: Json,
    ): CoarenaRetrofitService {
        return Retrofit.Builder()
            .baseUrl("https://coarena.ai/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CoarenaRetrofitService::class.java)
    }

    @Provides
    @Singleton
    fun provideCoarenaDatabase(
        @ApplicationContext context: Context,
    ): CoarenaDatabase {
        return Room.databaseBuilder(
            context,
            CoarenaDatabase::class.java,
            "coarena_chat.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideChatHistoryDao(database: CoarenaDatabase): ChatHistoryDao {
        return database.chatHistoryDao()
    }

    @Provides
    @Singleton
    fun provideSecureKeyStorage(
        @ApplicationContext context: Context,
    ): SecureKeyStorage = AndroidKeystoreSecureStorage(context)

    @Provides
    @Singleton
    fun provideProviderCredentialStorage(
        storage: AndroidKeystoreSecureStorage,
    ): ProviderCredentialStorage = storage

    @Provides
    @Singleton
    fun providePreferencesManager(
        @ApplicationContext context: Context,
    ): PreferencesManager = PreferencesManager(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        impl: SettingsRepositoryImpl,
    ): SettingsRepository = impl

    @Provides
    @Singleton
    fun provideCoarenaRepository(
        impl: CoarenaRepositoryImpl,
    ): CoarenaRepository = impl

    @Provides
    @Singleton
    fun provideChatProvider(
        adapter: CoarenaChatProviderAdapter,
    ): ChatProvider = adapter

    @Provides
    @Singleton
    fun provideChatHistoryRepository(
        impl: RoomChatHistoryRepository,
    ): ChatHistoryRepository = impl
}
