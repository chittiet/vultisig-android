package com.vultisig.wallet.ui.models

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vultisig.wallet.R
import com.vultisig.wallet.data.common.fileContent
import com.vultisig.wallet.data.common.fileName
import com.vultisig.wallet.data.models.SigningLibType
import com.vultisig.wallet.data.models.Vault
import com.vultisig.wallet.data.repositories.VaultDataStoreRepository
import com.vultisig.wallet.data.usecases.DiscoverTokenUseCase
import com.vultisig.wallet.data.usecases.DuplicateVaultException
import com.vultisig.wallet.data.usecases.ParseVaultFromStringUseCase
import com.vultisig.wallet.data.usecases.SaveVaultUseCase
import com.vultisig.wallet.data.usecases.backup.FILE_ALLOWED_EXTENSIONS
import com.vultisig.wallet.ui.navigation.Destination
import com.vultisig.wallet.ui.navigation.NavigationOptions
import com.vultisig.wallet.ui.navigation.Navigator
import com.vultisig.wallet.ui.navigation.Route
import com.vultisig.wallet.ui.utils.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

internal data class ImportFileState(
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val fileContent: String? = null,

    val error: UiText? = null,
    val showPasswordPrompt: Boolean = false,
    val password: String? = null,
    val isPasswordObfuscated: Boolean = true,
    val passwordErrorHint: UiText? = null,
)

internal val FILE_ALLOWED_MIME_TYPES = arrayOf("application/*", "text/plain")

@HiltViewModel
internal class ImportFileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val navigator: Navigator<Destination>,
    @ApplicationContext private val context: Context,
    private val vaultDataStoreRepository: VaultDataStoreRepository,
    private val saveVault: SaveVaultUseCase,
    private val parseVaultFromString: ParseVaultFromStringUseCase,
    private val discoverToken: DiscoverTokenUseCase,
) : ViewModel() {

    private val args = savedStateHandle.toRoute<Route.ImportVault>()

    val uiModel = MutableStateFlow(ImportFileState())

    val passwordTextFieldState = TextFieldState()

    private val snackBarChannel = Channel<UiText?>()
    val snackBarChannelFlow = snackBarChannel.receiveAsFlow()

    init {
        args.uri?.toUri()?.let {
            fetchFileName(it)
        }
    }

    fun hidePasswordPromptDialog() {
        uiModel.update {
            it.copy(showPasswordPrompt = false)
        }
    }

    fun decryptVaultData() {
        val key: String = passwordTextFieldState.text.toString()
        val vaultFileContent = uiModel.value.fileContent
        if (vaultFileContent != null) {
            viewModelScope.launch {
                try {
                    saveToDb(vaultFileContent, key)
                    hidePasswordPromptDialog()
                } catch (e: Exception) {
                    Timber.e(e)
                    showErrorHint()
                }
            }
        }
    }


    private fun parseFileContent(fileContent: String?, fileName: String?) {
        if (fileContent == null)
            return
        viewModelScope.launch {
            try {
                saveToDb(fileContent, null)
            } catch (e: Exception) {
                Timber.e(e)
                uiModel.update {
                    it.copy(
                        showPasswordPrompt = true,
                        passwordErrorHint = null,
                    )
                }
            }

        }
    }

    private suspend fun saveToDb(fileContent: String, password: String?) {
        try {
            insertVaultToDb(parseVaultFromString(fileContent, password))
        } catch (e: DuplicateVaultException) {
            Timber.e(e)
            snackBarChannel.send(UiText.StringResource(R.string.import_file_screen_duplicate_vault))
        }
    }

    private suspend fun insertVaultToDb(vault: Vault) {
        // if the backup didn't set libtype correctly , then we need a way to override it manually
        // when the backup file has share\d+of\d+ in the filename, then it's a DKLS vault
        val regex = "share\\d+of\\d+".toRegex()
        if(uiModel.value.fileName?.contains(regex) == true) {
            vault.libType = SigningLibType.DKLS
        }
        saveVault(vault, false)
        vaultDataStoreRepository.setBackupStatus(vault.id, true)
        discoverToken(vault.id, null)
        navigator.navigate(
            Destination.Home(
                openVaultId = vault.id,
            ),
            opts = NavigationOptions(clearBackStack = true)
        )
    }

    fun saveFileToAppDir() {
        val uri = uiModel.value.fileUri ?: return
        val fileContent = uri.fileContent(context)
        val fileName = uri.fileName(context)
        uiModel.update {
            it.copy(fileContent = fileContent)
        }
        parseFileContent(fileContent, fileName)
    }

    fun fetchFileName(uri: Uri?) {
        viewModelScope.launch {
            if (uri == null)
                return@launch
            val fileName = uri.fileName(context)
            val ext = fileName.substringAfterLast(".").lowercase()
            if (!FILE_ALLOWED_EXTENSIONS.contains(ext)) {
                uiModel.update {
                    it.copy(
                        fileUri = null,
                        fileName = null,
                        fileContent = null,
                        error = UiText.StringResource(R.string.import_file_not_supported)
                    )
                }
            } else {
                uiModel.update {
                    it.copy(
                        fileUri = uri,
                        fileName = fileName,
                        error = null,
                    )
                }
            }
        }
    }

    private fun showErrorHint() {
        uiModel.update {
            it.copy(passwordErrorHint = UiText.StringResource(R.string.import_file_screen_password_error))
        }
    }

    fun togglePasswordVisibility() {
        val passwordVisibility = uiModel.value.isPasswordObfuscated
        uiModel.update {
            it.copy(isPasswordObfuscated = !passwordVisibility)
        }
    }
}