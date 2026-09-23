package io.github.hatake716.dango.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.ui.theme.DangoTheme

/** フルアクセス権限のオンボーディング（SPEC §3: 拒否されても通常モードで継続） */
@Composable
fun OnboardingScreen(
    onGrant: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = DangoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(text = "🍡", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            color = colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_body),
            color = colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        io.github.hatake716.dango.ui.browser.components.FinderPushButton(
            text = stringResource(R.string.onboarding_grant),
            onClick = onGrant,
            style = io.github.hatake716.dango.ui.browser.components.FinderButtonStyle.Default,
            modifier = Modifier.widthIn(min = 200.dp),
        )
        Spacer(Modifier.height(10.dp))
        io.github.hatake716.dango.ui.browser.components.FinderPushButton(
            text = stringResource(R.string.onboarding_skip),
            onClick = onSkip,
            modifier = Modifier.widthIn(min = 200.dp),
        )
    }
}
