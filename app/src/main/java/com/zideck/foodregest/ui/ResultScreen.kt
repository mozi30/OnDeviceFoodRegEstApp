package com.zideck.foodregest.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zideck.foodregest.ml.Label
import com.zideck.foodregest.ml.MealEstimate
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(state: UiState, developerInfo: Boolean, vm: MainViewModel, modifier: Modifier = Modifier) {
    val e = state.estimate ?: return
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.showResult(false) }) { Text("‹ Photos") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::newMeal) { Text("New meal") }
        }
        Text(
            e.dishName?.replaceFirstChar { it.uppercase() } ?: "Meal",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Estimated from ${e.nPhotos} photo${if (e.nPhotos > 1) "s (median)" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${e.kcal.roundToInt()} kcal", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    if (e.massCorrectedG != null) "${e.massG.roundToInt()} g (corrected; model: ${e.predictedMassG.roundToInt()} g)"
                    else "${e.massG.roundToInt()} g  (90 % range ${e.massLowG.roundToInt()}–${e.massHighG.roundToInt()} g)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                MacroRow("Protein", e.proteinG, e.proteinPer100)
                MacroRow("Fat", e.fatG, e.fatPer100)
                MacroRow("Carbohydrates", e.carbG, e.carbPer100)
            }
        }

        Correction(e, onMass = vm::correctMass)

        Section("Food class (tap to correct)") {
            if (e.dishes.isEmpty()) Text("No class above ${(MealEstimate.LABEL_THRESHOLD * 100).toInt()} %")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.dishes.forEach { d ->
                    FilterChip(
                        selected = e.dishName == d.name,
                        onClick = { vm.chooseDish(d.name) },
                        label = { Text(labelText(d)) },
                    )
                }
            }
        }
        Section("Ingredients") {
            if (e.ingredients.isEmpty()) Text("No ingredient above ${(MealEstimate.LABEL_THRESHOLD * 100).toInt()} %")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.ingredients.forEach { AssistChip(onClick = {}, label = { Text(labelText(it)) }) }
            }
        }

        Section("Per photo") {
            state.photos.forEach { photo ->
                val p = photo.prediction ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Image(
                        photo.thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${p.massG.roundToInt()} g · ${p.kcal.roundToInt()} kcal", style = MaterialTheme.typography.bodyMedium)
                        if (developerInfo) Text(
                            "model ${p.inferenceMs} ms + preprocessing ${p.preprocessMs} ms on ${photo.accelerator}\n" +
                                "log m %.3f · log-std90 %.3f (90 %% range ×/÷ %.2f)".format(
                                    p.logMass, p.logStd90, kotlin.math.exp(1.645 * p.logStd90)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (developerInfo) DeveloperCard(state, vm)
    }
}

@Composable
private fun DeveloperCard(state: UiState, vm: MainViewModel) {
    val preds = state.ready
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Developer info", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            state.modelInfo?.let { info ->
                KeyValue("Model", info.summary)
                KeyValue("Load + warm-up", "${info.loadMs} ms")
            }
            if (preds.isNotEmpty()) {
                val inf = preds.map { it.inferenceMs.toDouble() }
                val pre = preds.map { it.preprocessMs.toDouble() }
                KeyValue("Model per photo (median)", "%.0f ms".format(MealEstimate.median(inf)))
                KeyValue("Preprocessing per photo (median)", "%.0f ms".format(MealEstimate.median(pre)))
                KeyValue("Total for ${preds.size} photo(s)", "%.0f ms".format(inf.sum() + pre.sum()))
            }
            val mem = vm.memory()
            KeyValue("Memory (Java / native heap)", "%.0f / %.0f MB".format(mem.javaHeapMb, mem.nativeHeapMb))
            state.estimate?.let { e ->
                KeyValue("Mass range (90 %)", "%.0f–%.0f g".format(e.massLowG, e.massHighG))
                KeyValue("All dish labels ≥ 25 %", e.dishes.joinToString { labelText(it) }.ifEmpty { "–" })
            }
        }
    }
}

@Composable
private fun MacroRow(name: String, grams: Double, per100: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f))
        Text("${"%.1f".format(grams)} g", fontWeight = FontWeight.SemiBold)
        Text("  (${"%.1f".format(per100)} g/100 g)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Correction(e: MealEstimate, onMass: (Double?) -> Unit) {
    var text by remember(e.massCorrectedG) { mutableStateOf(e.massCorrectedG?.roundToInt()?.toString() ?: "") }
    val focus = LocalFocusManager.current
    Section("Correct the weight") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                label = { Text("grams") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onMass(text.toDoubleOrNull()); focus.clearFocus() }) { Text("Apply") }
            if (e.massCorrectedG != null) TextButton(onClick = { onMass(null) }) { Text("Reset") }
        }
        Text(
            "Macros and kcal are recomputed from the predicted composition per 100 g.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

private fun labelText(l: Label) = "${l.name} ${(l.probability * 100).roundToInt()} %"
