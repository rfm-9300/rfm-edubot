package com.rfm.edubot.mobile.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LoadingScreen() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = BotColor.Accent, modifier = Modifier.size(36.dp))
}

@Composable
fun ScreenHeader(eyebrow: String, title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
            Spacer(Modifier.height(3.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        action?.invoke()
    }
    HorizontalDivider(color = BotColor.Border)
}

@Composable
fun ListRow(title: String, detail: String, status: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(38.dp), shape = RoundedCornerShape(10.dp), color = BotColor.Panel) { Box(contentAlignment = Alignment.Center) { Text(title.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = BotColor.Accent) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColor.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        status?.let { StatusLabel(it, statusColor(it)) }
    }
    HorizontalDivider(Modifier.padding(start = 70.dp), color = BotColor.Border)
}

@Composable
fun StatusLabel(text: String, color: Color) = Text("• ${text.uppercase()}", style = MaterialTheme.typography.labelMedium, color = color)

@Composable
fun SectionLabel(text: String) = Text(text.uppercase(), Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp), style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)

@Composable
fun InfoPanel(title: String, detail: String) = Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border)) {
    Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); if (detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = BotColor.Subtle) } }
}

@Composable
fun ErrorPanel(text: String) = Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = Color(0x1FF06B70), shape = RoundedCornerShape(10.dp)) { Text(text, Modifier.padding(14.dp), color = BotColor.Danger) }

@Composable
fun BotField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, singleLine: Boolean = true) = OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier, label = { Text(label) }, singleLine = singleLine,
    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BotColor.Accent, unfocusedBorderColor = BotColor.Border, focusedLabelColor = BotColor.Accent),
)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = BotColor.Accent, contentColor = BotColor.Background)) { Text(text) }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, color: Color = BotColor.Subtle) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = color, disabledContainerColor = Color.Transparent)) { Text(text) }

@Composable
fun MessageBubble(customer: Boolean, text: String, createdAt: String) {
    val color = if (customer) BotColor.Surface else Color(0x26FFD60A)
    Surface(
        modifier = Modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BotColor.Border),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(createdAt, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
        }
    }
}

fun statusColor(status: String): Color = when (status.uppercase()) {
    "ACTIVE", "OPEN", "CONFIRMED", "PAID", "RESOLVED" -> BotColor.Success
    "PENDING", "DRAFT" -> BotColor.Warning
    "BLOCKED", "FAILED", "CANCELLED" -> BotColor.Danger
    else -> BotColor.Info
}
