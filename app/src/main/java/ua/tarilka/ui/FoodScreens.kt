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

    // Remember searches that found nothing, so missing foods can be added to the database later.
    val nothing = query.trim().length >= 3 && results.isEmpty()
    androidx.compose.runtime.LaunchedEffect(query, nothing) {
        if (nothing) {
            kotlinx.coroutines.delay(1500)
            vm.recordMissing(query)
        }
    }

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
    /** 0 all, 1 my own foods and recipes, 2 favorites */
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val shown = when (filter) {
        1 -> results.filter { it.isCustom }
        2 -> results.filter { it.favorite }
        else -> results
    }

    Column(Modifier.padding(top = padding.calculateTopPadding())) {
        SearchField(query, { vm.query.value = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.filter_all, R.string.filter_mine, R.string.filter_favorites).forEachIndexed { i, res ->
                androidx.compose.material3.FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(stringResource(res)) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
        ) {
            items(shown, key = { it.id }) { f ->
                FoodRow(f, { details = f }, onFavorite = { vm.toggleFavorite(f) })
            }
            if (shown.isEmpty() && filter != 0) {
                item {
                    Text(
                        stringResource(if (filter == 1) R.string.filter_mine_empty else R.string.filter_favorites_empty),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
