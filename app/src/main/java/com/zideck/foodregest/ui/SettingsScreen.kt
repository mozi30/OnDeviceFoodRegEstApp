package com.zideck.foodregest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zideck.foodregest.settings.AcceleratorChoice
import com.zideck.foodregest.settings.AppSettings
import com.zideck.foodregest.settings.CropMode

@Composable
fun SettingsScreen(state: UiState, settings: AppSettings, vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SwitchRow(
                    "Developer info",
                    "Timings (ms per photo), raw model outputs, accelerator and memory on the meal screens.",
                    settings.developerInfo, vm::setDeveloperInfo,
                )
                HorizontalDivider()
                SwitchRow(
                    "Live estimate on camera",
                    "Runs the model on every camera frame and shows weight, ms per frame and fps. Uses more battery.",
                    settings.liveEstimate, vm::setLiveEstimate,
                )
            }
        }

        SectionTitle("Image preprocessing (crop)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                CropMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(settings.cropMode == mode, role = Role.RadioButton) { vm.setCropMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.cropMode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(mode.label)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Each mode uses the model trained with that preprocessing. Changing it re-runs the photos of the current meal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        SectionTitle("Accelerator")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                AcceleratorChoice.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(settings.accelerator == choice, role = Role.RadioButton) { vm.setAccelerator(choice) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.accelerator == choice, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(choice.label)
                    }
                }
                Text(
                    when (val m = state.model) {
                        ModelState.Loading -> "Loading model…"
                        is ModelState.Ready -> "Running on " + m.infos.joinToString(", ") { "${it.accelerator} (${it.variant.short})" }
                        is ModelState.Failed -> "Failed: ${m.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.model is ModelState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        SectionTitle("Benchmark")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "50 runs of each active model on the last photo of the current meal (grey image if there is none). " +
                        "Inference only, without decoding and preprocessing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.runBenchmark() }, enabled = !state.benchmarking && state.model is ModelState.Ready) {
                        Text("Run benchmark")
                    }
                    if (state.benchmarking) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                state.benchmark.forEach { b ->
                    if (state.benchmark.size > 1) Text(b.variant.label, fontWeight = FontWeight.SemiBold)
                    KeyValue("Accelerator", b.accelerator)
                    KeyValue("Median", "%.1f ms".format(b.p50Ms))
                    KeyValue("Mean", "%.1f ms".format(b.meanMs))
                    KeyValue("90th percentile", "%.1f ms".format(b.p90Ms))
                    KeyValue("Fastest", "%.1f ms".format(b.minMs))
                    KeyValue("Runs / input", "${b.runs} / ${b.input}")
                }
            }
        }

        SectionTitle("Model")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.modelInfos.isEmpty()) Text("Not loaded")
                state.modelInfos.forEach { info ->
                    if (state.modelInfos.size > 1) Text(info.variant.label, fontWeight = FontWeight.SemiBold)
                    KeyValue("Backbone", info.backbone)
                    KeyValue("Crop", info.variant.label)
                    KeyValue("Input", "${info.imageSize} × ${info.imageSize} px")
                    KeyValue("Precision", info.precision.uppercase())
                    KeyValue("File", "${info.file} (%.1f MB)".format(info.fileMb))
                    KeyValue("Load + warm-up", "${info.loadMs} ms")
                }
                val mem = vm.memory()
                KeyValue("Memory (Java / native heap)", "%.0f / %.0f MB".format(mem.javaHeapMb, mem.nativeHeapMb))
                val ctx = LocalContext.current
                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
                KeyValue("App version", version ?: "?")
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
