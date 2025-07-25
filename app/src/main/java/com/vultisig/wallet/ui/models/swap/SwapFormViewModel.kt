@file:OptIn(ExperimentalUuidApi::class)

package com.vultisig.wallet.ui.models.swap

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vultisig.wallet.R
import com.vultisig.wallet.data.api.errors.SwapException
import com.vultisig.wallet.data.chains.helpers.EvmHelper
import com.vultisig.wallet.data.models.Address
import com.vultisig.wallet.data.models.Chain
import com.vultisig.wallet.data.models.Coin
import com.vultisig.wallet.data.models.FiatValue
import com.vultisig.wallet.data.models.GasFeeParams
import com.vultisig.wallet.data.models.IsSwapSupported
import com.vultisig.wallet.data.models.OneInchSwapPayloadJson
import com.vultisig.wallet.data.models.SwapProvider
import com.vultisig.wallet.data.models.SwapQuote
import com.vultisig.wallet.data.models.SwapQuote.Companion.expiredAfter
import com.vultisig.wallet.data.models.SwapTransaction.RegularSwapTransaction
import com.vultisig.wallet.data.models.THORChainSwapPayload
import com.vultisig.wallet.data.models.TokenStandard
import com.vultisig.wallet.data.models.TokenValue
import com.vultisig.wallet.data.models.VaultId
import com.vultisig.wallet.data.models.payload.BlockChainSpecific
import com.vultisig.wallet.data.models.payload.SwapPayload
import com.vultisig.wallet.data.models.settings.AppCurrency
import com.vultisig.wallet.data.repositories.AccountsRepository
import com.vultisig.wallet.data.repositories.AllowanceRepository
import com.vultisig.wallet.data.repositories.AppCurrencyRepository
import com.vultisig.wallet.data.repositories.BlockChainSpecificRepository
import com.vultisig.wallet.data.repositories.GasFeeRepository
import com.vultisig.wallet.data.repositories.RequestResultRepository
import com.vultisig.wallet.data.repositories.SwapQuoteRepository
import com.vultisig.wallet.data.repositories.SwapTransactionRepository
import com.vultisig.wallet.data.repositories.TokenRepository
import com.vultisig.wallet.data.usecases.ConvertTokenAndValueToTokenValueUseCase
import com.vultisig.wallet.data.usecases.ConvertTokenValueToFiatUseCase
import com.vultisig.wallet.data.usecases.GasFeeToEstimatedFeeUseCase
import com.vultisig.wallet.data.utils.TextFieldUtils
import com.vultisig.wallet.ui.models.mappers.AccountToTokenBalanceUiModelMapper
import com.vultisig.wallet.ui.models.mappers.FiatValueToStringMapper
import com.vultisig.wallet.ui.models.mappers.TokenValueToDecimalUiStringMapper
import com.vultisig.wallet.ui.models.send.InvalidTransactionDataException
import com.vultisig.wallet.ui.models.send.SendSrc
import com.vultisig.wallet.ui.models.send.TokenBalanceUiModel
import com.vultisig.wallet.ui.navigation.Destination
import com.vultisig.wallet.ui.navigation.Navigator
import com.vultisig.wallet.ui.navigation.Route
import com.vultisig.wallet.ui.utils.UiText
import com.vultisig.wallet.ui.utils.asUiText
import com.vultisig.wallet.ui.utils.textAsFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class SwapFormUiModel(
    val selectedSrcToken: TokenBalanceUiModel? = null,
    val selectedDstToken: TokenBalanceUiModel? = null,
    val srcFiatValue: String = "0",
    val estimatedDstTokenValue: String = "0",
    val estimatedDstFiatValue: String = "0",
    val provider: UiText = UiText.Empty,
    val minimumAmount: String = BigInteger.ZERO.toString(),
    val gas: String = "",
    val fiatGas: String = "",
    val totalFee: String = "0",
    val fee: String = "",
    val error: UiText? = null,
    val formError: UiText? = null,
    val isSwapDisabled: Boolean = false,
    val isLoading: Boolean = false,
    val expiredAt: Instant? = null,
)


@HiltViewModel
internal class SwapFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val navigator: Navigator<Destination>,
    private val accountToTokenBalanceUiModelMapper: AccountToTokenBalanceUiModelMapper,
    private val mapTokenValueToDecimalUiString: TokenValueToDecimalUiStringMapper,
    private val fiatValueToString: FiatValueToStringMapper,
    private val convertTokenAndValueToTokenValue: ConvertTokenAndValueToTokenValueUseCase,

    private val allowanceRepository: AllowanceRepository,
    private val appCurrencyRepository: AppCurrencyRepository,
    private val convertTokenValueToFiat: ConvertTokenValueToFiatUseCase,
    private val accountsRepository: AccountsRepository,
    private val gasFeeRepository: GasFeeRepository,
    private val swapQuoteRepository: SwapQuoteRepository,
    private val swapTransactionRepository: SwapTransactionRepository,
    private val blockChainSpecificRepository: BlockChainSpecificRepository,
    private val tokenRepository: TokenRepository,
    private val requestResultRepository: RequestResultRepository,
    private val gasFeeToEstimatedFee: GasFeeToEstimatedFeeUseCase,
) : ViewModel() {

    private val args = savedStateHandle.toRoute<Route.Swap>()

    val uiState = MutableStateFlow(SwapFormUiModel())

    val srcAmountState = TextFieldState()

    private var vaultId: String? = null
    private var chain: Chain? = null

    private var quote: SwapQuote? = null

    private var provider: SwapProvider? = null

    private val srcAmount: BigDecimal?
        get() = srcAmountState.text.toString().toBigDecimalOrNull()

    private val selectedSrc = MutableStateFlow<SendSrc?>(null)
    private val selectedDst = MutableStateFlow<SendSrc?>(null)
    private val selectedSrcId = MutableStateFlow<String?>(null)
    private val selectedDstId = MutableStateFlow<String?>(null)

    private val gasFee = MutableStateFlow<TokenValue?>(null)
    private val swapFeeFiat = MutableStateFlow<FiatValue?>(null)
    private val gasFeeFiat = MutableStateFlow<FiatValue?>(null)

    private val addresses = MutableStateFlow<List<Address>>(emptyList())

    private val refreshQuoteState = MutableStateFlow(0)

    private var selectTokensJob: Job? = null

    private var refreshQuoteJob: Job? = null

    private var isLoading: Boolean
        get() = uiState.value.isLoading
        set(value) {
            uiState.update {
                it.copy(isLoading = value)
            }
        }

    init {
        viewModelScope.launch {
            loadData(
                vaultId = args.vaultId,
                chainId = args.chainId,
                srcTokenId = args.srcTokenId,
                dstTokenId = args.dstTokenId,
            )
        }

        collectSelectedAccounts()
        collectSelectedTokens()

        calculateGas()
        calculateFees()
        collectTotalFee()
    }

    fun back() {
        viewModelScope.launch {
            navigator.navigate(Destination.Back)
        }
    }

    fun swap() {
        try {
            // TODO verify swap info

            isLoading = true
            val vaultId = vaultId ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_no_vault)
            )
            val selectedSrc = selectedSrc.value ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_no_src_error)
            )
            val selectedDst = selectedDst.value ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_selected_no_dst)
            )

            val gasFee = gasFee.value ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_gas_fee_calculation)
            )
            val gasFeeFiatValue = gasFeeFiat.value ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_gas_fee_calculation)
            )

            val srcToken = selectedSrc.account.token
            val dstToken = selectedDst.account.token

            if (srcToken == dstToken) {
                throw InvalidTransactionDataException(
                    UiText.StringResource(R.string.swap_screen_same_asset_error_message)
                )
            }

            val srcAddress = selectedSrc.address.address

            val srcAmountInt = srcAmount
                ?.movePointRight(selectedSrc.account.token.decimal)
                ?.toBigInteger()

            val selectedSrcBalance =
                selectedSrc.account.tokenValue?.value ?: throw InvalidTransactionDataException(
                    UiText.StringResource(R.string.swap_screen_same_asset_error_message)
                )
            if (srcAmountInt == BigInteger.ZERO)
                throw InvalidTransactionDataException(
                    UiText.StringResource(R.string.swap_screen_invalid_zero_token_amount)
                )
            val srcTokenValue = srcAmountInt
                ?.let { convertTokenAndValueToTokenValue(srcToken, it) }
                ?: throw InvalidTransactionDataException(
                    UiText.StringResource(R.string.swap_screen_invalid_zero_token_amount)
                )

            val quote = quote ?: throw InvalidTransactionDataException(
                UiText.StringResource(R.string.swap_screen_invalid_quote_calculation)
            )

            val swapFee =
                quote.fees.value.takeIf { provider == SwapProvider.LIFI } ?: BigInteger.ZERO

            if (srcToken.isNativeToken) {
                if (srcAmountInt + gasFee.value + swapFee > selectedSrcBalance) {
                    throw InvalidTransactionDataException(
                        UiText.StringResource(R.string.send_error_insufficient_balance)
                    )
                }
            } else {
                val nativeTokenAccount =
                    selectedSrc.address.accounts.find { it.token.isNativeToken }
                val nativeTokenValue = nativeTokenAccount?.tokenValue?.value
                    ?: throw InvalidTransactionDataException(
                        UiText.StringResource(R.string.send_error_no_token)
                    )

                if (selectedSrcBalance < srcAmountInt
                    || nativeTokenValue < gasFee.value + swapFee
                ) {
                    throw InvalidTransactionDataException(
                        UiText.StringResource(R.string.send_error_insufficient_balance)
                    )
                }
            }


            viewModelScope.launch {
                val dstTokenValue = quote.expectedDstValue

                val specificAndUtxo = getSpecificAndUtxo(srcToken, srcAddress, gasFee)

                val transaction = when (quote) {
                    is SwapQuote.ThorChain -> {
                        val dstAddress =
                            quote.data.router ?: quote.data.inboundAddress ?: srcAddress
                        val allowance = allowanceRepository.getAllowance(
                            chain = srcToken.chain,
                            contractAddress = srcToken.contractAddress,
                            srcAddress = srcAddress,
                            dstAddress = dstAddress,
                        )
                        val isApprovalRequired =
                            allowance != null && allowance < srcTokenValue.value

                        val srcFiatValue = convertTokenValueToFiat(
                            srcToken, srcTokenValue, AppCurrency.USD,
                        )

                        val isAffiliate = srcFiatValue.value >=
                                AFFILIATE_FEE_USD_THRESHOLD.toBigDecimal()

                        RegularSwapTransaction(
                            id = UUID.randomUUID().toString(),
                            vaultId = vaultId,
                            srcToken = srcToken,
                            srcTokenValue = srcTokenValue,
                            dstToken = dstToken,
                            dstAddress = dstAddress,
                            expectedDstTokenValue = dstTokenValue,
                            blockChainSpecific = specificAndUtxo,
                            estimatedFees = quote.fees,
                            isApprovalRequired = isApprovalRequired,
                            memo = null,
                            gasFeeFiatValue = gasFeeFiatValue,
                            payload = SwapPayload.ThorChain(
                                THORChainSwapPayload(
                                    fromAddress = srcAddress,
                                    fromCoin = srcToken,
                                    toCoin = dstToken,
                                    vaultAddress = quote.data.inboundAddress ?: srcAddress,
                                    routerAddress = quote.data.router,
                                    fromAmount = srcTokenValue.value,
                                    toAmountDecimal = dstTokenValue.decimal,
                                    toAmountLimit = "0",
                                    streamingInterval = "1",
                                    streamingQuantity = "0",
                                    expirationTime = (System.currentTimeMillis().milliseconds + 15.minutes)
                                        .inWholeSeconds.toULong(),
                                    isAffiliate = isAffiliate,
                                )
                            )
                        )
                    }

                    is SwapQuote.MayaChain -> {
                        val address = quote.data.inboundAddress
                        val dstAddress = address ?: srcAddress

                        val allowance = allowanceRepository.getAllowance(
                            chain = srcToken.chain,
                            contractAddress = srcToken.contractAddress,
                            srcAddress = srcAddress,
                            dstAddress = dstAddress,
                        )
                        val isApprovalRequired =
                            allowance != null && allowance < srcTokenValue.value

                        val srcFiatValue = convertTokenValueToFiat(
                            srcToken, srcTokenValue, AppCurrency.USD,
                        )

                        val isAffiliate = srcFiatValue.value >=
                                AFFILIATE_FEE_USD_THRESHOLD.toBigDecimal()

                        val regularSwapTransaction = RegularSwapTransaction(
                            id = UUID.randomUUID().toString(),
                            vaultId = vaultId,
                            srcToken = srcToken,
                            srcTokenValue = srcTokenValue,
                            dstToken = dstToken,
                            dstAddress = dstAddress,
                            expectedDstTokenValue = dstTokenValue,
                            blockChainSpecific = specificAndUtxo,
                            estimatedFees = quote.fees,
                            memo = quote.data.memo,
                            isApprovalRequired = isApprovalRequired,
                            gasFeeFiatValue = gasFeeFiatValue,
                            payload = SwapPayload.MayaChain(
                                THORChainSwapPayload(
                                    fromAddress = srcAddress,
                                    fromCoin = srcToken,
                                    toCoin = dstToken,
                                    vaultAddress = quote.data.inboundAddress ?: srcAddress,
                                    routerAddress = quote.data.router,
                                    fromAmount = srcTokenValue.value,
                                    toAmountDecimal = dstTokenValue.decimal,
                                    toAmountLimit = "0",
                                    streamingInterval = "3",
                                    streamingQuantity = "0",
                                    expirationTime = (System.currentTimeMillis().milliseconds + 15.minutes)
                                        .inWholeSeconds.toULong(),
                                    isAffiliate = isAffiliate,
                                )
                            )
                        )

                        regularSwapTransaction
                    }

                    is SwapQuote.OneInch -> {
                        val dstAddress = quote.data.tx.to

                        val allowance = allowanceRepository.getAllowance(
                            chain = srcToken.chain,
                            contractAddress = srcToken.contractAddress,
                            srcAddress = srcAddress,
                            dstAddress = dstAddress,
                        )
                        val isApprovalRequired =
                            allowance != null && allowance < srcTokenValue.value

                        RegularSwapTransaction(
                            id = UUID.randomUUID().toString(),
                            vaultId = vaultId,
                            srcToken = srcToken,
                            srcTokenValue = srcTokenValue,
                            dstToken = dstToken,
                            dstAddress = dstAddress,
                            expectedDstTokenValue = dstTokenValue,
                            blockChainSpecific = specificAndUtxo,
                            estimatedFees = quote.fees,
                            memo = null,
                            isApprovalRequired = isApprovalRequired,
                            gasFeeFiatValue = gasFeeFiatValue,
                            payload = SwapPayload.OneInch(
                                OneInchSwapPayloadJson(
                                    fromCoin = srcToken,
                                    toCoin = dstToken,
                                    fromAmount = srcTokenValue.value,
                                    toAmountDecimal = dstTokenValue.decimal,
                                    quote = quote.data,
                                )
                            )
                        )
                    }
                }

                swapTransactionRepository.addTransaction(transaction)

                navigator.route(
                    Route.VerifySwap(
                        transactionId = transaction.id,
                        vaultId = vaultId,
                    )
                )
                isLoading = false
            }
        } catch (e: InvalidTransactionDataException) {
            isLoading = false
            showError(e.text)
            return
        }
    }

    private suspend fun getSpecificAndUtxo(
        srcToken: Coin,
        srcAddress: String,
        gasFee: TokenValue,
    ) = try {
        blockChainSpecificRepository.getSpecific(
            srcToken.chain,
            srcAddress,
            srcToken,
            gasFee,
            isSwap = true,
            isMaxAmountEnabled = false,
            isDeposit = srcToken.chain == Chain.MayaChain,
            gasLimit = getGasLimit(srcToken),
        )
    } catch (e: Exception) {
        throw InvalidTransactionDataException(
            UiText.StringResource(R.string.swap_screen_invalid_specific_and_utxo)
        )
    }

    fun selectSrcNetwork() {
        viewModelScope.launch {
            val newSendSrc = selectNetwork(
                vaultId = vaultId ?: return@launch,
                selectedChain = selectedSrc.value?.address?.chain ?: return@launch,
            ) ?: return@launch

            selectedSrcId.value = newSendSrc.account.token.id
        }
    }

    fun selectDstNetwork() {
        viewModelScope.launch {
            val newSendSrc = selectNetwork(
                vaultId = vaultId ?: return@launch,
                selectedChain = selectedDst.value?.address?.chain ?: return@launch,
            ) ?: return@launch

            selectedDstId.value = newSendSrc.account.token.id
        }
    }

    private suspend fun selectNetwork(
        vaultId: VaultId,
        selectedChain: Chain,
    ): SendSrc? {
        val requestId = Uuid.random().toString()
        navigator.route(
            Route.SelectNetwork(
                vaultId = vaultId,
                selectedNetworkId = selectedChain.id,
                requestId = requestId,
                filters = Route.SelectNetwork.Filters.SwapAvailable,
            )
        )

        val chain: Chain = requestResultRepository.request(requestId)
            ?: return null

        if (chain == selectedChain) {
            return null
        }

        return addresses.value.firstSendSrc(
            selectedTokenId = null,
            filterByChain = chain,
        )
    }

    fun selectSrcToken() {
        navigateToSelectToken(ARG_SELECTED_SRC_TOKEN_ID)
    }

    fun selectDstToken() {
        navigateToSelectToken(ARG_SELECTED_DST_TOKEN_ID)
    }

    private fun navigateToSelectToken(
        targetArg: String,
    ) {
        viewModelScope.launch {
            navigator.route(
                Route.SelectAsset(
                    vaultId = vaultId ?: return@launch,
                    requestId = targetArg,
                    preselectedNetworkId = (when (targetArg) {
                        ARG_SELECTED_SRC_TOKEN_ID -> selectedSrc.value?.address?.chain
                        ARG_SELECTED_DST_TOKEN_ID -> selectedDst.value?.address?.chain
                        else -> Chain.ThorChain
                    })?.id ?: Chain.ThorChain.id,
                    networkFilters = Route.SelectNetwork.Filters.SwapAvailable,
                )
            )
            checkTokenSelectionResponse(targetArg)
        }
    }

    private suspend fun checkTokenSelectionResponse(targetArg: String) {
        val result = requestResultRepository.request<Coin>(targetArg)?.id
        if (targetArg == ARG_SELECTED_SRC_TOKEN_ID) {
            selectedSrcId.value = result
        } else {
            selectedDstId.value = result
        }
    }

    fun flipSelectedTokens() {
        viewModelScope.launch {
            val buffer = selectedSrc.value
            selectedSrc.value = selectedDst.value
            selectedDst.value = buffer
        }
    }

    fun selectSrcPercentage(percentage: Float) {
        val selectedSrcAccount = selectedSrc.value?.account ?: return
        val srcTokenValue = selectedSrcAccount.tokenValue ?: return

        val srcToken = selectedSrcAccount.token

        val swapFee =
            quote?.fees?.value.takeIf { provider == SwapProvider.LIFI } ?: BigInteger.ZERO

        val maxUsableTokenAmount =
            srcTokenValue.value - swapFee - (gasFee.value?.value?.takeIf { srcToken.isNativeToken }
                ?: BigInteger.ZERO)

        val amount = TokenValue.createDecimal(maxUsableTokenAmount, srcTokenValue.decimals)
            .multiply(percentage.toBigDecimal())
            .setScale(6, RoundingMode.DOWN)

        srcAmountState.setTextAndPlaceCursorAtEnd(amount.toString())
    }

    fun loadData(
        vaultId: String,
        chainId: String?,
        srcTokenId: String?,
        dstTokenId: String?,
    ) {
        this.chain = chainId?.let(Chain::fromRaw)

        if (srcTokenId != null && this.selectedSrcId.value == null) {
            selectedSrcId.value = srcTokenId
        }

        if (dstTokenId != null && this.selectedDstId.value == null) {
            selectedDstId.value = dstTokenId
        }

        if (this.vaultId != vaultId) {
            this.vaultId = vaultId
            loadTokens(vaultId)
        }
    }

    fun validateAmount() {
        val errorMessage = validateSrcAmount(srcAmountState.text.toString())
        uiState.update { it.copy(error = errorMessage) }
    }

    private fun loadTokens(
        vaultId: String,
    ) {
        viewModelScope.launch {
            accountsRepository.loadAddresses(vaultId)
                .map { addresses ->
                    addresses.filter { it.chain.IsSwapSupported }
                }
                .catch {
                    // TODO handle error
                    Timber.e(it)
                }.collect(addresses)
        }
    }

    private fun collectSelectedTokens() {
        selectTokensJob?.cancel()
        selectTokensJob = viewModelScope.launch {
            combine(
                addresses,
                selectedSrcId,
                selectedDstId,
            ) { addresses, srcTokenId, dstTokenId ->
                val chain = chain
                selectedSrc.updateSrc(srcTokenId, addresses, chain)
                selectedDst.updateSrc(dstTokenId, addresses, chain)
            }.collect()
        }
    }

    private fun collectSelectedAccounts() {
        viewModelScope.launch {
            combine(
                selectedSrc,
                selectedDst,
            ) { src, dst ->
                val srcUiModel = src?.let(accountToTokenBalanceUiModelMapper::map)
                val dstUiModel = dst?.let(accountToTokenBalanceUiModelMapper::map)

                uiState.update {
                    it.copy(
                        selectedSrcToken = srcUiModel,
                        selectedDstToken = dstUiModel,
                    )
                }
            }.collect()
        }
    }

    private fun calculateGas() {
        viewModelScope.launch {
            selectedSrc
                .filterNotNull()
                .map {
                    it to gasFeeRepository.getGasFee(it.address.chain, it.address.address)
                }
                .catch {
                    // TODO handle error when querying gas fee
                    Timber.e(it)
                }
                .collect { (selectedSrc, gasFee) ->
                    this@SwapFormViewModel.gasFee.value = gasFee
                    val selectedAccount = selectedSrc.account
                    val chain = selectedAccount.token.chain
                    val selectedToken = selectedAccount.token
                    val srcAddress = selectedAccount.token.address
                    try {
                        val spec = getSpecificAndUtxo(selectedToken, srcAddress, gasFee)

                        val estimatedFee = gasFeeToEstimatedFee(
                            GasFeeParams(
                                gasLimit = if (chain.standard == TokenStandard.EVM) {
                                    (spec.blockChainSpecific as BlockChainSpecific.Ethereum).gasLimit
                                } else {
                                    BigInteger.valueOf(1)
                                },
                                gasFee = gasFee,
                                selectedToken = selectedToken,
                            )
                        )

                        gasFeeFiat.value = estimatedFee.fiatValue

                        uiState.update {
                            it.copy(
                                gas = estimatedFee.formattedTokenValue,
                                fiatGas = estimatedFee.formattedFiatValue,
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e)
                        showError(UiText.StringResource(R.string.swap_screen_invalid_gas_fee_calculation))
                    }
                }
        }
    }

    private fun collectTotalFee() {
        gasFeeFiat.filterNotNull().combine(swapFeeFiat.filterNotNull()) { gasFeeFiat, swapFeeFiat ->
            gasFeeFiat + swapFeeFiat
        }.onEach { totalFee ->
            uiState.update {
                it.copy(totalFee = fiatValueToString.map(totalFee))
            }
        }.launchIn(viewModelScope)
    }

    private fun calculateFees() {
        viewModelScope.launch {
            combine(
                selectedSrc.filterNotNull(),
                selectedDst.filterNotNull(),
            ) { src, dst -> src to dst }
                .distinctUntilChanged()
                .combine(srcAmountState.textAsFlow()) { address, amount ->
                    address to srcAmount
                }
                .combine(refreshQuoteState) { it, _ -> it }
                .collect { (address, amount) ->
                    isLoading = true
                    val (src, dst) = address

                    val srcToken = src.account.token
                    val dstToken = dst.account.token

                    val srcTokenValue = amount
                        ?.movePointRight(src.account.token.decimal)
                        ?.toBigInteger()

                    try {
                        if (srcTokenValue == null || srcTokenValue <= BigInteger.ZERO) {
                            throw SwapException.AmountCannotBeZero("Amount must be positive")
                        }
                        if (srcToken == dstToken) {
                            throw SwapException.SameAssets("Can't swap same assets ${srcToken.id})")
                        }

                        val provider = swapQuoteRepository.resolveProvider(srcToken, dstToken)
                            ?: throw SwapException.SwapIsNotSupported("Swap is not supported for this pair")

                        this@SwapFormViewModel.provider = provider


                        val tokenValue = convertTokenAndValueToTokenValue(srcToken, srcTokenValue)

                        val currency = appCurrencyRepository.currency.first()

                        val srcFiatValue =
                            convertTokenValueToFiat(srcToken, tokenValue, currency)

                        val srcFiatValueText = srcFiatValue.let {
                            fiatValueToString.map(it)
                        }

                        val srcNativeToken = tokenRepository.getNativeToken(srcToken.chain.id)

                        when (provider) {
                            SwapProvider.MAYA, SwapProvider.THORCHAIN -> {
                                val srcUsdFiatValue = convertTokenValueToFiat(
                                    srcToken, tokenValue, AppCurrency.USD,
                                )

                                val isAffiliate =
                                    srcUsdFiatValue.value >= AFFILIATE_FEE_USD_THRESHOLD.toBigDecimal()

                                val (quote, recommendedMinAmountToken) = if (provider == SwapProvider.MAYA) {
                                    val mayaSwapQuote = swapQuoteRepository.getMayaSwapQuote(
                                        dstAddress = dst.address.address,
                                        srcToken = srcToken,
                                        dstToken = dstToken,
                                        tokenValue = tokenValue,
                                        isAffiliate = isAffiliate,
                                    )
                                    mayaSwapQuote as SwapQuote.MayaChain to mayaSwapQuote.recommendedMinTokenValue
                                } else {
                                    val thorSwapQuote = swapQuoteRepository.getSwapQuote(
                                        dstAddress = dst.address.address,
                                        srcToken = srcToken,
                                        dstToken = dstToken,
                                        tokenValue = tokenValue,
                                        isAffiliate = isAffiliate,
                                    )
                                    thorSwapQuote as SwapQuote.ThorChain to thorSwapQuote.recommendedMinTokenValue
                                }
                                this@SwapFormViewModel.quote = quote

                                val recommendedMinAmountTokenString =
                                    mapTokenValueToDecimalUiString(recommendedMinAmountToken)
                                amount.let {
                                    uiState.update {
                                        if (amount < recommendedMinAmountToken.decimal) {
                                            it.copy(
                                                minimumAmount = recommendedMinAmountTokenString,
                                                isSwapDisabled = true
                                            )
                                        } else {
                                            it.copy(
                                                minimumAmount = BigInteger.ZERO.toString(),
                                                isSwapDisabled = false
                                            )
                                        }
                                    }
                                }

                                val fiatFees =
                                    convertTokenValueToFiat(dstToken, quote.fees, currency)
                                swapFeeFiat.value = fiatFees

                                val estimatedDstTokenValue =
                                    mapTokenValueToDecimalUiString(
                                        quote.expectedDstValue
                                    )

                                val estimatedDstFiatValue = convertTokenValueToFiat(
                                    dstToken,
                                    quote.expectedDstValue,
                                    currency
                                )

                                uiState.update {
                                    it.copy(
                                        provider = if (provider == SwapProvider.MAYA)
                                            R.string.swap_form_provider_mayachain.asUiText()
                                        else
                                            R.string.swap_form_provider_thorchain.asUiText(),
                                        srcFiatValue = srcFiatValueText,
                                        estimatedDstTokenValue = estimatedDstTokenValue,
                                        estimatedDstFiatValue = fiatValueToString.map(
                                            estimatedDstFiatValue
                                        ),
                                        fee = fiatValueToString.map(fiatFees),
                                        formError = null,
                                        isSwapDisabled = false,
                                        isLoading = false,
                                        expiredAt = this@SwapFormViewModel.quote?.expiredAt,
                                    )
                                }
                            }

                            SwapProvider.ONEINCH -> {
                                val srcUsdFiatValue = convertTokenValueToFiat(
                                    srcToken, tokenValue, AppCurrency.USD,
                                )

                                val isAffiliate =
                                    srcUsdFiatValue.value >= AFFILIATE_FEE_USD_THRESHOLD.toBigDecimal()

                                val quote = swapQuoteRepository.getOneInchSwapQuote(
                                    srcToken = srcToken,
                                    dstToken = dstToken,
                                    tokenValue = tokenValue,
                                    isAffiliate = isAffiliate,
                                )

                                val expectedDstValue = TokenValue(
                                    value = quote.dstAmount.toBigInteger(),
                                    token = dstToken,
                                )

                                val tokenFees = TokenValue(
                                    value = quote.tx.gasPrice.toBigInteger() *
                                            (quote.tx.gas.takeIf { it != 0L }
                                                ?: EvmHelper.DEFAULT_ETH_SWAP_GAS_UNIT).toBigInteger(),
                                    token = srcNativeToken
                                )

                                this@SwapFormViewModel.quote = SwapQuote.OneInch(
                                    expectedDstValue = expectedDstValue,
                                    fees = tokenFees,
                                    data = quote,
                                    expiredAt = Clock.System.now() + expiredAfter
                                )

                                val fiatFees =
                                    convertTokenValueToFiat(srcNativeToken, tokenFees, currency)
                                swapFeeFiat.value = fiatFees

                                val estimatedDstTokenValue =
                                    mapTokenValueToDecimalUiString(expectedDstValue)

                                val estimatedDstFiatValue = convertTokenValueToFiat(
                                    dstToken,
                                    expectedDstValue, currency
                                )

                                uiState.update {
                                    it.copy(
                                        provider = R.string.swap_for_provider_1inch.asUiText(),
                                        srcFiatValue = srcFiatValueText,
                                        estimatedDstTokenValue = estimatedDstTokenValue,
                                        estimatedDstFiatValue = fiatValueToString.map(
                                            estimatedDstFiatValue
                                        ),
                                        fee = fiatValueToString.map(fiatFees),
                                        formError = null,
                                        isSwapDisabled = false,
                                        isLoading = false,
                                        expiredAt = this@SwapFormViewModel.quote?.expiredAt,
                                    )
                                }
                            }

                            SwapProvider.LIFI, SwapProvider.JUPITER -> {
                                val quote =
                                    if (provider == SwapProvider.LIFI) swapQuoteRepository.getLiFiSwapQuote(
                                        srcAddress = src.address.address,
                                        dstAddress = dst.address.address,
                                        srcToken = srcToken,
                                        dstToken = dstToken,
                                        tokenValue = tokenValue,
                                    ) else swapQuoteRepository.getJupiterSwapQuote(
                                        srcAddress = src.address.address,
                                        srcToken = srcToken,
                                        dstToken = dstToken,
                                        tokenValue = tokenValue
                                    )

                                val expectedDstValue = TokenValue(
                                    value = quote.dstAmount.toBigInteger(),
                                    token = dstToken,
                                )

                                val tokenFees = TokenValue(
                                    value = quote.tx.swapFee.toBigInteger(),
                                    token = srcNativeToken
                                )

                                this@SwapFormViewModel.quote = SwapQuote.OneInch(
                                    expectedDstValue = expectedDstValue,
                                    fees = tokenFees,
                                    data = quote,
                                    expiredAt = Clock.System.now() + expiredAfter
                                )

                                val fiatFees =
                                    convertTokenValueToFiat(srcNativeToken, tokenFees, currency)
                                swapFeeFiat.value = fiatFees
                                val estimatedDstTokenValue =
                                    mapTokenValueToDecimalUiString(expectedDstValue)

                                val estimatedDstFiatValue = convertTokenValueToFiat(
                                    dstToken,
                                    expectedDstValue, currency
                                )

                                uiState.update {
                                    it.copy(
                                        provider = if (provider == SwapProvider.LIFI) {
                                            R.string.swap_for_provider_li_fi.asUiText()
                                        } else {
                                            R.string.swap_for_provider_jupiter.asUiText()
                                        },
                                        srcFiatValue = srcFiatValueText,
                                        estimatedDstTokenValue = estimatedDstTokenValue,
                                        estimatedDstFiatValue = fiatValueToString.map(
                                            estimatedDstFiatValue
                                        ),
                                        fee = fiatValueToString.map(fiatFees),
                                        formError = null,
                                        isSwapDisabled = false,
                                        isLoading = false,
                                        expiredAt = this@SwapFormViewModel.quote?.expiredAt,
                                    )
                                }
                            }
                        }
                    } catch (e: SwapException) {
                        val formError = when (e) {
                            is SwapException.SwapIsNotSupported ->
                                UiText.StringResource(R.string.swap_route_not_available)

                            is SwapException.AmountCannotBeZero ->
                                UiText.StringResource(R.string.swap_form_invalid_amount)

                            is SwapException.SameAssets ->
                                UiText.StringResource(R.string.swap_screen_same_asset_error_message)

                            is SwapException.UnkownSwapError ->
                                UiText.DynamicString(e.message ?: "Unknown error")

                            is SwapException.InsufficentSwapAmount ->
                                UiText.StringResource(R.string.swap_error_amount_too_low)

                            is SwapException.SwapRouteNotAvailable ->
                                UiText.StringResource(R.string.swap_route_not_available)

                            is SwapException.TimeOut ->
                                UiText.StringResource(R.string.swap_error_time_out)

                            is SwapException.NetworkConnection ->
                                UiText.StringResource(R.string.network_connection_lost)
                            is SwapException.SmallSwapAmount ->
                                UiText.StringResource(R.string.swap_error_small_swap_amount)
                        }
                        uiState.update {
                            it.copy(
                                provider = UiText.Empty,
                                srcFiatValue = "0",
                                estimatedDstTokenValue = "0",
                                estimatedDstFiatValue = "0",
                                fee = "0",
                                isSwapDisabled = true,
                                formError = formError,
                                isLoading = false,
                                expiredAt = null,
                            )
                        }
                        Timber.e("swapError $e")
                    } catch (e: Exception) {
                        // TODO handle error
                        isLoading = false
                        Timber.e(e)
                    }

                    this@SwapFormViewModel.quote?.expiredAt?.let {
                        launchRefreshQuoteTimer(it)
                    }
                }
        }
    }

    private fun launchRefreshQuoteTimer(expiredAt: Instant) {
        refreshQuoteJob?.cancel()
        refreshQuoteJob = viewModelScope.launch {
            withContext (Dispatchers.IO) {
                delay(expiredAt - Clock.System.now())
                refreshQuoteState.value++
            }
        }
    }

    private fun validateSrcAmount(srcAmount: String): UiText? {
        if (srcAmount.isEmpty() || srcAmount.length > TextFieldUtils.AMOUNT_MAX_LENGTH) {
            return UiText.StringResource(R.string.swap_form_invalid_amount)
        }
        val srcAmountAmountBigDecimal = srcAmount.toBigDecimalOrNull()
        if (srcAmountAmountBigDecimal == null || srcAmountAmountBigDecimal <= BigDecimal.ZERO) {
            return UiText.StringResource(R.string.swap_error_no_amount)
        }
        return null
    }

    fun hideError() {
        uiState.update {
            it.copy(error = null)
        }
    }

    private fun showError(error: UiText) {
        uiState.update {
            it.copy(error = error)
        }
    }

    private fun getGasLimit(
        token: Coin
    ): BigInteger? {
        val isEVMSwap =
            token.isNativeToken &&
                    token.chain in listOf(Chain.Ethereum, Chain.Arbitrum)
        return if (isEVMSwap)
            BigInteger.valueOf(
                if (token.chain == Chain.Ethereum)
                    ETH_GAS_LIMIT else ARB_GAS_LIMIT
            ) else null
    }

    companion object {
        const val AFFILIATE_FEE_USD_THRESHOLD = 10000000
        const val ETH_GAS_LIMIT: Long = 40_000
        const val ARB_GAS_LIMIT: Long = 400_000

        private const val ARG_SELECTED_SRC_TOKEN_ID = "ARG_SELECTED_SRC_TOKEN_ID"
        private const val ARG_SELECTED_DST_TOKEN_ID = "ARG_SELECTED_DST_TOKEN_ID"

    }

}


internal fun MutableStateFlow<SendSrc?>.updateSrc(
    selectedTokenId: String?,
    addresses: List<Address>,
    chain: Chain?,
) {
    val selectedSrcValue = value
    value = if (addresses.isEmpty()) {
        null
    } else {
        if (selectedSrcValue == null) {
            addresses.firstSendSrc(selectedTokenId, chain)
        } else {
            addresses.findCurrentSrc(selectedTokenId, selectedSrcValue)
        }
    }
}

internal fun List<Address>.firstSendSrc(
    selectedTokenId: String?,
    filterByChain: Chain?,
): SendSrc {
    val address = when {
        selectedTokenId != null -> first { it -> it.accounts.any { it.token.id == selectedTokenId } }
        filterByChain != null -> first { it.chain == filterByChain }
        else -> first()
    }

    val account = when {
        selectedTokenId != null -> address.accounts.first { it.token.id == selectedTokenId }
        filterByChain != null -> address.accounts.first { it.token.isNativeToken }
        else -> address.accounts.first()
    }

    return SendSrc(address, account)
}

internal fun List<Address>.findCurrentSrc(
    selectedTokenId: String?,
    currentSrc: SendSrc,
): SendSrc {
    if (selectedTokenId == null) {
        val selectedAddress = currentSrc.address
        val selectedAccount = currentSrc.account
        val address = first {
            it.chain == selectedAddress.chain &&
                    it.address == selectedAddress.address
        }
        return SendSrc(
            address,
            address.accounts.first {
                it.token.ticker == selectedAccount.token.ticker
            },
        )
    } else {
        return firstSendSrc(selectedTokenId, null)
    }
}
