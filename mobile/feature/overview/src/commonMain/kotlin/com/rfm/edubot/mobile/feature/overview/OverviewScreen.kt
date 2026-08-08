package com.rfm.edubot.mobile.feature.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.StatusLabel

@Composable
fun OverviewScreen(
    tenantName: String,
    overview: Overview?,
    loading: Boolean,
    strings: MobileCopy,
    onRefresh: () -> Unit,
    padding: PaddingValues,
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(tenantName.uppercase(), strings.overview) {
                TextButton(onClick = onRefresh, enabled = !loading) { Text(strings.refresh, color = BotColor.Accent) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp, 18.dp, 20.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings.operationalSnapshot, style = MaterialTheme.typography.labelLarge)
                StatusLabel(strings.synced, BotColor.Success)
            }
        }
        if (overview == null) item { LoadingScreen() } else {
            item { MetricRow(strings.messagesToday, overview.messagesToday, strings.messages, overview.messages) }
            item { MetricRow(strings.contacts, overview.users, strings.conversations, overview.conversations) }
            item { MetricRow(strings.quotes, overview.quotes, strings.invoices, overview.invoices) }
            item { InfoPanel(strings.cacheReady, strings.cacheDescription) }
        }
    }
}

@Composable
private fun MetricRow(leftLabel: String, leftValue: Long, rightLabel: String, rightValue: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(leftLabel, leftValue, Modifier.weight(1f))
        MetricCard(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: Long, modifier: Modifier) = Surface(
    modifier = modifier, color = BotColor.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border),
) {
    Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
        Spacer(Modifier.height(8.dp))
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
    }
}
