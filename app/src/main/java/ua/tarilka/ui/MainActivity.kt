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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
private sealed interface Overlay {
    data class AddFood(val meal: Int) : Overlay
    data class Photo(val meal: Int) : Overlay
    /** Pick a food for the photo review: replace item [index], or add a new one when null. */
    data class PickForPhoto(val index: Int?) : Overlay
    data class EditFood(val food: Food?) : Overlay
}

@Composable
private fun AppRoot() {
    val vm: MainViewModel = viewModel()
    val ai: AiViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Overlay>() }
    fun push(o: Overlay) { stack.add(o) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

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
        )

        is Overlay.Photo -> PhotoScreen(
            vm = vm,
            ai = ai,
            initialMeal = top.meal,
            onBack = ::pop,
            onSetupModel = { stack.clear(); tab = 3 },
            onReplace = { push(Overlay.PickForPhoto(it)) },
            onAddItem = { push(Overlay.PickForPhoto(null)) },
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
            initial = top.food,
            onBack = ::pop,
            onSave = { vm.saveFood(it); pop() },
        )

        null -> Tabs(vm, ai, tab, onTab = { tab = it }, push = ::push)
    }
}

@Composable
private fun Tabs(vm: MainViewModel, ai: AiViewModel, tab: Int, onTab: (Int) -> Unit, push: (Overlay) -> Unit) {
    Scaffold(
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
            0 -> TodayScreen(vm, ai, pad, onAdd = { push(Overlay.AddFood(it)) }, onPhoto = { push(Overlay.Photo(it)) })
            1 -> WeightScreen(vm, pad)
            2 -> FoodsScreen(vm, pad, onEdit = { push(Overlay.EditFood(it)) })
            else -> SettingsScreen(vm, ai, pad)
        }
    }
}
