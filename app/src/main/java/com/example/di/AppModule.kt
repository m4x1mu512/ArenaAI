package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.local.PreferencesManager
import com.example.data.local.RoomChatHistoryRepository
import com.example.data.local.SettingsRepositoryImpl
import com.example.data.local.db.ChatHistoryDao
import com.example.data.local.db.CoarenaDatabase
import com.example.data.remote.CoarenaApiParser
import com.example.data.remote.CoarenaRepositoryImpl
import com.example.data.remote.CoarenaRetrofitService
import com.example.data.security.AndroidKeystoreSecureStorage
import com.example.data.security.SecureKeyStorage
import com.example.domain.repository.ChatHistoryRepository
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
        ).build()
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
    fun provideChatHistoryRepository(
        impl: RoomChatHistoryRepository,
    ): ChatHistoryRepository = impl
}
