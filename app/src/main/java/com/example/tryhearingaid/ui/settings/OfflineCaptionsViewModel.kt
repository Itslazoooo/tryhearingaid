package com.example.tryhearingaid.ui.settings

import androidx.lifecycle.ViewModel
import com.example.tryhearingaid.captions.ModelState
import com.example.tryhearingaid.captions.VoskModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Downloads and removes the offline speech models. The downloads continue if the screen is left. */
@HiltViewModel
class OfflineCaptionsViewModel @Inject constructor(
    private val store: VoskModelStore
) : ViewModel() {
    val states: StateFlow<Map<String, ModelState>> = store.states
    fun download(language: String) = store.startDownload(language)
    fun delete(language: String) = store.delete(language)
}
