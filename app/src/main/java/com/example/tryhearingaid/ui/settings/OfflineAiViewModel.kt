package com.example.tryhearingaid.ui.settings

import androidx.lifecycle.ViewModel
import com.example.tryhearingaid.ai.LlmModelStore
import com.example.tryhearingaid.captions.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Downloads and removes the offline AI model. The download continues if the screen is left. */
@HiltViewModel
class OfflineAiViewModel @Inject constructor(
    private val store: LlmModelStore
) : ViewModel() {
    val state: StateFlow<ModelState> = store.state
    fun download() = store.startDownload()
    fun delete() = store.delete()
}
