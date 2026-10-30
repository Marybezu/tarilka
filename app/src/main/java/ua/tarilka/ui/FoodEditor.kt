package ua.tarilka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.tarilka.R
import ua.tarilka.data.Food
import ua.tarilka.data.Recipe
import ua.tarilka.data.RecipeItem

/**
 * Form state for a new or edited food. It lives in the navigation stack,
 * so picking an ingredient on another screen doesn't lose what was typed.
 */
class FoodDraft(val initial: Food?) {
    var nameUk by mutableStateOf(initial?.nameUk ?: "")
    var nameEn by mutableStateOf(initial?.nameEn ?: "")
    var kcal by mutableStateOf(initial?.kcal?.f1() ?: "")
    var protein by mutableStateOf(initial?.protein?.f1() ?: "")
    var fat by mutableStateOf(initial?.fat?.f1() ?: "")
    var carbs by mutableStateOf(initial?.carbs?.f1() ?: "")
    var fiber by mutableStateOf(initial?.fiber?.f1() ?: "")
    var portion by mutableStateOf(initial?.portionG?.f0() ?: "")
    var kind by mutableStateOf(ua.tarilka.data.OwnKinds.clean(initial?.category))
    var isRecipe by mutableStateOf(initial?.recipe != null)
    val items = mutableStateListOf<RecipeItem>()
    var cooked by mutableStateOf("")
    var recipeLoaded = initial?.recipe == null

    private val name get() = nameUk.isNotBlank() || nameEn.isNotBlank()
    /** Calories as typed, or from protein, fat and carbs (4/9/4) when left empty: under 18 the field is hidden. */
    private val kcalValue: Double? get() = kcal.toNumber() ?: listOf(protein, fat, carbs).map { it.toNumber() }
        .takeIf { l -> l.any { it != null } }?.let { l -> 4 * (l[0] ?: 0.0) + 9 * (l[1] ?: 0.0) + 4 * (l[2] ?: 0.0) }
    val valid: Boolean get() = name && if (isRecipe) items.isNotEmpty() else kcalValue?.let { it <= 900 } == true

    fun build(): Food {
        val uk = nameUk.trim().ifEmpty { nameEn.trim() }
        val en = nameEn.trim().ifEmpty { nameUk.trim() }
        val pg = portion.toNumber()
        val base = initial ?: Food(
            category = "custom", nameUk = uk, nameEn = en, kcal = 0.0, protein = 0.0, fat = 0.0,
            carbs = 0.0, fiber = 0.0, source = "CUSTOM",
        )
        val common = base.copy(
            nameUk = uk, nameEn = en, portionG = pg, category = kind,
            portionUk = pg?.let { "порція" }, portionEn = pg?.let { "serving" },
        )
        return if (isRecipe) {
            val cw = cooked.toNumber()
            val t = Recipe.per100(items, cw)
            common.copy(
                kcal = t.kcal, protein = t.protein, fat = t.fat, carbs = t.carbs, fiber = t.fiber,
                source = "RECIPE_USER", recipe = Recipe.encode(items, cw),
            )
        } else {
            common.copy(
                kcal = kcalValue ?: 0.0, protein = protein.toNumber() ?: 0.0, fat = fat.toNumber() ?: 0.0,
                carbs = carbs.toNumber() ?: 0.0, fiber = fiber.toNumber() ?: 0.0,
                source = "CUSTOM", recipe = null,
            )
        }
    }
}

/** Own food from a label, or an own dish calculated from ingredients. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFoodScreen(
    draft: FoodDraft,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSave: (Food) -> Unit,
    onAddIngredient: () -> Unit,
) {
    LaunchedEffect(draft) {
        val json = draft.initial?.recipe
        if (!draft.recipeLoaded && json != null) {
            val (items, cooked) = Recipe.decode(json, vm.allFoods())
            draft.items.addAll(items)
            draft.cooked = cooked?.f0() ?: ""
            draft.recipeLoaded = true
        }
    }

    // Under 18 no calorie field: the app works the number out from protein, fat and carbs.
    val showKcal = LocalShowKcal.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft.initial == null) R.string.new_food else R.string.edit_food)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(enabled = draft.valid, onClick = { onSave(draft.build()) }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!draft.isRecipe, { draft.isRecipe = false }, { Text(stringResource(R.string.from_label)) })
                    FilterChip(draft.isRecipe, { draft.isRecipe = true }, { Text(stringResource(R.string.from_ingredients)) })
                }
            }
            item { Field(stringResource(R.string.name_uk), draft.nameUk, { draft.nameUk = it }, number = false) }
            item { Field(stringResource(R.string.name_en), draft.nameEn, { draft.nameEn = it }, number = false) }
            item { KindPicker(draft.kind) { draft.kind = it } }

            if (!draft.isRecipe) {
                item { Text(stringResource(R.string.label_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (showKcal) item { Field(stringResource(R.string.kcal_100), draft.kcal, { draft.kcal = it }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(stringResource(R.string.protein), draft.protein, { draft.protein = it }, Modifier.weight(1f))
                        Field(stringResource(R.string.fat), draft.fat, { draft.fat = it }, Modifier.weight(1f))
                        Field(stringResource(R.string.carbs), draft.carbs, { draft.carbs = it }, Modifier.weight(1f))
                    }
                }
                item { Field(stringResource(R.string.fiber), draft.fiber, { draft.fiber = it }) }
            } else {
                item { Text(stringResource(R.string.recipe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(draft.items) { i, it -> IngredientRow(it, onGrams = { g -> draft.items.indexOf(it).takeIf { n -> n >= 0 }?.let { n -> draft.items[n] = it.copy(grams = g) } }, onRemove = { draft.items.remove(it) }) }
                item {
                    FilledTonalButton(onClick = onAddIngredient) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_ingredient))
                    }
                }
                item {
                    Field(stringResource(R.string.cooked_weight), draft.cooked, { draft.cooked = it })
                    Text(
                        stringResource(R.string.cooked_weight_hint, draft.items.sumOf { it.grams }.f0()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (draft.items.isNotEmpty()) {
                    item {
                        val t = Recipe.per100(draft.items, draft.cooked.toNumber())
                        Section(title = stringResource(R.string.per_100g)) { MacroLine(t.kcal, t.protein, t.fat, t.carbs) }
                    }
                }
            }
            item { Field(stringResource(R.string.portion_g), draft.portion, { draft.portion = it }) }
        }
    }
}

/** What the food is, so it counts in the habits and advice (vegetables, sweets, a drink...). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.own_kind), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ua.tarilka.data.OwnKinds.ALL.forEach { k ->
                FilterChip(k == kind, { onPick(k) }, { Text(stringResource(ownKindName(k)), maxLines = 1) })
            }
        }
        Text(stringResource(R.string.own_kind_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ownKindName(k: String) = when (k) {
    "veg", "fruit" -> R.string.kind_veg
    "sweets" -> R.string.kind_sweets
    "fastfood" -> R.string.kind_fastfood
    "drinks" -> R.string.kind_drinks
    "legumes" -> R.string.kind_legumes
    "fish" -> R.string.kind_fish
    "nuts" -> R.string.kind_nuts
    else -> R.string.kind_other
}

@Composable
private fun IngredientRow(item: RecipeItem, onGrams: (Double) -> Unit, onRemove: () -> Unit) {
    var text by remember(item.food.id) { mutableStateOf(item.grams.f0()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(item.food.name(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (LocalShowKcal.current) Text(
                "${(item.food.kcal * item.grams / 100).f0()} ${stringResource(R.string.unit_kcal)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Field(
            stringResource(R.string.unit_g), text,
            { v -> text = v; v.toNumber()?.takeIf { it > 0 }?.let(onGrams) },
            Modifier.width(96.dp),
        )
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
    }
}
