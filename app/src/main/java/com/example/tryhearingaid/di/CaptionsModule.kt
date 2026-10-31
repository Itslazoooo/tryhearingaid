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
 * - [AIAssistant] is STILL A STAND-IN: it is built on [FakeGeminiClient], [FakeNetworkCallback]
 *   and an in-memory vocabulary, so summaries, translations, definitions and sound descriptions
 *   are placeholders, and the vocabulary is lost when the app closes. The real Gemini clients and
 *   the connectivity monitor are not written yet. Replace the body of [provideAIAssistant]
 *   when they are.
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
    fun provideAIAssistant(): AIAssistant = AIAssistantImpl(
        cloudClient = FakeGeminiClient(),
        onDeviceClient = FakeGeminiClient(),
        vocabularyDao = InMemoryVocabularyDao(),
        networkCallback = FakeNetworkCallback()
    )
}
