package hr.sonicpulse.app.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.remote.ApiKeyInterceptor
import hr.sonicpulse.app.data.remote.DetectionApi
import hr.sonicpulse.app.data.remote.HotspotApi
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ApiKeyInterceptor(apiKey = BuildConfig.API_KEY))
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideDetectionApi(retrofit: Retrofit): DetectionApi = retrofit.create(DetectionApi::class.java)

    @Provides
    @Singleton
    fun provideHotspotApi(retrofit: Retrofit): HotspotApi = retrofit.create(HotspotApi::class.java)
}
