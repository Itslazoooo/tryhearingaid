package com.example.tryhearingaid.di

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.ai.AIAssistantImpl
import com.example.tryhearingaid.ai.FakeGeminiClient
import com.example.tryhearingaid.ai.FakeNetworkCallback
import com.example.tryhearingaid.ai.InMemoryVocabularyDao
import com.example.tryhearingaid.captions.CaptionEngine
import com.example.tryhearingaid.captions.CaptionEngineImpl
import com.example.tryhearingaid.captions.TranscriptStore
import com.example.tryhearingaid.captions.TranscriptStoreImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides [CaptionEngine], [TranscriptStore], and [AIAssistant] bindings.
 *
 * - [CaptionEngine] is the real speech-recognizer implementation ([CaptionEngineImpl]).
 * - [TranscriptStore] is the real database-backed implementation ([TranscriptStoreImpl]); its
 *   DAO comes from [DataModule].
 * - [AIAssistant] runs the small offline model ([com.example.tryhearingaid.ai.LocalLlmClient])
 *   for summaries and study notes once it is downloaded. The cloud client, the connectivity
 *   monitor and the vocabulary store are still stand-ins (the app always counts as offline, and
 *   the vocabulary is lost when the app closes). Sound descriptions are written ahead of time.
 */
@Module
@InstallIn(SingletonComponent::class)
object CaptionsModule {

    @Provides
    @Singleton
    fun provideCaptionEngine(impl: com.example.tryhearingaid.captions.CompositeCaptionEngine): CaptionEngine = impl

    @Provides
    @Singleton
    fun provideTranscriptStore(impl: TranscriptStoreImpl): TranscriptStore = impl

    @Provides
    @Singleton
    fun provideAIAssistant(
        localLlm: com.example.tryhearingaid.ai.LocalLlmClient,
        modelStore: com.example.tryhearingaid.ai.LlmModelStore
    ): AIAssistant = AIAssistantImpl(
        cloudClient = FakeGeminiClient(),
        onDeviceClient = localLlm,
        vocabularyDao = InMemoryVocabularyDao(),
        networkCallback = FakeNetworkCallback(),
        // A small model on a phone takes a while; allow minutes rather than seconds.
        shortTimeoutMs = 180_000L,
        longTimeoutMs = 300_000L,
        // About 550 tokens of transcript per request; the model holds 1280 tokens in all.
        maxPromptChars = 2_200,
        onDeviceReady = { modelStore.isReady() }
    )
}
