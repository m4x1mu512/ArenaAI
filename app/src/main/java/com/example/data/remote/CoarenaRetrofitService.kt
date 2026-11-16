package com.example.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit service for Coarena REST JSON endpoints using dynamic URLs built from Base URL.
 */
interface CoarenaRetrofitService {
    @GET
    suspend fun getEndpoint(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>

    @POST
    suspend fun postChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequestDto,
    ): Response<ResponseBody>
}
