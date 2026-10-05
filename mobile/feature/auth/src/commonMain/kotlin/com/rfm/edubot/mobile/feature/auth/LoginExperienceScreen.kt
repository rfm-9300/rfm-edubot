package com.rfm.edubot.mobile.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.PrimaryButton

/**
 * Sign-in.
 *
 * The copy comes from [Strings] now; it used to be a hardcoded English object, so a Portuguese
 * tenant's staff saw an English sign-in screen and English error messages.
 */
@Composable
fun LoginExperienceScreen(
    strings: Strings,
    errorMessage: String?,
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
    initialEmail: String = "",
    initialPassword: String = "",
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Surface(color = BotColors.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val horizontal = if (maxWidth <= 420.dp) BotSpace.lg else BotSpace.xl
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontal, vertical = BotSpace.xxl),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BRAND, style = MaterialTheme.typography.titleLarge, color = BotColors.ink)
                        Spacer(Modifier.width(BotSpace.sm))
                        Box(Modifier.size(6.dp).clip(CircleShape).background(BotColors.accent))
                    }
                    Spacer(Modifier.height(56.dp))
                    Text(
                        strings[Txt.LOGIN_TITLE],
                        style = MaterialTheme.typography.headlineLarge,
                        color = BotColors.ink,
                    )
                    Spacer(Modifier.height(BotSpace.sm))
                    Text(
                        strings[Txt.LOGIN_DESCRIPTION],
                        style = MaterialTheme.typography.bodyMedium,
                        color = BotColors.inkMuted,
                    )
                    Spacer(Modifier.height(BotSpace.xxl))
                    BotField(
                        value = email,
                        onValueChange = { email = it },
                        label = strings[Txt.LOGIN_EMAIL],
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(BotSpace.md))
                    BotField(
                        value = password,
                        onValueChange = { password = it },
                        label = strings[Txt.LOGIN_PASSWORD],
                        modifier = Modifier.fillMaxWidth(),
                        password = !passwordVisible,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                strings[if (passwordVisible) Txt.ACTION_HIDE else Txt.ACTION_SHOW],
                                style = MaterialTheme.typography.labelMedium,
                                color = BotColors.inkMuted,
                            )
                        }
                    }
                    errorMessage?.let {
                        Text(
                            it,
                            Modifier.padding(top = BotSpace.xs, bottom = BotSpace.sm),
                            color = BotColors.badInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(BotSpace.md))
                    PrimaryButton(
                        text = strings[Txt.ACTION_SIGN_IN],
                        onClick = { onSignIn(email, password) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = email.isNotBlank() && password.isNotBlank(),
                        busy = busy,
                    )
                    Spacer(Modifier.height(BotSpace.xl))
                    HorizontalDivider(color = BotColors.line)
                    Row(Modifier.fillMaxWidth().padding(top = BotSpace.lg), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(BotColors.ok))
                        Spacer(Modifier.width(BotSpace.sm))
                        Text(
                            strings[Txt.LOGIN_SECURITY_NOTE],
                            style = MaterialTheme.typography.bodySmall,
                            color = BotColors.inkFaint,
                        )
                    }
                }
            }
        }
    }
}

private const val BRAND = "thebots.lab"
