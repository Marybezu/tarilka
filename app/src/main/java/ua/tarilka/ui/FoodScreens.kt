package ua.tarilka.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Food
import ua.tarilka.data.isUkrainian
import ua.tarilka.ui.theme.Numbers

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_food)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
        },
    )
}

@Composable
fun FoodRow(food: Food, onClick: () -> Unit, onFavorite: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(food.name(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${food.protein.f1()} · ${food.fat.f1()} · ${food.carbs.f1()} ${stringResource(R.string.per_100g_pfc)} · ${sourceLabel(food.source)}",
                style = MaterialTheme.typography.bodySmall.merge(Numbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${food.kcal.f0()}",
            style = MaterialTheme.typography.titleMedium.merge(Numbers),
        )
        if (onFavorite != null) {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (food.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    stringResource(R.string.favorite),
                    tint = if (food.favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Search the database and add a food to a meal (or pick a food to replace an AI guess). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(
    vm: MainViewModel,
    title: String,
    onBack: () -> Unit,
    onPicked: (Food, Double) -> Unit,
    askGrams: Boolean = true,
    onCreateFood: () -> Unit,
) {
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val recent by vm.recent.collectAsState()
    var chosen by remember { mutableStateOf<Food?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateFood,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.new_food)) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            SearchField(query, { vm.query.value = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (query.isBlank() && recent.isNotEmpty()) {
                    item { ListHeader(stringResource(R.string.recent)) }
                    items(recent, key = { "r${it.id}" }) { f ->
                        FoodRow(f, { if (askGrams) chosen = f else onPicked(f, f.portionG ?: 100.0) })
                    }
                    item { ListHeader(stringResource(R.string.all_foods)) }
                }
                items(results, key = { it.id }) { f ->
                    FoodRow(f, { if (askGrams) chosen = f else onPicked(f, f.portionG ?: 100.0) })
                }
                if (results.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            stringResource(R.string.nothing_found),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    chosen?.let { f ->
        GramsDialog(
            title = f.name(),
            initial = f.portionG ?: 100.0,
            food = f,
            onDismiss = { chosen = null },
            onConfirm = { g -> chosen = null; onPicked(f, g) },
        )
    }
}

@Composable
fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Browse the whole database, see where values come from, mark favourites, manage own foods. */
@Composable
fun FoodsScreen(vm: MainViewModel, padding: PaddingValues, onEdit: (Food?) -> Unit) {
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    var details by remember { mutableStateOf<Food?>(null) }

    Column(Modifier.padding(top = padding.calculateTopPadding())) {
        SearchField(query, { vm.query.value = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
        ) {
            items(results, key = { it.id }) { f ->
                FoodRow(f, { details = f }, onFavorite = { vm.toggleFavorite(f) })
            }
        }
    }

    details?.let { f ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(f.name()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.per_100g), style = MaterialTheme.typography.labelLarge)
                    NutrRow(stringResource(R.string.calories), "${f.kcal.f0()} ${stringResource(R.string.unit_kcal)}")
                    NutrRow(stringResource(R.string.protein), "${f.protein.f1()} ${stringResource(R.string.unit_g)}")
                    NutrRow(stringResource(R.string.fat), "${f.fat.f1()} ${stringResource(R.string.unit_g)}")
                    NutrRow(stringResource(R.string.carbs), "${f.carbs.f1()} ${stringResource(R.string.unit_g)}")
                    NutrRow(stringResource(R.string.fiber), "${f.fiber.f1()} ${stringResource(R.string.unit_g)}")
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.source) + ": " + sourceLabel(f.source), style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (isUkrainian()) f.nameEn else f.nameUk,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.close)) } },
            dismissButton = {
                if (f.isCustom) {
                    Row {
                        TextButton(onClick = { vm.deleteFood(f); details = null }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { details = null; onEdit(f) }) { Text(stringResource(R.string.edit)) }
                    }
                }
            },
        )
    }
}

@Composable
private fun NutrRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Numbers))
    }
}

/** Create or edit the user's own food (e.g. from a package label). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFoodScreen(initial: Food?, onBack: () -> Unit, onSave: (Food) -> Unit) {
    var nameUk by remember { mutableStateOf(initial?.nameUk ?: "") }
    var nameEn by remember { mutableStateOf(initial?.nameEn ?: "") }
    var kcal by remember { mutableStateOf(initial?.kcal?.f1() ?: "") }
    var p by remember { mutableStateOf(initial?.protein?.f1() ?: "") }
    var f by remember { mutableStateOf(initial?.fat?.f1() ?: "") }
    var c by remember { mutableStateOf(initial?.carbs?.f1() ?: "") }
    var fib by remember { mutableStateOf(initial?.fiber?.f1() ?: "") }
    var portion by remember { mutableStateOf(initial?.portionG?.f0() ?: "") }
    val valid = (nameUk.isNotBlank() || nameEn.isNotBlank()) && kcal.toNumber() != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (initial == null) R.string.new_food else R.string.edit_food)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(enabled = valid, onClick = {
                        val uk = nameUk.trim().ifEmpty { nameEn.trim() }
                        val en = nameEn.trim().ifEmpty { nameUk.trim() }
                        val pg = portion.toNumber()
                        onSave(
                            (initial ?: Food(category = "custom", nameUk = uk, nameEn = en, kcal = 0.0, protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0, source = "CUSTOM"))
                                .copy(
                                    nameUk = uk, nameEn = en,
                                    kcal = kcal.toNumber() ?: 0.0, protein = p.toNumber() ?: 0.0,
                                    fat = f.toNumber() ?: 0.0, carbs = c.toNumber() ?: 0.0, fiber = fib.toNumber() ?: 0.0,
                                    portionG = pg,
                                    portionUk = pg?.let { "порція" }, portionEn = pg?.let { "serving" },
                                ),
                        )
                    }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(stringResource(R.string.label_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Field(stringResource(R.string.name_uk), nameUk, { nameUk = it }, number = false) }
            item { Field(stringResource(R.string.name_en), nameEn, { nameEn = it }, number = false) }
            item { Field(stringResource(R.string.kcal_100), kcal, { kcal = it }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.protein), p, { p = it }, Modifier.weight(1f))
                    Field(stringResource(R.string.fat), f, { f = it }, Modifier.weight(1f))
                    Field(stringResource(R.string.carbs), c, { c = it }, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.fiber), fib, { fib = it }, Modifier.weight(1f))
                    Field(stringResource(R.string.portion_g), portion, { portion = it }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}
