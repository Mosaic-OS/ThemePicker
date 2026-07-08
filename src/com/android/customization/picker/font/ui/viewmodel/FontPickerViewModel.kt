package com.android.customization.picker.font.ui.viewmodel

import com.android.customization.model.CustomizationManager
import com.android.customization.model.font.FontManager
import com.android.customization.model.font.FontOption
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FontPickerViewModel
@AssistedInject
constructor(
    private val fontManager: FontManager,
    @Assisted private val viewModelScope: CoroutineScope,
) {

    private val _fontOptions = MutableStateFlow<List<FontOption>>(emptyList())
    val fontOptions: StateFlow<List<FontOption>> = _fontOptions.asStateFlow()

    private val _selectedOption = MutableStateFlow<FontOption?>(null)
    val selectedOption: StateFlow<FontOption?> = _selectedOption.asStateFlow()

    private val _activeOption = MutableStateFlow<FontOption?>(null)
    val activeOption: StateFlow<FontOption?> = _activeOption.asStateFlow()

    private val _appliedOption = MutableStateFlow<FontOption?>(null)

    private val _showApplyDialog = MutableStateFlow(false)
    val showApplyDialog: StateFlow<Boolean> = _showApplyDialog.asStateFlow()

    private val _applyEvent = Channel<Unit>(Channel.BUFFERED)
    val applyEvent = _applyEvent.receiveAsFlow()

    // Resolved by confirmApply()/cancelApply() to release the suspended onApply() invocation.
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    val isApplyVisible: StateFlow<Boolean> =
        combine(_selectedOption, _appliedOption) { selected, applied ->
                if (selected == null || applied == null) false
                else selected.packageName != applied.packageName
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // The shared apply pipeline treats this suspend block as "the apply" and only navigates away
    // once it returns, so it must not return until the user has answered the restart-confirmation
    // dialog and (on confirm) the overlay has actually been applied.
    val onApply: Flow<(suspend () -> Unit)?> =
        isApplyVisible.map { visible -> if (visible) suspend { applyWithConfirmation() } else null }

    init {
        // fetchOptions performs per-overlay binder IPC and Typeface disk loads; keep it off the
        // main thread so opening the picker never janks/ANRs.
        viewModelScope.launch(Dispatchers.IO) {
            fontManager.fetchOptions(
                { options ->
                    _fontOptions.value = options
                    val active =
                        options.firstOrNull { fontManager.isActive(it) } ?: options.firstOrNull()
                    _selectedOption.value = active
                    _appliedOption.value = active
                    _activeOption.value = active
                },
                true,
            )
        }
    }

    fun selectFont(option: FontOption) {
        _selectedOption.value = option
    }

    private suspend fun applyWithConfirmation() {
        val option = _selectedOption.value ?: return
        val confirmation = CompletableDeferred<Boolean>()
        pendingConfirmation = confirmation
        _showApplyDialog.value = true
        val confirmed = confirmation.await()
        if (!confirmed) return

        // Enabling the overlay does blocking IPC (idmap commit in system_server) + Settings writes.
        val applied = withContext(Dispatchers.IO) { applyBlocking(option) }
        if (applied) {
            _appliedOption.value = option
            _activeOption.value = option
            _applyEvent.trySend(Unit)
        } else {
            // Apply failed: revert selection so the picker reflects the unchanged device state and
            // the user can retry, instead of falsely showing the font as applied.
            _selectedOption.value = _activeOption.value
        }
    }

    private suspend fun applyBlocking(option: FontOption): Boolean {
        val result = CompletableDeferred<Boolean>()
        fontManager.apply(
            option,
            object : CustomizationManager.Callback {
                override fun onSuccess() {
                    result.complete(true)
                }

                override fun onError(throwable: Throwable?) {
                    result.complete(false)
                }
            },
        )
        return result.await()
    }

    fun confirmApply() {
        _showApplyDialog.value = false
        pendingConfirmation?.complete(true)
        pendingConfirmation = null
    }

    fun cancelApply() {
        _showApplyDialog.value = false
        _selectedOption.value = _activeOption.value
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
    }

    @ViewModelScoped
    @AssistedFactory
    interface Factory {
        fun create(viewModelScope: CoroutineScope): FontPickerViewModel
    }
}
