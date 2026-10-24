package ua.tarilka.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.ai.ModelStore
import ua.tarilka.ai.PhotoUtil
import ua.tarilka.ui.theme.Numbers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(
    vm: MainViewModel,
    ai: AiViewModel,
    initialMeal: Int,
    onBack: () -> Unit,
    onSetupModel: () -> Unit,
    onReplace: (index: Int) -> Unit,
    onAddItem: () -> Unit,
) {
    val context = LocalContext.current
    val model by ai.model.collectAsState()
    val ui by ai.photo.collectAsState()
    val day by vm.day.collectAsState()
    var meal by remember { mutableIntStateOf(initialMeal) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) ai.analyze(u)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ai.analyze(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        bottomBar = {
            val chosen = ui.items.filter { it.include }
            if (chosen.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "${chosen.sumOf { it.kcal }.f0()} ${stringResource(R.string.unit_kcal)}",
                        style = MaterialTheme.typography.titleMedium.merge(Numbers),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    )
                    Button(
                        onClick = {
                            vm.addEntries(ai.entriesFor(day.toEpochDay(), meal))
                            ai.resetPhoto()
                            onBack()
                        },
                        modifier = Modifier.padding(end = 16.dp),
                    ) { Text(stringResource(R.string.add_to, mealName(meal))) }
                }
            }
        },
    ) { pad ->
        if (model !is ModelStore.State.Ready) {
            Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.photo_need_model), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSetupModel) { Text(stringResource(R.string.ai_setup)) }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..3).forEach { m ->
                        FilterChip(meal == m, { meal = m }, { Text(mealName(m)) })
                    }
                }
            }
            if (!ui.working && !ui.analyzed) {
                item {
                    OutlinedTextField(
                        value = ui.note,
                        onValueChange = ai::setNote,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.photo_note)) },
                        placeholder = { Text(stringResource(R.string.photo_note_hint)) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val u = PhotoUtil.captureUri(context)
                            captureUri = u
                            camera.launch(u)
                        }) {
                            Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.take_photo))
                        }
                        OutlinedButton(onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(stringResource(R.string.from_gallery)) }
                    }
                }
                item {
                    Text(stringResource(R.string.photo_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ui.image?.let { file ->
                item(key = "img") {
                    val bmp = remember(file.path, file.lastModified()) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                    if (bmp != null) {
                        Image(
                            bmp, null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }
            if (ui.working) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.analyzing, ui.seconds), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(R.string.analyzing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ui.error?.let { err ->
                item {
                    Text(stringResource(R.string.ai_error, err), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = ai::resetPhoto) { Text(stringResource(R.string.try_again)) }
                }
            }
            if (ui.analyzed) {
                if (ui.items.isEmpty()) {
                    item { Text(stringResource(R.string.no_food_found)) }
                } else {
                    item {
                        Text(stringResource(R.string.review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(ui.items) { i, item ->
                    ReviewCard(i, item, ai, onReplace)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onAddItem) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_item))
                        }
                        TextButton(onClick = ai::resetPhoto) { Text(stringResource(R.string.new_photo)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(i: Int, item: ReviewItem, ai: AiViewModel, onReplace: (Int) -> Unit) {
    var text by remember(i, item.food?.id) { mutableStateOf(item.grams.f0()) }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.include, onCheckedChange = { ai.setInclude(i, it) })
            Column(Modifier.weight(1f)) {
                Text(item.food?.name() ?: ua.tarilka.data.localized(item.detected.nameUk, item.detected.nameEn), style = MaterialTheme.typography.bodyLarge)
                val sub = when {
                    item.isEstimate -> stringResource(R.string.ai_estimate)
                    else -> stringResource(R.string.from_db, sourceLabel(item.food!!.source))
                }
                val conf = if (item.detected.confidence == "low") " · " + stringResource(R.string.low_confidence) else ""
                Text(sub + conf, style = MaterialTheme.typography.bodySmall, color = if (item.isEstimate) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.kcal.f0(), style = MaterialTheme.typography.titleMedium.merge(Numbers))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v; v.toNumber()?.let { ai.setGrams(i, it) } },
                singleLine = true,
                suffix = { Text(stringResource(R.string.unit_g)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onReplace(i) }) { Text(stringResource(R.string.replace)) }
        }
    }
}
