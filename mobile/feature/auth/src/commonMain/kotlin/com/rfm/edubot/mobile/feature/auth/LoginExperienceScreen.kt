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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.ui.BotColor

@Composable
fun LoginExperienceScreen(
    initialEmail: String,
    initialPassword: String,
    errorMessage: String?,
    onLogin: (String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var keepSignedIn by rememberSaveable { mutableStateOf(true) }
    var notice by rememberSaveable { mutableStateOf("") }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val horizontalPadding = if (maxWidth <= 420.dp) 16.dp else 20.dp
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(LoginExperienceCopy.brand, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(LoginExperienceCopy.brandSuffix, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
                }
                Spacer(Modifier.height(72.dp))
                Text(LoginExperienceCopy.signInLabel, style = MaterialTheme.typography.labelMedium, color = BotColor.Accent)
                Spacer(Modifier.height(18.dp))
                Text(LoginExperienceCopy.welcomeTitle, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text(LoginExperienceCopy.welcomeDescription, style = MaterialTheme.typography.bodyMedium, color = BotColor.Muted)
                Spacer(Modifier.height(30.dp))
                LoginExperienceField(LoginExperienceCopy.email, LoginExperienceCopy.emailPlaceholder, email, { email = it })
                Spacer(Modifier.height(18.dp))
                LoginExperienceField(
                    label = LoginExperienceCopy.password,
                    placeholder = LoginExperienceCopy.passwordPlaceholder,
                    value = password,
                    onValueChange = { password = it },
                    password = true,
                    passwordVisible = passwordVisible,
                    onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                )
                errorMessage?.let { Text(it, Modifier.padding(top = 8.dp), color = BotColor.Danger, style = MaterialTheme.typography.bodySmall) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepSignedIn, onCheckedChange = { keepSignedIn = it })
                        Text(LoginExperienceCopy.keepSignedIn, style = MaterialTheme.typography.bodyMedium, color = BotColor.Muted)
                    }
                    TextButton(onClick = { notice = LoginExperienceCopy.resetNotice }) {
                        Text(LoginExperienceCopy.forgotPassword, style = MaterialTheme.typography.bodyMedium, color = BotColor.Subtle)
                    }
                }
                LoginExperienceButton(LoginExperienceCopy.signIn, { onLogin(email, password) }, Modifier.fillMaxWidth().height(50.dp))
                Text(notice, Modifier.fillMaxWidth().padding(top = 14.dp), style = MaterialTheme.typography.bodySmall, color = BotColor.Muted)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = BotColor.Border)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(BotColor.Success))
                    Spacer(Modifier.width(9.dp))
                    Text(LoginExperienceCopy.securityNote, style = MaterialTheme.typography.bodySmall, color = BotColor.Muted)
                }
            }
        }
    }
}

@Composable
private fun LoginExperienceField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityChange: () -> Unit = {},
) = Column {
    Text(label, style = MaterialTheme.typography.labelLarge, color = BotColor.Subtle)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = BotColor.Muted) },
        singleLine = true,
        visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            { TextButton(onClick = onPasswordVisibilityChange) { Text(if (passwordVisible) LoginExperienceCopy.hide else LoginExperienceCopy.show, style = MaterialTheme.typography.labelMedium) } }
        } else null,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BotColor.Accent,
            unfocusedBorderColor = BotColor.Border,
            focusedContainerColor = BotColor.Background,
            unfocusedContainerColor = BotColor.Background,
        ),
    )
}

@Composable
private fun LoginExperienceButton(text: String, onClick: () -> Unit, modifier: Modifier) = Button(
    onClick = onClick,
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    colors = ButtonDefaults.buttonColors(containerColor = BotColor.Accent, contentColor = BotColor.Background),
) {
    Text(text)
}

private object LoginExperienceCopy {
    const val brand = "thebots.lab"
    const val brandSuffix = "tenant operations"
    const val signInLabel = "sign in"
    const val welcomeTitle = "Welcome back."
    const val welcomeDescription = "Use your operator account to continue."
    const val email = "Work email"
    const val emailPlaceholder = "you@company.com"
    const val password = "Password"
    const val passwordPlaceholder = "Enter your password"
    const val show = "Show"
    const val hide = "Hide"
    const val keepSignedIn = "Keep me signed in"
    const val forgotPassword = "Forgot password?"
    const val resetNotice = "Password reset instructions will be sent to your work email."
    const val signIn = "Sign in"
    const val securityNote = "Secure workspace. Your tenant data stays scoped to your operator account."
}
