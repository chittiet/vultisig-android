package com.vultisig.wallet.ui.screens.swap

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vultisig.wallet.R
import com.vultisig.wallet.ui.components.TokenLogo
import com.vultisig.wallet.ui.components.UiAlertDialog
import com.vultisig.wallet.ui.components.UiIcon
import com.vultisig.wallet.ui.components.UiSpacer
import com.vultisig.wallet.ui.components.buttons.VsButton
import com.vultisig.wallet.ui.components.buttons.VsButtonState
import com.vultisig.wallet.ui.components.buttons.VsButtonVariant
import com.vultisig.wallet.ui.components.inputs.VsBasicTextField
import com.vultisig.wallet.ui.components.library.form.FormDetails2
import com.vultisig.wallet.ui.components.library.form.FormError
import com.vultisig.wallet.ui.components.selectors.ChainSelector
import com.vultisig.wallet.ui.components.topbar.VsTopAppBar
import com.vultisig.wallet.ui.components.util.CutoutPosition
import com.vultisig.wallet.ui.components.util.RoundedWithCutoutShape
import com.vultisig.wallet.ui.models.send.TokenBalanceUiModel
import com.vultisig.wallet.ui.models.swap.SwapFormUiModel
import com.vultisig.wallet.ui.models.swap.SwapFormViewModel
import com.vultisig.wallet.ui.theme.Theme
import com.vultisig.wallet.ui.utils.asString
import java.math.BigInteger

@Composable
internal fun SwapScreen(
    model: SwapFormViewModel = hiltViewModel(),
) {
    val state by model.uiState.collectAsState()

    SwapScreen(
        state = state,
        srcAmountTextFieldState = model.srcAmountState,
        onBackClick = model::back,
        onAmountLostFocus = model::validateAmount,
        onSwap = model::swap,
        onSelectSrcNetworkClick = model::selectSrcNetwork,
        onSelectSrcToken = model::selectSrcToken,
        onSelectDstNetworkClick = model::selectDstNetwork,
        onDismissError = model::hideError,
        onSelectDstToken = model::selectDstToken,
        onFlipSelectedTokens = model::flipSelectedTokens,
    )
}


@Composable
internal fun SwapScreen(
    state: SwapFormUiModel,
    srcAmountTextFieldState: TextFieldState,
    onBackClick: () -> Unit = {},
    onAmountLostFocus: () -> Unit = {},
    onSelectSrcNetworkClick: () -> Unit = {},
    onSelectSrcToken: () -> Unit = {},
    onSelectDstNetworkClick: () -> Unit = {},
    onSelectDstToken: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onFlipSelectedTokens: () -> Unit = {},
    onSwap: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        containerColor = Theme.colors.backgrounds.primary,
        topBar = {
            VsTopAppBar(
                title = "Swap",
                onBackClick = onBackClick,
            )
        },
        content = { contentPadding ->
            val errorText = state.error
            if (errorText != null) {
                UiAlertDialog(
                    title = stringResource(R.string.dialog_default_error_title),
                    text = errorText.asString(),
                    confirmTitle = stringResource(R.string.try_again),
                    onDismiss = onDismissError,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(
                        horizontal = 16.dp,
                        vertical = 16.dp
                    ),
            ) {
                Box {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TokenInput(
                            title = "From",
                            selectedToken = state.selectedSrcToken,
                            fiatValue = state.srcFiatValue,
                            onSelectNetworkClick = onSelectSrcNetworkClick,
                            onSelectTokenClick = onSelectSrcToken,
                            shape = RoundedWithCutoutShape(
                                cutoutPosition = CutoutPosition.Bottom,
                                cutoutOffsetY = (-4).dp,
                                cutoutRadius = 28.dp,
                            ),
                            focused = true,
                            textFieldContent = {
                                VsBasicTextField(
                                    textFieldState = srcAmountTextFieldState,
                                    style = Theme.brockmann.headings.title2,
                                    color = Theme.colors.text.light,
                                    textAlign = TextAlign.End,
                                    hint = "0",
                                    hintColor = Theme.colors.text.extraLight,
                                    hintStyle = Theme.brockmann.headings.title2,
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    // TODO onAmountLostFocus
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                )
                            }
                        )

                        TokenInput(
                            title = "To",
                            selectedToken = state.selectedDstToken,
                            fiatValue = state.estimatedDstFiatValue,
                            onSelectNetworkClick = onSelectDstNetworkClick,
                            onSelectTokenClick = onSelectDstToken,
                            shape = RoundedWithCutoutShape(
                                cutoutPosition = CutoutPosition.Top,
                                cutoutOffsetY = (-4).dp,
                                cutoutRadius = 28.dp,
                            ),
                            focused = false,
                            textFieldContent = {
                                Text(
                                    text = state.estimatedDstTokenValue,
                                    style = Theme.brockmann.headings.title2,
                                    color = Theme.colors.text.light,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                )
                            }
                        )
                    }

                    val rotation = remember { Animatable(0f) }

                    // Trigger spin when this is incremented
                    var spinTrigger by remember { mutableStateOf(0) }

                    // Launch the animation every time trigger changes
                    LaunchedEffect(spinTrigger) {
                        rotation.snapTo(0f)
                        rotation.animateTo(
                            targetValue = 180f,
                            animationSpec = tween(
                                durationMillis = 600,
                                easing = FastOutSlowInEasing
                            )
                        )
                    }

                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_bottom_top),
                        contentDescription = null,
                        tint = Theme.colors.text.primary,
                        modifier = Modifier
                            .clickable {
                                spinTrigger++
                                onFlipSelectedTokens()
                            }
                            .background(
                                color = Theme.colors.persianBlue400,
                                shape = CircleShape,
                            )
                            .padding(all = 8.dp)
                            .align(Alignment.Center)
                            .size(24.dp)
                            .graphicsLayer {
                                rotationZ = rotation.value
                            },
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .padding(
                            horizontal = 8.dp,
                        )
                ) {
                    FormDetails2(
                        title = stringResource(R.string.swap_screen_provider_title),
                        value = state.provider.asString(),
                    )

                    FormDetails2(
                        title = stringResource(R.string.swap_form_estimated_fees_title),
                        value = state.fee
                    )

                    FormDetails2(
                        modifier = Modifier.fillMaxWidth(),
                        title = buildAnnotatedString {
                            append(stringResource(R.string.swap_form_gas_title))
                        },
                        value = buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    color = Theme.colors.neutral100,
                                )
                            ) {
                                append(state.gas)
                            }
                            append(" ")
                            withStyle(
                                style = SpanStyle(
                                    color = Theme.colors.neutral400,
                                )
                            ) {
                                append(
                                    if (state.fiatGas.isNotEmpty())
                                        "(~${state.fiatGas})"
                                    else ""
                                )
                            }
                        }
                    )

                    FormDetails2(
                        title = stringResource(R.string.swap_form_total_fees_title),
                        value = state.totalFee
                    )
                }

                when {
                    state.formError != null -> {
                        FormError(
                            errorMessage = state.formError.asString()
                        )
                    }

                    state.minimumAmount != BigInteger.ZERO.toString() -> {
                        FormError(
                            errorMessage = stringResource(
                                R.string.swap_form_minimum_amount,
                                state.minimumAmount,
                                state.selectedSrcToken?.title ?: ""
                            )
                        )
                    }
                }
            }

        },
        bottomBar = {
            VsButton(
                label = stringResource(R.string.swap_swap_button),
                variant = VsButtonVariant.Primary,
                state = if (state.isSwapDisabled)
                    VsButtonState.Disabled
                else VsButtonState.Enabled,
                onClick = {
                    focusManager.clearFocus(true)
                    onSwap()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = 12.dp,
                        horizontal = 24.dp,
                    )
            )
        }
    )
}

@Composable
private fun TokenInput(
    title: String,
    selectedToken: TokenBalanceUiModel?,
    fiatValue: String,
    onSelectNetworkClick: () -> Unit,
    onSelectTokenClick: () -> Unit,
    shape: Shape,
    focused: Boolean,
    modifier: Modifier = Modifier,
    @SuppressLint("ComposableLambdaParameterNaming")
    textFieldContent: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .background(
                color = if (focused)
                    Theme.colors.backgrounds.secondary
                else
                    Theme.colors.backgrounds.disabled,
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Theme.colors.borders.light,
                shape = shape,
            )
            .padding(
                all = 16.dp,
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val selectedChain = selectedToken?.model?.address?.chain

            if (selectedChain != null) {
                ChainSelector(
                    title = title,
                    chain = selectedChain,
                    onClick = onSelectNetworkClick
                )
            }

            // TODO loader if empty
            Text(
                text = selectedToken?.let { "${it.balance} ${it.title}" } ?: "",
                style = Theme.brockmann.supplementary.caption,
                color = Theme.colors.text.extraLight,
                textAlign = TextAlign.End,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onSelectTokenClick)
                    .background(
                        color = Theme.colors.backgrounds.tertiary,
                        shape = RoundedCornerShape(99.dp)
                    )
                    .padding(
                        all = 6.dp,
                    )
            ) {
                TokenLogo(
                    errorLogoModifier = Modifier
                        .size(32.dp)
                        .background(Theme.colors.neutral100),
                    logo = selectedToken?.tokenLogo ?: "",
                    title = selectedToken?.title ?: "",
                    modifier = Modifier
                        .size(32.dp)
                )

                UiSpacer(8.dp)

                Column {
                    Text(
                        // TODO loader
                        text = selectedToken?.title ?: "",
                        style = Theme.brockmann.supplementary.caption,
                        color = Theme.colors.text.primary,
                    )

                    if (selectedToken?.isNativeToken == true) {
                        Text(
                            text = "Native",
                            style = Theme.brockmann.supplementary.captionSmall,
                            color = Theme.colors.text.extraLight,
                        )
                    }
                }

                UiSpacer(4.dp)

                UiIcon(
                    drawableResId = R.drawable.ic_chevron_right_small,
                    size = 20.dp,
                    tint = Theme.colors.text.primary,
                )

                UiSpacer(6.dp)
            }


            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                textFieldContent()

                Text(
                    text = fiatValue,
                    style = Theme.brockmann.supplementary.caption,
                    color = Theme.colors.text.extraLight,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}


@Preview
@Composable
internal fun SwapFormScreenPreview() {
    SwapScreen(
        state = SwapFormUiModel(
            estimatedDstTokenValue = "0",
        ),
        srcAmountTextFieldState = TextFieldState(),
    )
}