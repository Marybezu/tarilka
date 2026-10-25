package ua.tarilka.ui

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.tarilka.R
import ua.tarilka.data.Food
import ua.tarilka.ui.theme.TarilkaTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = (application as ua.tarilka.TarilkaApp).prefs
        setContent {
            val dynamic by prefs.dynamicColor.collectAsState()
            TarilkaTheme(dynamicColor = dynamic) { AppRoot() }
        }
    }
}

/** Screens shown on top of the tabs. */
sealed interface Overlay {
    data class AddFood(val meal: Int) : Overlay
    data class Photo(val meal: Int) : Overlay
    /** Pick a food for the photo review: replace item [index], or add a new one when null. */
    data class PickForPhoto(val index: Int?) : Overlay
    /** Not a data class: each editor keeps its own form state while ingredients are picked. */
    class EditFood(food: Food?) : Overlay { val draft = FoodDraft(food) }
    class PickIngredient(val draft: FoodDraft) : Overlay
    data object History : Overlay
}

/** Keeps the screen stack (and half-filled forms) across rotation, theme and language changes. */
class NavViewModel : androidx.lifecycle.ViewModel() {
    val stack = mutableStateListOf<Overlay>()
}

@Composable
private fun AppRoot() {
    val vm: MainViewModel = viewModel()
    val ai: AiViewModel = viewModel()
    val nav: NavViewModel = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = nav.stack
    fun push(o: Overlay) { stack.add(o) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    // The camera may be in front long enough for Android to restart this app.
    // These launchers live at the root, so the result is still delivered and the photo screen comes back.
    var pendingMeal by rememberSaveable { mutableIntStateOf(-1) }
    fun showPhoto(meal: Int) { if (stack.lastOrNull() !is Overlay.Photo) push(Overlay.Photo(meal)) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok && pendingMeal >= 0) {
            showPhoto(pendingMeal)
            ai.analyze(ua.tarilka.ai.PhotoUtil.captureUri(context))
        }
    }
    val gallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && pendingMeal >= 0) {
            showPhoto(pendingMeal)
            ai.analyze(uri)
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    when (val top = stack.lastOrNull()) {
        is Overlay.AddFood -> AddFoodScreen(
            vm = vm,
            title = stringResource(R.string.add_to, mealName(top.meal)),
            onBack = ::pop,
            onPicked = { food, grams ->
                vm.addFood(food, grams, top.meal)
                vm.query.value = ""
                pop()
            },
            onCreateFood = { push(Overlay.EditFood(null)) },
            // Breakfast/lunch/dinner that already have a time keep it; only a new meal or a snack asks.
            mealTime = if (vm.mealTimes.collectAsState().value && (top.meal == 3 || vm.mealTimeOf(top.meal) == null)) vm.defaultTime(top.meal) else null,
            onPickedAt = { food, grams, time ->
                vm.addFood(food, grams, top.meal, time)
                vm.query.value = ""
                pop()
            },
            onTextList = { text ->
                ai.analyzeText(text)
                vm.query.value = ""
                pop()
                push(Overlay.Photo(top.meal))
            },
        )

        is Overlay.Photo -> PhotoScreen(
            vm = vm,
            ai = ai,
            initialMeal = top.meal,
            onBack = ::pop,
            onSetupModel = { stack.clear(); tab = 3 },
            onReplace = { i ->
                // For a food the database lacks, start the search with the name the AI gave.
                ai.photo.value.items.getOrNull(i)?.takeIf { it.food == null }?.let { vm.query.value = it.detected.nameEn }
                push(Overlay.PickForPhoto(i))
            },
            onAddItem = { push(Overlay.PickForPhoto(null)) },
            onCamera = { meal -> pendingMeal = meal; camera.launch(ua.tarilka.ai.PhotoUtil.captureUri(context)) },
            onGallery = { meal ->
                pendingMeal = meal
                gallery.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
        )

        is Overlay.PickForPhoto -> AddFoodScreen(
            vm = vm,
            title = stringResource(R.string.choose_food),
            onBack = ::pop,
            askGrams = false,
            onPicked = { food, _ ->
                if (top.index != null) ai.replaceFood(top.index, food) else ai.addManualItem(food)
                vm.query.value = ""
                pop()
            },
            onCreateFood = { push(Overlay.EditFood(null)) },
        )

        is Overlay.EditFood -> EditFoodScreen(
            draft = top.draft,
            vm = vm,
            onBack = ::pop,
            onSave = { vm.saveFood(it); pop() },
            onAddIngredient = { push(Overlay.PickIngredient(top.draft)) },
        )

        is Overlay.PickIngredient -> AddFoodScreen(
            vm = vm,
            title = stringResource(R.string.add_ingredient),
            onBack = ::pop,
            onPicked = { food, grams ->
                top.draft.items.add(ua.tarilka.data.RecipeItem(food, grams))
                vm.query.value = ""
                pop()
            },
            onCreateFood = { push(Overlay.EditFood(null)) },
        )

        Overlay.History -> HistoryScreen(
            vm = vm,
            ai = ai,
            onBack = ::pop,
            onOpen = { d -> vm.day.value = d; tab = 0; stack.clear() },
        )

        null -> Tabs(vm, ai, tab, onTab = { tab = it }, push = ::push)
    }
}

@Composable
private fun Tabs(vm: MainViewModel, ai: AiViewModel, tab: Int, onTab: (Int) -> Unit, push: (Overlay) -> Unit) {
    val pendingReason by vm.pendingReason.collectAsState()
    if (pendingReason != null) {
        ReasonDialog(onSave = vm::setReason, onSkip = vm::skipReason)
    }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val undoEvent by vm.undoEvent.collectAsState()
    val deletedText = undoEvent?.let { stringResource(R.string.deleted_n, it.second) }
    val undoText = stringResource(R.string.undo)
    LaunchedEffect(undoEvent) {
        if (deletedText == null) return@LaunchedEffect
        val r = snackbar.showSnackbar(deletedText, actionLabel = undoText, duration = androidx.compose.material3.SnackbarDuration.Short)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undo() else vm.undoShown()
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == 2) {
                ExtendedFloatingActionButton(
                    onClick = { push(Overlay.EditFood(null)) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.new_food)) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { onTab(0) }, { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.tab_today)) })
                NavigationBarItem(tab == 1, { onTab(1) }, { Icon(painterResource(R.drawable.ic_scale), null) }, label = { Text(stringResource(R.string.tab_weight)) })
                NavigationBarItem(tab == 2, { onTab(2) }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text(stringResource(R.string.tab_foods)) })
                NavigationBarItem(tab == 3, { onTab(3) }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_options), maxLines = 1) })
            }
        },
    ) { pad ->
        when (tab) {
            0 -> TodayScreen(vm, ai, pad, onAdd = { push(Overlay.AddFood(it)) }, onPhoto = { push(Overlay.Photo(it)) }, onHistory = { push(Overlay.History) })
            1 -> WeightScreen(vm, pad)
            2 -> FoodsScreen(vm, pad, onEdit = { push(Overlay.EditFood(it)) })
            else -> SettingsScreen(vm, ai, pad)
        }
    }
}
