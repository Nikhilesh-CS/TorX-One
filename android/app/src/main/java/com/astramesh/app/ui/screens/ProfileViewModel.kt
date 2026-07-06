package com.astramesh.app.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val name: String = "",
    val bio: String = "",
    val statusMessage: String = "",
    val avatarUri: Uri? = null,
    val avatarLocalPath: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

class ProfileViewModel(
    private val profileRepository: ProfileRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            profileRepository.getLocalProfile().collect { profile ->
                if (profile != null) {
                    _uiState.update { state ->
                        state.copy(
                            name = profile.name,
                            bio = profile.bio,
                            statusMessage = profile.statusMessage,
                            avatarLocalPath = profile.avatarLocalPath,
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateBio(bio: String) {
        _uiState.update { it.copy(bio = bio) }
    }

    fun updateStatusMessage(status: String) {
        _uiState.update { it.copy(statusMessage = status) }
    }

    fun updateAvatar(uri: Uri?) {
        _uiState.update { it.copy(avatarUri = uri) }
    }

    fun saveProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            
            try {
                val state = _uiState.value
                profileRepository.updateLocalProfile(
                    name = state.name,
                    bio = state.bio,
                    statusMessage = state.statusMessage,
                    avatarUri = state.avatarUri
                )
                
                _uiState.update { it.copy(isSaving = false, isSuccess = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save profile") }
            }
        }
    }
}
