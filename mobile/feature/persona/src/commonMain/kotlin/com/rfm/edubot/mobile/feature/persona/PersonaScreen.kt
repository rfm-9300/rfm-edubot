package com.rfm.edubot.mobile.feature.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.StatusLabel

@Composable
fun PersonaScreen(api: DashboardApi, token: String, strings: MobileCopy, padding: PaddingValues) {
    val vm = viewModel<PersonaViewModel>(key = "persona:$token", factory = viewModelFactory { initializer { PersonaViewModel(api, token) } })
    val personaState by vm.state.collectAsState()
    var instructions by remember(personaState.persona?.version) { mutableStateOf(personaState.persona?.compiledInstructions.orEmpty()) }
    LaunchedEffect(vm) { vm.load() }
    Column(Modifier.fillMaxSize().padding(padding)) {
        ScreenHeader(strings.persona.uppercase(), strings.persona)
        val persona = personaState.persona
        if (persona == null) LoadingScreen() else Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${strings.version} ${persona.version}", style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
                StatusLabel(persona.status, BotColor.Success)
            }
            Spacer(Modifier.height(16.dp))
            BotField(instructions, { instructions = it }, strings.instructions, Modifier.fillMaxWidth().weight(1f), singleLine = false)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(strings.save, { vm.save(instructions) }, Modifier.fillMaxWidth(), !personaState.saving)
        }
    }
}
