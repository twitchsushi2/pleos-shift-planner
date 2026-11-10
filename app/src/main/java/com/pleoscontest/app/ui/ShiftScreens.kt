package com.pleoscontest.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pleoscontest.app.BuildConfig
import com.pleoscontest.app.R
import com.pleoscontest.app.navi.NaviLive
import com.pleoscontest.app.shift.ChangeItem
import com.pleoscontest.app.shift.Driver
import com.pleoscontest.app.shift.DriverChanges
import com.pleoscontest.app.shift.DriverProfile
import com.pleoscontest.app.shift.Josa
import com.pleoscontest.app.shift.MusicLinks
import com.pleoscontest.app.shift.Place
import com.pleoscontest.app.shift.RestArea
import com.pleoscontest.app.shift.RouteCatalog
import com.pleoscontest.app.shift.Segment
import com.pleoscontest.app.shift.TripState
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun secondaryText() = MaterialTheme.colorScheme.onSurfaceVariant

/** 운전 순서대로 정해지는 그 사람의 색. */
private fun ShiftUiState.colorOf(driver: Driver): Color =
    DRIVER_COLORS[drivers.indexOfFirst { it.id == driver.id }.coerceAtLeast(0) % DRIVER_COLORS.size]

@Composable
fun ShiftApp(state: ShiftUiState, vm: ShiftViewModel) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            var askingEnd by remember { mutableStateOf(false) }
            // 화면이 바뀔 때 툭 끊기지 않게 한다. 설정은 iOS처럼 옆에서 밀려 들어오고, 나머지는 부드럽게 겹쳐 바뀐다.
            AnimatedContent(
                state.screen,
                Modifier.fillMaxSize(),
                transitionSpec = {
                    when {
                        targetState == AppScreen.SETTINGS -> push(forward = true)
                        initialState == AppScreen.SETTINGS -> push(forward = false)
                        else -> (fadeIn(tween(SCREEN_MILLIS)) + scaleIn(tween(SCREEN_MILLIS), initialScale = 0.97f)) togetherWith fadeOut(tween(SCREEN_MILLIS / 2))
                    }
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    AppScreen.SETUP -> SetupScreen(state, vm)
                    AppScreen.SETTINGS -> SettingsScreen(state, vm)
                    AppScreen.DRIVING -> DrivingPanel(state, vm) { askingEnd = true }
                    AppScreen.SHIFT -> ShiftScreen(state, vm) { askingEnd = true }
                    AppScreen.SUMMARY -> SummaryScreen(state, vm)
                }
            }
            EndTripSheet(askingEnd && state.trip != null, { askingEnd = false }) {
                askingEnd = false
                vm.reset()
            }
            if (BuildConfig.DEMO_TIME_SCALE > 1) {
                // 위쪽은 처음으로 버튼과 겹쳐 아래 구석에 둔다.
                Text(
                    "데모 ${BuildConfig.DEMO_TIME_SCALE}배속",
                    Modifier.align(Alignment.BottomStart).padding(6.dp),
                    fontSize = 12.sp,
                    color = secondaryText(),
                )
            }
        }
    }
}

/** 출발 전 정차 중에만 보이는 입력 화면. 한 번에 한 가지만 묻는다. */
@Composable
private fun SetupScreen(state: ShiftUiState, vm: ShiftViewModel) {
    val previous = SetupStep.entries.getOrNull(state.step.ordinal - 1)
    if (previous != null) BackHandler { vm.goTo(previous) }
    // 다음 단계는 오른쪽에서 밀려 들어오고, 이전 단계로 가면 반대로 밀려난다.
    AnimatedContent(
        state.step,
        Modifier.fillMaxSize(),
        transitionSpec = { push(forward = targetState.ordinal > initialState.ordinal) },
        label = "step",
    ) { step -> SetupStepContent(step, state, vm) }
}

@Composable
private fun SetupStepContent(step: SetupStep, state: ShiftUiState, vm: ShiftViewModel) {
    when (step) {
        SetupStep.WELCOME -> WelcomeStep(state, vm)
        SetupStep.ROUTE -> RouteStep(state, vm)
        SetupStep.DRIVERS -> StepFrame(state, vm, "누가 운전하나요?", nextEnabled = state.namesFilled) { DriversStep(state, vm) }
        SetupStep.REVIEW -> StepFrame(state, vm, "${state.word} 계획", nextEnabled = false) { ReviewStep(state, vm) }
    }
}

@Composable
private fun WelcomeStep(state: ShiftUiState, vm: ShiftViewModel) {
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        CircleButton(R.drawable.ic_settings, "설정", vm::openSettings, Modifier.align(Alignment.TopStart))
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                Modifier.size(128.dp).background(colorResource(R.color.ic_launcher_background), RoundedCornerShape(30.dp)),
            )
            Spacer(Modifier.height(4.dp))
            Text("교대 플래너", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("언제, 어디서 쉬고 바꿀지 정해 드려요", fontSize = 22.sp, color = secondaryText())
            Spacer(Modifier.height(20.dp))
            val suggestion = state.naviSuggestion
            if (suggestion != null) {
                // 운전자가 내비에서 장거리 안내를 시작했으면 그 목적지로 바로 이어 간다.
                GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                    Text("내비 목적지", fontSize = 18.sp, color = secondaryText())
                    Text(suggestion.place.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${suggestion.distanceKm}km · ${durationText(suggestion.minutes)}", fontSize = 22.sp, color = secondaryText())
                    Spacer(Modifier.height(8.dp))
                    PrimaryPill("이 목적지로 계획 만들기", vm::planFromNavi, Modifier.fillMaxWidth(), height = 80.dp, fontSize = 26)
                }
                SecondaryPill("다른 곳으로 시작하기", {
                    vm.dismissSuggestion()
                    vm.goTo(SetupStep.ROUTE)
                }, Modifier.fillMaxWidth(), height = 72.dp)
            } else {
                PrimaryPill("플래너 시작하기", { vm.goTo(SetupStep.ROUTE) }, Modifier.fillMaxWidth(), height = 88.dp, fontSize = 28)
                Spacer(Modifier.height(8.dp))
                HowItWorks(state.naviAvailable)
            }
        }
    }
}

/** 처음 쓰는 사람이 무엇을 하게 될지 세 단계로 미리 보여 준다. */
@Composable
private fun HowItWorks(naviAvailable: Boolean) {
    val steps = listOf(
        if (naviAvailable) "내비에서\n목적지 찍기" else "목적지\n고르기",
        "운전할 사람\n고르기",
        "기어 D로\n출발",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { index, text ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text(text, fontSize = 17.sp, lineHeight = 22.sp, color = secondaryText())
            }
        }
    }
}

/** 네 단계를 작은 막대로 보여 준다. 숫자보다 눈에 잘 들어온다. */
@Composable
private fun StepProgress(state: ShiftUiState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1 until SetupStep.entries.size).forEach { step ->
            val done = step <= state.step.ordinal
            Box(
                Modifier.weight(1f).height(6.dp).background(
                    if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            )
        }
    }
}

/** 지도를 쓰지 않는 단계의 공통 틀. 위에 뒤로 버튼과 큰 제목, 아래에 다음 버튼을 둔다. */
@Composable
private fun StepFrame(
    state: ShiftUiState, vm: ShiftViewModel, title: String, nextEnabled: Boolean, scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val steps = SetupStep.entries
    val index = state.step.ordinal
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CircleButton(R.drawable.ic_back, "이전", { vm.goTo(steps[index - 1]) })
            StepProgress(state, Modifier.weight(1f))
            CircleButton(R.drawable.ic_home, "처음으로", { vm.goTo(SetupStep.WELCOME) })
        }
        Text(title, fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        if (state.step == SetupStep.REVIEW) SpeedPermissionNotice(state)
        if (state.step == SetupStep.REVIEW) {
            // 누르는 버튼으로 보이지 않게 테두리만 두고, 기어 표시처럼 D를 강조한다.
            Row(
                Modifier.fillMaxWidth().height(80.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            ) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text("D", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Text("기어를 D에 넣으면 출발해요", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            PrimaryPill("다음", { vm.goTo(steps[index + 1]) }, Modifier.fillMaxWidth(), enabled = nextEnabled)
        }
    }
}

/**
 * 내비처럼 지도를 화면 가득 깔고 그 위에 유리 카드를 띄운다.
 * 칸을 누르면 지도 대신 검색 결과가 나온다.
 */
@Composable
private fun RouteStep(state: ShiftUiState, vm: ShiftViewModel) {
    var searching by remember { mutableStateOf<SearchField?>(null) }
    val focus = LocalFocusManager.current
    val route = state.enteredRoute
    Box(Modifier.fillMaxSize()) {
        RouteMap(
            route, state.originPlace, state.destinationPlace, Modifier.fillMaxSize(),
            topInset = 200.dp, bottomInset = 190.dp,
        )
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircleButton(R.drawable.ic_back, "이전", { vm.goTo(SetupStep.WELCOME) })
                GlassCard(Modifier.weight(1f), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchRow(START_COLOR, "출발지", state.origin, vm::setOrigin) { active ->
                                searching = if (active) SearchField.ORIGIN else searching.takeUnless { it == SearchField.ORIGIN }
                            }
                            // 차에 내비가 있으면 목적지는 내비 검색에서 찍는다. 주소·지명 검색이 내비가 더 정확하고, 고르면 앱이 바로 돌아온다.
                            if (state.naviAvailable) {
                                NaviPickRow(state.destination, vm::pickInNavi)
                            } else {
                                SearchRow(END_COLOR, "주소, 지명, 건물 이름", state.destination, vm::setDestination, onSearch = vm::searchInNavi) { active ->
                                    searching = if (active) SearchField.DESTINATION else searching.takeUnless { it == SearchField.DESTINATION }
                                }
                            }
                        }
                        if (!state.naviAvailable) {
                            IconButton(vm::swapPlaces, enabled = state.canSwap) {
                                Icon(painterResource(R.drawable.ic_swap), "출발지와 목적지 맞바꾸기", Modifier.size(30.dp))
                            }
                        }
                    }
                }
            }
            val field = searching
            if (field != null) {
                val typed = if (field == SearchField.ORIGIN) state.origin else state.destination
                val query = if (RouteCatalog.find(typed) != null || typed == RouteCatalog.CURRENT_LOCATION) "" else typed
                val pick = { name: String ->
                    if (field == SearchField.ORIGIN) vm.setOrigin(name) else vm.setDestination(name)
                    focus.clearFocus()
                }
                // 도시 목록에 없는 곳은 앱 안 검색으로 찾는다. 글자를 칠 때마다 보내지 않도록 잠깐 기다린다.
                var found by remember(field) { mutableStateOf<List<PlaceSearch.Found>>(emptyList()) }
                var finding by remember(field) { mutableStateOf(false) }
                val webQuery = query.trim().takeIf { it.length >= 2 && it != state.searchedPlace?.name && it != state.searchedOrigin?.name }
                LaunchedEffect(field, webQuery) {
                    found = emptyList()
                    if (webQuery == null) return@LaunchedEffect
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    finding = true
                    found = PlaceSearch.search(webQuery, state.currentPlace)
                    finding = false
                }
                GlassCard(Modifier.weight(1f).fillMaxWidth(), padding = 4.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (field == SearchField.ORIGIN) SearchResult(RouteCatalog.CURRENT_LOCATION, "차가 있는 곳") { pick(RouteCatalog.CURRENT_LOCATION) }
                        val matches = RouteCatalog.matching(query)
                        matches.forEach { place -> SearchResult(place.name, distanceText(state, place)) { pick(place.name) } }
                        found.forEach { result ->
                            SearchResult(result.place.name, distanceText(state, result.place), sub = result.address) {
                                vm.choosePlace(field == SearchField.ORIGIN, result.place)
                                focus.clearFocus()
                            }
                        }
                        if (finding) Text("찾는 중…", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), fontSize = 20.sp, color = secondaryText())
                        // 앱 안 검색은 도로명 상세 주소에 약하다. 주소는 내비 검색으로 찾게 남겨 둔다.
                        if (field == SearchField.DESTINATION && typed.isNotBlank()) {
                            SearchResult("\"$typed\" 내비에서 검색", "상세 주소") { vm.searchInNavi(typed) }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    val problem = route == null && state.destination.isNotBlank()
                    if (route != null) {
                        Text(
                            state.destination, Modifier.padding(start = 8.dp),
                            fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            route != null -> (state.naviPreview ?: "${route.totalKm.roundToInt()}km · 약 ${durationText(route.etaMinutes)}") + stopCountText(route.restAreas)
                            state.usesCurrentLocation && state.currentPlace == null -> "현재 위치를 아직 몰라요. 출발 도시를 골라 주세요"
                            state.destination.isBlank() && state.naviAvailable -> "내비에서 목적지를 찍으면 바로 여기로 돌아와요"
                            state.destination.isBlank() -> "목적지를 골라 주세요"
                            else -> "검색해서 목적지를 골라 주세요"
                        },
                        Modifier.padding(start = 8.dp, bottom = 12.dp),
                        fontSize = 22.sp,
                        fontWeight = if (route != null) FontWeight.Normal else FontWeight.SemiBold,
                        color = when {
                            problem -> MaterialTheme.colorScheme.error
                            route != null -> secondaryText()
                            else -> Color.Unspecified
                        },
                    )
                    if (!state.usesCurrentLocation) {
                        Text("내비 안내는 차가 있는 곳에서 시작해요", Modifier.padding(start = 8.dp, bottom = 12.dp), fontSize = 18.sp, color = secondaryText())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            route != null -> {
                                SecondaryPill("내비에서 보기", vm::showInNavi, Modifier.weight(1f), height = 72.dp)
                                PrimaryPill("다음", { vm.goTo(SetupStep.DRIVERS) }, Modifier.weight(1f), height = 72.dp)
                            }
                            state.naviAvailable -> PrimaryPill("내비에서 목적지 찍기", vm::pickInNavi, Modifier.weight(1f), height = 72.dp)
                            else -> PrimaryPill("다음", {}, Modifier.weight(1f), enabled = false, height = 72.dp)
                        }
                    }
                }
            }
        }
    }
}

private enum class SearchField { ORIGIN, DESTINATION }

/** 목적지 칸. 글자를 넣는 대신 누르면 내비 검색 화면이 열린다. 생김새는 옆의 출발지 칸과 맞춘다. */
@Composable
private fun NaviPickRow(destination: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(14.dp).background(END_COLOR, CircleShape))
        Row(
            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_search), null, tint = secondaryText())
            Text(
                destination.ifBlank { "내비에서 목적지 찍기" },
                Modifier.weight(1f),
                fontSize = 22.sp,
                color = if (destination.isBlank()) secondaryText() else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(if (destination.isBlank()) "내비" else "다시 찍기", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** " · 휴게소 6곳 · 졸음쉼터 3곳". 졸음쉼터는 식당·주유소가 없어 따로 센다. */
private fun stopCountText(stops: List<RestArea>): String {
    val drowsy = stops.count { it.drowsy }
    return " · 휴게소 ${stops.size - drowsy}곳" + if (drowsy > 0) " · 졸음쉼터 ${drowsy}곳" else ""
}

/** 현재 위치에서 곧게 잰 거리. 내비 검색 결과처럼 고르는 데 참고만 한다. */
private const val SEARCH_DEBOUNCE_MILLIS = 400L

private fun distanceText(state: ShiftUiState, place: Place): String {
    val here = state.currentPlace ?: return ""
    val km = hypot((place.lat - here.lat) * 111.3, (place.lng - here.lng) * 111.3 * cos(Math.toRadians(here.lat)))
    return "약 ${km.roundToInt()}km"
}

/**
 * 색 점, 돋보기, 입력칸, 지우기 버튼이 한 줄인 내비식 검색창.
 * 한글 조합 중인 글자가 끊기지 않도록 입력값을 이 안에서 들고 있고, 바깥 값이 달라졌을 때만 맞춘다.
 */
@Composable
private fun SearchRow(
    dot: Color, placeholder: String, value: String, onChange: (String) -> Unit,
    onSearch: (String) -> Unit = {}, onActive: (Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    val focus = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(14.dp).background(dot, CircleShape))
        TextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            modifier = Modifier.weight(1f).onFocusChanged { onActive(it.isFocused) },
            placeholder = { Text(placeholder, fontSize = 22.sp) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton({ text = ""; onChange("") }) { Icon(painterResource(R.drawable.ic_close), "지우기") }
                }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text); focus.clearFocus() }),
            shape = RoundedCornerShape(18.dp),
            colors = filledFieldColors(),
        )
    }
}

@Composable
private fun filledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
private fun SearchResult(name: String, detail: String, sub: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(24.dp), tint = secondaryText())
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 18.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(detail, fontSize = 20.sp, color = secondaryText())
    }
    HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 누가 운전하는지 한 화면에서 정한다. 칸 수가 곧 운전자 수이고, 위 칸부터 차례로 운전한다.
 * 등록한 운전자는 눌러서 넣고 빼며, 처음 타는 사람은 칸을 늘려 이름만 적는다.
 */
@Composable
private fun DriversStep(state: ShiftUiState, vm: ShiftViewModel) {
    if (state.profiles.isNotEmpty()) {
        Text("등록한 운전자 · 눌러서 넣고 빼요", Modifier.padding(start = 8.dp), fontSize = 20.sp, color = secondaryText())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.profiles.forEach { profile ->
                val order = state.drivers.indexOfFirst { it.profileId == profile.id }
                ToggleChip(profile.name, order >= 0, { vm.toggleProfile(profile) }, leading = if (order >= 0) "${order + 1}" else "+")
            }
        }
    }
    Text(
        if (state.solo) "혼자 가면 1시간~1시간 반마다 쉴 곳을 알려 드려요." else "1번부터 차례로 운전해요. ${state.drivers.size}명이 번갈아 가요.",
        Modifier.padding(start = 8.dp, top = 4.dp),
        fontSize = 20.sp,
        color = secondaryText(),
    )
    state.drivers.forEachIndexed { index, driver ->
        // 가운데 칸을 빼도 입력 중인 이름이 옆 칸으로 옮겨 가지 않게 운전자마다 따로 기억한다.
        key(driver.id) { DriverRow(index, driver, state, vm) }
    }
    if (state.drivers.size < MAX_DRIVERS) {
        SecondaryPill("운전자 추가", vm::addDriver, Modifier.fillMaxWidth(), height = 68.dp, fontSize = 22)
    }
}

@Composable
private fun DriverRow(index: Int, driver: Driver, state: ShiftUiState, vm: ShiftViewModel) {
    GroupCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).background(state.colorOf(driver), CircleShape), contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f)) {
                // 등록한 운전자는 이름을 고칠 일이 없다. 고치려면 설정에서 한다.
                if (driver.profileId != null) {
                    Text(driver.name, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    NameField(if (state.solo) "이름" else "운전자 ${index + 1} 이름", driver.name) { vm.renameDriver(driver.id, it) }
                }
            }
            if (state.drivers.size > 1) {
                IconButton({ vm.removeDriver(driver.id) }, Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_close), "${driver.name.ifBlank { "이 운전자" }} 빼기", Modifier.size(28.dp), tint = secondaryText())
                }
            }
        }
        Row(Modifier.padding(start = 66.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleChip("초보 운전자", driver.novice, { vm.toggleNovice(driver.id) })
            ToggleChip("야간 운전 피하기", driver.avoidNight, { vm.toggleAvoidNight(driver.id) })
        }
    }
}

/** 한글 조합이 끊기지 않도록 입력값을 이 안에서 들고 있는다. */
@Composable
private fun NameField(placeholder: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    // 등록한 운전자를 고르면 바깥 값이 바뀐다.
    LaunchedEffect(value, text) { if (value != text) text = value }
    val focus = LocalFocusManager.current
    TextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, fontSize = 22.sp) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        shape = RoundedCornerShape(18.dp),
        colors = filledFieldColors(),
    )
}

/** 계획을 한눈에 본다. 위에 도착 시각과 쉬는 횟수, 가운데 시간표, 아래에 출발 전에 맞출 것을 둔다. */
@Composable
private fun ReviewStep(state: ShiftUiState, vm: ShiftViewModel) {
    val segments = state.plan?.segments.orEmpty()
    if (segments.isEmpty()) return
    val stops = segments.count { it.endStop != null }
    GroupCard {
        Text("${state.originLabel} → ${state.destination}", fontSize = 22.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlanStat("도착", TIME.format(segments.last().end), Modifier.weight(1f))
            PlanStat("쉬는 시간 포함", durationText(ChronoUnit.MINUTES.between(segments.first().start, segments.last().end).toInt()), Modifier.weight(1f))
            PlanStat(state.word, if (stops == 0) "없음" else "${stops}번", Modifier.weight(1f))
        }
        // 내비 값이 있으면 그것만 쓴다. 내장 도로망 거리와 함께 쓰면 거리가 둘이라 헷갈린다.
        Text(state.naviPreview ?: "${state.route.totalKm.roundToInt()}km", fontSize = 20.sp, color = secondaryText())
        if (state.solo) Text("1시간~1시간 반마다 15분 쉬어 가요.", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
    }
    GroupCard { PlanTimeline(state, segments) }
    state.firstDriver?.let { ChangeChecklist(state, it, vm, "${it.name} 님 출발 전에 맞출 것") }
}

@Composable
private fun PlanStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, fontSize = 18.sp, color = secondaryText())
        Text(value, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 출발부터 도착까지 세로 시간표. 점은 들르는 곳, 그 사이 선은 한 사람이 운전하는 구간이다.
 * 교대 시간은 앞 구간이 끝나고 다음 구간이 시작하기까지다.
 */
@Composable
private fun PlanTimeline(state: ShiftUiState, segments: List<Segment>) {
    val first = segments.first()
    TimelineStop(TIME.format(first.start), state.originLabel, "${first.driver.name} 님 출발", START_COLOR, top = false, bottom = true)
    segments.forEachIndexed { index, segment ->
        TimelineDrive(segment, state.colorOf(segment.driver))
        val stop = segment.endStop
        val next = segments.getOrNull(index + 1)
        if (stop != null && next != null) {
            val rest = ChronoUnit.MINUTES.between(segment.end, next.start)
            TimelineStop(
                TIME.format(segment.end), stop.name,
                if (state.solo) "${rest}분 쉬기" else "${next.driver.name} 님으로 교대 · ${rest}분",
                MaterialTheme.colorScheme.primary, top = true, bottom = true,
                sub = stop.facilities.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            )
        } else {
            TimelineStop(TIME.format(segment.end), state.destination, "도착", END_COLOR, top = true, bottom = false)
        }
    }
}

@Composable
private fun TimelineStop(time: String, title: String, detail: String, dot: Color, top: Boolean, bottom: Boolean, sub: String? = null) {
    val line = MaterialTheme.colorScheme.outlineVariant
    TimelineRow(time, rail = {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(4.dp).height(10.dp).background(if (top) line else Color.Transparent))
            Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape).border(5.dp, dot, CircleShape))
            Box(Modifier.width(4.dp).weight(1f).background(if (bottom) line else Color.Transparent))
        }
    }) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, fontSize = 20.sp, color = secondaryText())
        sub?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun TimelineDrive(segment: Segment, color: Color) {
    // 운전 구간의 선은 그 사람 색으로 칠해, 시간표만 봐도 누가 얼마나 모는지 보인다.
    TimelineRow(null, rail = {
        Box(Modifier.width(6.dp).fillMaxHeight().align(Alignment.Center).background(color, CircleShape))
    }) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(segment.driver.name, color, 40.dp, 18)
            Column {
                Text("${segment.driver.name} 운전 · ${durationText(segment.minutes.toInt())}", fontSize = 22.sp)
                if (segment.nightMinutes > 0) Text("야간 ${durationText(segment.nightMinutes)} 포함", fontSize = 18.sp, color = secondaryText())
                if (segment.overLimit) Text("연속 운전 한도를 넘는 구간이에요", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 왼쪽에 시각, 가운데 선과 점, 오른쪽에 내용. 선이 내용 높이만큼 이어지도록 높이를 내용에 맞춘다. */
@Composable
private fun TimelineRow(time: String?, rail: @Composable BoxScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            time.orEmpty(),
            Modifier.width(80.dp).padding(top = 4.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
        Box(Modifier.width(28.dp).fillMaxHeight(), content = rail)
        Column(Modifier.weight(1f).padding(bottom = 12.dp), content = content)
    }
}

/** 시작 화면의 톱니바퀴로 들어오는 화면. 정차 중에만 보인다. */
@Composable
private fun SettingsScreen(state: ShiftUiState, vm: ShiftViewModel) {
    AnimatedContent(state.editingProfile, Modifier.fillMaxSize(), contentKey = { it?.id }, transitionSpec = {
        push(forward = targetState != null)
    }, label = "profile") { editing ->
        if (editing != null) ProfileEditor(editing, state.profiles.any { saved -> saved.id == editing.id }, vm) else SettingsList(state, vm)
    }
}

@Composable
private fun SettingsList(state: ShiftUiState, vm: ShiftViewModel) {
    BackHandler(onBack = vm::closeSettings)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircleButton(R.drawable.ic_back, "뒤로", vm::closeSettings)
        Text("설정", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("화면 테마", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            Segmented(ThemeMode.entries.toList(), state.themeMode, { it.label }, vm::setThemeMode)
            Text(
                when (state.themeMode) {
                    ThemeMode.SYSTEM -> "차량의 주간·야간 설정을 따라가요."
                    ThemeMode.LIGHT -> "항상 밝은 화면으로 보여요."
                    ThemeMode.DARK -> "항상 어두운 화면으로 보여요."
                },
                fontSize = 20.sp,
                color = secondaryText(),
            )
        }
        Text("휴게소", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            ToggleRow("화물차 휴게소도 쓰기", state.truckStops) { vm.setTruckStops(!state.truckStops) }
            Text(
                if (state.truckStops) "화물차 운전자용 휴게소 22곳도 교대·휴식 장소로 골라요. 이름 끝에 \"화물차휴게소\"가 붙어요."
                else "화물차를 운전한다면 켜 주세요. 켜면 화물차 휴게소도 교대·휴식 장소로 골라요.",
                fontSize = 20.sp, lineHeight = 28.sp, color = secondaryText(),
            )
        }
        Text("운전자", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            state.profiles.forEach { profile ->
                Column(Modifier.fillMaxWidth().clickable { vm.editProfile(profile) }.padding(vertical = 4.dp)) {
                    Text(profile.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(profile.tasteText.ifEmpty { "취향을 아직 넣지 않았어요" }, fontSize = 20.sp, color = secondaryText())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SecondaryPill("운전자 추가", vm::addProfile, Modifier.fillMaxWidth(), height = 64.dp, fontSize = 20)
        }
        Text("내비 위 안내", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            val context = LocalContext.current
            // 권한 설정 화면에서 켜고 돌아오면 다시 확인한다. 처음 그릴 때 한 번만 보면 켠 뒤에도 "필요해요"가 남는다.
            var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
            LifecycleResumeEffect(Unit) {
                allowed = Settings.canDrawOverlays(context)
                onPauseOrDispose {}
            }
            Text(if (allowed) "내비를 보는 동안 다음 ${Josa.object_(state.word)} 작게 띄워요." else "내비 위에 안내를 띄우려면 권한이 필요해요.", fontSize = 20.sp, color = secondaryText())
            // 알림이 꺼져 있으면 앱이 가려졌을 때 교대·휴식 알림이 오지 않는다. 카드와 음성은 그대로라 안내만 한다.
            var notifying by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
            LifecycleResumeEffect(Unit) {
                notifying = NotificationManagerCompat.from(context).areNotificationsEnabled()
                onPauseOrDispose {}
            }
            if (!notifying) {
                Text("알림이 꺼져 있어 내비를 보는 동안 교대·휴식 알림이 오지 않아요. 내비 위 안내와 음성은 그대로 나와요.", fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.error)
                SecondaryPill("알림 켜러 가기", {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, Modifier.fillMaxWidth(), height = 64.dp, fontSize = 20)
            }
            if (!allowed) {
                SecondaryPill("권한 설정 열기", {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }, Modifier.fillMaxWidth(), height = 64.dp, fontSize = 20)
            }
        }
        Text("내비 옆에 놓기", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            Text(
                "출발하면 내비가 화면을 덮어요. 그때 화면 오른쪽 끝 ⋮를 왼쪽으로 끌면 내비는 가운데, 교대 플래너는 오른쪽에 나란히 떠요. 한 번 나누면 계속 그대로예요.",
                fontSize = 20.sp,
                lineHeight = 28.sp,
                color = secondaryText(),
            )
        }
        Text("정보", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            Row(Modifier.fillMaxWidth()) {
                Text("버전", Modifier.weight(1f), fontSize = 22.sp)
                Text(BuildConfig.VERSION_NAME, fontSize = 22.sp, color = secondaryText())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("고속도로·휴게소 자료 © OpenStreetMap contributors", fontSize = 18.sp, color = secondaryText())
            Text("해안선 자료 Natural Earth", fontSize = 18.sp, color = secondaryText())
            Text("지도 © OpenFreeMap · OpenMapTiles · OpenStreetMap contributors", fontSize = 18.sp, color = secondaryText())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "경로 미리보기를 그리려고 출발지·목적지·휴게소 좌표를 OSRM 공개 서버(router.project-osrm.org)로, 장소를 찾으려고 검색어와 차의 대략적인 위치를 Photon 검색 서버(photon.komoot.io)로 보내요. 이름이나 차량 정보는 보내지 않아요.",
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = secondaryText(),
            )
        }
    }
}

/** 운전자 한 명의 정보와 취향을 미리 넣어 두는 화면. */
@Composable
private fun ProfileEditor(profile: DriverProfile, saved: Boolean, vm: ShiftViewModel) {
    BackHandler(onBack = vm::closeProfile)
    var draft by remember(profile.id) { mutableStateOf(profile) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircleButton(R.drawable.ic_back, "뒤로", vm::closeProfile)
        Text(if (saved) "운전자 고치기" else "운전자 추가", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        GroupCard {
            NameField("이름", draft.name) { draft = draft.copy(name = it) }
            ToggleRow("초보 운전자", draft.novice) { draft = draft.copy(novice = !draft.novice) }
            ToggleRow("야간 운전 피하기", draft.avoidNight) { draft = draft.copy(avoidNight = !draft.avoidNight) }
        }
        Text("취향", Modifier.padding(start = 8.dp, top = 8.dp), fontSize = 20.sp, color = secondaryText())
        GroupCard {
            NameField("좋아하는 음악 (장르, 가수)", draft.music) { draft = draft.copy(music = it) }
            NameField("플레이리스트 링크 (멜론·유튜브 뮤직 등 공유 링크)", draft.playlistUrl, KeyboardType.Uri) { draft = draft.copy(playlistUrl = it.trim()) }
            if (draft.playlistUrl.isNotEmpty()) {
                val valid = MusicLinks.isValid(draft.playlistUrl)
                Text(
                    if (valid) "${MusicLinks.label(draft.playlistUrl)} · 교대 때 이 앱으로 열어 드려요" else "음악 앱의 공유 링크(https://…)를 붙여 넣어 주세요",
                    fontSize = 18.sp,
                    color = if (valid) secondaryText() else MaterialTheme.colorScheme.error,
                )
            }
            NameField("선호 실내 온도 (도)", draft.temperature, KeyboardType.Number) { typed ->
                draft = draft.copy(temperature = typed.filter(Char::isDigit).take(2))
            }
            if (draft.temperature.isNotEmpty() && draft.temperatureCelsius == null) {
                Text(
                    "온도는 ${DriverProfile.MIN_TEMPERATURE.toInt()}~${DriverProfile.MAX_TEMPERATURE.toInt()}도 사이로 넣어 주세요",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("시트 열선", fontSize = 20.sp, color = secondaryText())
            Segmented(
                listOf<Int?>(null) + DriverChanges.SEAT_HEAT_LEVELS, draft.seatHeat, { it?.let(DriverChanges::seatHeatText) ?: "상관없음" },
                { draft = draft.copy(seatHeat = it) },
            )
            Text("핸들 열선", fontSize = 20.sp, color = secondaryText())
            Segmented(
                listOf(null, false, true), draft.wheelHeat, { it?.let(DriverChanges::wheelHeatText) ?: "상관없음" },
                { draft = draft.copy(wheelHeat = it) },
            )
            Text("메모리 시트", fontSize = 20.sp, color = secondaryText())
            Segmented(
                listOf<Int?>(null) + DriverChanges.SEAT_MEMORY_SLOTS, draft.seatMemory, { it?.let { slot -> "${slot}번" } ?: "안 씀" },
                { draft = draft.copy(seatMemory = it) },
            )
            Text(
                if (draft.seatMemory == null) "차의 메모리 시트(IMS)에 이 사람 자세를 저장해 두었으면 그 번호를 골라 주세요. 교대 때 앱이 불러와요."
                else "교대 때 메모리 시트 ${draft.seatMemory}번을 불러와 시트와 사이드미러를 맞춰요. 룸미러는 직접 맞춰 주세요.",
                fontSize = 18.sp, lineHeight = 26.sp, color = secondaryText(),
            )
            NameField("메모 (예: 등받이 조금 세우기)", draft.note) { draft = draft.copy(note = it) }
        }
        PrimaryPill(
            "저장", { vm.saveProfile(draft) }, Modifier.fillMaxWidth(),
            enabled = draft.name.isNotBlank() && (draft.temperature.isEmpty() || draft.temperatureCelsius != null) &&
                (draft.playlistUrl.isEmpty() || MusicLinks.isValid(draft.playlistUrl)),
        )
        if (saved) {
            // 잘못 눌러 지워지지 않게 한 번 더 누르게 한다.
            var confirming by remember { mutableStateOf(false) }
            SecondaryPill(
                if (confirming) "한 번 더 누르면 삭제돼요" else "삭제",
                { if (confirming) vm.deleteProfile(profile.id) else confirming = true },
                Modifier.fillMaxWidth(), height = 64.dp, fontSize = 20,
            )
        }
    }
}

/** 주행 중 내비 옆에 두는 패널. 한 줄 안내와 큰 버튼 두 개만 둔다. */
@Composable
private fun DrivingPanel(state: ShiftUiState, vm: ShiftViewModel, onHome: () -> Unit) {
    val trip = state.trip
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (trip == null) {
            // 계획 없이 출발했다. 운전 중에는 입력을 받지 않으니 무엇을 하면 되는지만 크게 알린다.
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    Modifier.size(96.dp).background(colorResource(R.color.ic_launcher_background), RoundedCornerShape(24.dp)),
                )
                Text("아직 계획이 없어요", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("기어를 P에 넣으면 목적지와 운전자를 정할 수 있어요.", fontSize = 22.sp, color = secondaryText(), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            return@Column
        }
        DriverStrip(state, trip, onHome)
        SpeedPermissionNotice(state)
        // 내비가 아직 값을 주지 않았으면 빈 칸을 띄우지 않는다. 내비 옆에 놓였으면 같은 안내가 바로 옆에 있다.
        if (!state.besideNavi) state.naviLive?.takeIf { it.turnMeters != null || it.destinationKm != null }?.let { NaviCard(it) }
        if (state.listening) {
            val hint = when {
                state.musicAsk != null -> "\"응\" 또는 \"아니\""
                state.proposal != null -> "\"응\" 또는 \"그대로\""
                else -> "\"응\" 또는 \"미뤄 줘\""
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).background(GREEN, CircleShape))
                Text("듣고 있어요 · $hint", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.overLimit) {
            Text(
                if (state.next != null) "연속 운전 한도를 넘었어요. 다음 휴게소에서 ${if (state.solo) "쉬어" else "교대해"} 주세요."
                else "연속 운전 한도를 넘었어요. 안전한 곳에서 쉬어 가세요.",
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val proposal = state.proposal
        val next = state.next
        val musicAsk = state.musicAsk
        when {
            // 교대 직후에는 다음 교대가 한참 뒤라, 대답할 때까지 질문만 크게 보여 준다.
            musicAsk != null -> MusicAskCard(musicAsk, vm)
            proposal != null -> {
                GroupCard {
                    Text("정체로 늦어지고 있어요", fontSize = 24.sp, color = secondaryText())
                    Text("${Josa.toward(proposal.shiftStops.firstOrNull()?.name ?: "목적지")} 바꿀까요?", fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                TwoButtons("바꾸기", vm::applyProposal, "그대로", vm::dismissProposal)
            }
            state.arrived -> GroupCard {
                Text("${next?.stop?.name} 도착", fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                Text("기어를 P에 넣으면 ${state.word} 화면이 열려요.", fontSize = 24.sp, color = secondaryText())
            }
            state.finished -> GroupCard {
                Text("목적지 도착", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("기어를 P에 넣어 주세요.", fontSize = 24.sp, color = secondaryText())
            }
            next == null -> {
                GroupCard { Text("남은 ${state.word} 없음", fontSize = 38.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                ShiftHereButton(state, vm)
            }
            else -> {
                GroupCard {
                    Text("다음 ${state.word}", fontSize = 24.sp, color = secondaryText())
                    Text(next.stop.name, fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                    Text("${durationText(next.minutesLeft)} 후", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    // 편의시설 자료가 없는 휴게소가 많다. 없다는 말은 빼고 있을 때만 쓴다.
                    next.stop.facilities.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontSize = 20.sp, color = secondaryText()) }
                    if (!state.solo) {
                        next.nextDriver?.let { driver ->
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("다음 운전자", fontSize = 22.sp, color = secondaryText())
                                Avatar(driver.name, state.colorOf(driver), 36.dp, 16)
                                Text(driver.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                LaterStops(state, next.stop.km)
                Spacer(Modifier.weight(1f))
                if (state.accepted) {
                    Text(if (state.waypointSet) "내비 경유지로 등록했어요" else "${state.wordWithObject} 수락했어요", Modifier.padding(start = 8.dp), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    TwoButtons("수락", vm::accept, "미루기", vm::postpone)
                }
                ShiftHereButton(state, vm)
            }
        }
    }
}

/**
 * 속도 권한이 없으면 왜 거리가 늘지 않는지 알린다. 정차 중에만 설정을 여는 버튼을 둔다.
 * 권한은 앱 정보 화면에서 켠다(속도는 위험 권한이라 앱이 한 번 거절당하면 다시 묻기 어렵다).
 */
@Composable
private fun SpeedPermissionNotice(state: ShiftUiState) {
    if (state.speedAllowed) return
    val context = LocalContext.current
    GroupCard(padding = 16.dp) {
        Text("차량 속도 권한이 꺼져 있어요", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
        Text("간 거리와 정체를 셀 수 없어 교대 때를 정확히 알려 드리지 못해요.", fontSize = 18.sp, lineHeight = 26.sp, color = secondaryText())
        if (!state.driving) {
            SecondaryPill("권한 켜러 가기", {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }, Modifier.fillMaxWidth(), height = 60.dp, fontSize = 20)
        }
    }
}

/** 다음 교대 뒤에 남은 곳. 시각은 정체와 데모 배속으로 자주 바뀌어 이름만 차례로 잇는다. */
@Composable
private fun LaterStops(state: ShiftUiState, afterKm: Double) {
    val later = state.plan?.segments.orEmpty().mapNotNull { segment -> segment.endStop?.takeIf { it.km > afterKm + 1e-6 }?.name }
    // 좁은 옆 창에서도 한 줄에 들어가게 두 곳까지만 이름을 쓰고 나머지는 개수로 줄인다.
    val names = later.take(LATER_NAMES) + listOfNotNull((later.size - LATER_NAMES).takeIf { it > 0 }?.let { "${it}곳 더" })
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("이후", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = secondaryText())
        Text((names + "${state.destination} 도착").joinToString("  ›  "), fontSize = 20.sp, color = secondaryText(), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val LATER_NAMES = 2

/**
 * 주행 화면 맨 위. 지금 누가 몰고 몇 분째인지, 다음 교대까지 얼마나 왔는지를 막대로 보여 준다.
 * 막대는 그 사람 색이고, 연속 운전 한도를 넘으면 빨갛게 바뀐다.
 */
@Composable
private fun DriverStrip(state: ShiftUiState, trip: TripState, onHome: () -> Unit) {
    val driven = trip.minutesSinceShift.toInt()
    val color = if (state.overLimit) MaterialTheme.colorScheme.error else state.colorOf(trip.driver)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(trip.driver.name, state.colorOf(trip.driver), 56.dp, 24)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${trip.driver.name} 운전 중", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${durationText(driven)}째", fontSize = 22.sp, color = secondaryText())
            }
            state.next?.takeIf { !state.arrived && !state.finished }?.let { next ->
                Meter(driven / (driven + next.minutesLeft).coerceAtLeast(1).toFloat(), color, height = 10.dp)
            }
        }
        // 주행 중에는 여행을 끝낼 수 없다. 정차한 뒤에 누른다.
        CircleButton(R.drawable.ic_home, "처음으로", onHome, enabled = !state.driving)
    }
}

/** iOS 내비게이션처럼 새 화면이 옆에서 밀려 들어온다. 뒤로 가면 반대 방향으로 밀려난다. */
private fun push(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(SCREEN_MILLIS)
    return if (forward) {
        (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(SCREEN_MILLIS))) togetherWith
            (slideOutHorizontally(spec) { -it / 6 } + fadeOut(tween(SCREEN_MILLIS / 2)))
    } else {
        (slideInHorizontally(spec) { -it / 6 } + fadeIn(tween(SCREEN_MILLIS))) togetherWith
            (slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(SCREEN_MILLIS / 2)))
    }
}

private const val SCREEN_MILLIS = 320

/**
 * 잘못 눌러 여행이 끝나지 않도록 한 번 더 묻는다. iOS 시트처럼 아래에서 올라온다.
 * 대화상자 창은 앱의 1.5배 스케일을 받지 않아 화면 위에 직접 그린다.
 */
@Composable
private fun BoxScope.EndTripSheet(visible: Boolean, onContinue: () -> Unit, onEnd: () -> Unit) {
    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(tween(SCREEN_MILLIS)), exit = fadeOut(tween(SCREEN_MILLIS))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onContinue),
            contentAlignment = Alignment.BottomCenter,
        ) {
            GlassCard(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(24.dp)
                    .animateEnterExit(enter = slideInVertically(tween(SCREEN_MILLIS)) { it }, exit = slideOutVertically(tween(SCREEN_MILLIS)) { it })
                    .clickable(remember { MutableInteractionSource() }, null) {},
                padding = 24.dp,
            ) {
                Text("여행을 끝낼까요?", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("교대 계획과 내비 안내가 끝나고 처음 화면으로 돌아가요.", Modifier.padding(vertical = 12.dp), fontSize = 24.sp, lineHeight = 32.sp, color = secondaryText())
                TwoButtons("계속 가기", onContinue, "끝내기", onEnd)
            }
        }
    }
}

/** 교대를 마친 뒤 새 운전자의 플레이리스트를 틀지 묻는다. 말로 "응"이라고 해도 된다. */
@Composable
private fun MusicAskCard(ask: MusicAsk, vm: ShiftViewModel) {
    GroupCard {
        Text("${ask.driverName} 님 ${Josa.object_(ask.label)} 틀까요?", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
        if (ask.failed) {
            Text("이 차에서 이 링크를 열 수 있는 음악 앱이 없어요", fontSize = 22.sp, color = MaterialTheme.colorScheme.error)
            SecondaryPill("닫기", vm::skipMusic, Modifier.fillMaxWidth(), height = 72.dp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryPill("틀기", vm::playMusic, Modifier.weight(1f), height = 80.dp, fontSize = 28)
                SecondaryPill("괜찮아요", vm::skipMusic, Modifier.weight(1f), height = 80.dp, fontSize = 28)
            }
        }
    }
}

/** 수락하지 않은 곳에 섰을 때 쓰는 버튼. */
@Composable
private fun ShiftHereButton(state: ShiftUiState, vm: ShiftViewModel) {
    if (state.driving || state.arrived || state.finished || state.proposal != null) return
    SecondaryPill(if (state.solo) "여기서 쉬기" else "여기서 교대하기", vm::shiftHere, Modifier.fillMaxWidth(), height = 80.dp, fontSize = 26)
}

/** 내비에서 가져온 정보. 다음 회전을 가장 크게 두고 나머지는 한 줄로 묶는다. */
@Composable
private fun NaviCard(live: NaviLive) {
    GroupCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (live.turnMeters != null) {
                Text(meterText(live.turnMeters), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(live.turnText.orEmpty(), Modifier.weight(1f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            live.speedLimit?.let { limit ->
                Box(Modifier.size(52.dp).border(4.dp, END_COLOR, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$limit", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val parts = listOfNotNull(
            live.destinationKm?.let { "도착까지 ${it}km" },
            live.destinationMinutes?.let { durationText(it) },
            live.congestionKm?.takeIf { it > 0 }?.let { "정체 ${it}km" },
        )
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 20.sp, color = secondaryText())
    }
}

private fun meterText(meters: Int) = if (meters >= 1000) "%.1fkm".format(meters / 1000f) else "${meters}m"

@Composable
private fun TwoButtons(first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PrimaryPill(first, onFirst, Modifier.weight(1f), height = 104.dp, fontSize = 32)
        SecondaryPill(second, onSecond, Modifier.weight(1f), height = 104.dp, fontSize = 32)
    }
}

/**
 * 교대 휴게소에 정차하면 뜨는 풀스크린 화면.
 * 교대할 때는 왼쪽에 누가 받는지, 오른쪽에 맞출 것을 나란히 두어 스크롤 없이 본다. 혼자 쉬면 맞출 것이 없어 한 단으로 둔다.
 */
@Composable
private fun ShiftScreen(state: ShiftUiState, vm: ShiftViewModel, onHome: () -> Unit) {
    val trip = state.trip ?: return
    val nextDriver = state.next?.nextDriver ?: trip.driver
    val atStop = state.next?.stop?.takeIf { it.name == state.shiftPlaceName }
    val left = state.plan?.segments.orEmpty().count { segment -> segment.endStop?.let { it.km > trip.km + REST_STOP_NEAR_KM } == true }
    val summary: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.shiftPlaceName, Modifier.weight(1f), fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CircleButton(R.drawable.ic_home, "처음으로", onHome)
        }
        Text("${trip.driver.name} 님, ${durationText(trip.minutesSinceShift.toInt())} 운전하셨어요", fontSize = 22.sp, color = secondaryText())
        GroupCard {
            if (state.solo) {
                Text("15분 쉬고 출발하세요", fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold)
                state.restedMinutes?.let { rested ->
                    RestBar(rested / REST_MINUTES.toFloat())
                    Text(
                        if (rested >= REST_MINUTES) "${rested}분 쉬었어요 · 준비되면 출발하세요" else "${rested}분 쉬었어요 · ${REST_MINUTES - rested}분 남았어요",
                        fontSize = 22.sp, color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text("다음 운전자", fontSize = 22.sp, color = secondaryText())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(nextDriver.name, state.colorOf(nextDriver), 72.dp, 32)
                    Text(nextDriver.name, fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            atStop?.facilities?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlanStat("남은 거리", "${(state.route.totalKm - trip.km).roundToInt().coerceAtLeast(0)}km", Modifier.weight(1f))
            PlanStat("남은 ${state.word}", if (left == 0) "없음" else "${left}번", Modifier.weight(1f))
        }
    }
    val finish = @Composable { modifier: Modifier ->
        PrimaryPill(if (state.solo) "휴식 끝" else "교대 완료", vm::completeShift, modifier.fillMaxWidth(), height = 96.dp, fontSize = 32)
    }
    if (state.solo) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = summary)
            finish(Modifier)
        }
    } else {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = summary)
                finish(Modifier)
            }
            Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                ChangeChecklist(state, nextDriver, vm, "${nextDriver.name} 님으로 바뀌면 맞출 것")
            }
        }
    }
}

/** 혼자 쉴 때 15분 중 얼마나 쉬었는지. 다 차면 초록으로 바뀐다. */
@Composable
private fun RestBar(fraction: Float) {
    Meter(fraction.coerceAtLeast(0.02f), if (fraction >= 1f) GREEN else MaterialTheme.colorScheme.primary, height = 14.dp)
}

private const val REST_MINUTES = 15

/** 이만큼 안쪽 휴게소는 지금 쉬는 곳으로 보고 남은 횟수에서 뺀다. */
private const val REST_STOP_NEAR_KM = 3.0

/**
 * 운전자가 바뀔 때 챙길 것. 차에서 바꿀 것은 지금 상태와 다르면 미리 체크해 두고, 운전자가 확인해 누르면 그것만 바꾼다.
 * 직접 할 것(미러, 시트, 음악)은 마치고 체크한다.
 */
@Composable
private fun ChangeChecklist(state: ShiftUiState, driver: Driver, vm: ShiftViewModel, title: String) {
    LaunchedEffect(driver.id) { vm.prepareChanges(driver) }
    val list = state.changes?.takeIf { it.driver.id == driver.id } ?: return
    GroupCard {
        Text(title, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        if (list.loading) {
            Text("차 상태를 읽고 있어요", fontSize = 20.sp, color = secondaryText())
            return@GroupCard
        }
        val auto = list.items.filter { it.kind.auto }
        val manual = list.items.filterNot { it.kind.auto }
        if (auto.isNotEmpty()) {
            Text("차에서 바로 바꿀 것 · 바꿔야 하는 것은 미리 체크했어요", fontSize = 18.sp, color = secondaryText())
            auto.forEach { item ->
                val result = list.done[item.kind]
                ChangeRow(item, item.kind in list.checked, when (result) {
                    true -> "바꿨어요"
                    false -> "못 바꿨어요"
                    null -> null
                }, result == false) { vm.toggleChange(item.kind) }
            }
            PrimaryPill(
                if (list.applying) "바꾸는 중" else "체크한 것 바꾸기", vm::applyChanges, Modifier.fillMaxWidth(),
                enabled = !list.applying && list.checked.isNotEmpty(), height = 72.dp,
            )
        }
        if (manual.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("직접 맞춰 주세요 · 마치면 체크", fontSize = 18.sp, color = secondaryText())
            manual.forEach { item ->
                ChangeRow(item, list.done[item.kind] == true, null, false, action = item.link?.let { "틀기" to vm::openPlaylist }) { vm.toggleChange(item.kind) }
            }
            list.musicMessage?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ChangeRow(
    item: ChangeItem, checked: Boolean, result: String?, failed: Boolean,
    action: Pair<String, () -> Unit>? = null, onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(item.detail, fontSize = 20.sp, color = secondaryText())
        }
        result?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        action?.let { (label, onClick) -> PrimaryPill(label, onClick, height = 56.dp, fontSize = 20) }
    }
}

/** 도착 후 운전자별 분담 요약. 누가 얼마나 몰았는지 막대로 비교한다. */
@Composable
private fun SummaryScreen(state: ShiftUiState, vm: ShiftViewModel) {
    val trip = state.trip ?: return
    val totalKm = trip.drivenKm.values.sum()
    val totalMinutes = trip.drivenMinutes.values.sum().toInt()
    val totalNight = trip.nightMinutes.values.sum().toInt()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${state.destination} 도착", fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${state.originLabel}에서 출발 · 수고하셨어요", fontSize = 22.sp, color = secondaryText())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlanStat("달린 거리", "${totalKm.roundToInt()}km", Modifier.weight(1f))
                PlanStat("운전 시간", durationText(totalMinutes), Modifier.weight(1f))
                PlanStat("야간 운전", if (totalNight == 0) "없음" else durationText(totalNight), Modifier.weight(1f))
            }
            if (!state.solo) {
                GroupCard {
                    Text("운전 분담", fontSize = 22.sp, color = secondaryText())
                    state.drivers.forEachIndexed { index, driver ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val km = trip.drivenKm[driver.id] ?: 0.0
                        DriverShareRow(
                            driver.name,
                            state.colorOf(driver),
                            (trip.drivenMinutes[driver.id] ?: 0.0).toInt(),
                            km,
                            (trip.nightMinutes[driver.id] ?: 0.0).toInt(),
                            if (totalKm > 0) (km / totalKm).toFloat() else 0f,
                        )
                    }
                }
            }
        }
        PrimaryPill("새 여행", vm::reset, Modifier.fillMaxWidth(), height = 96.dp, fontSize = 30)
    }
}

@Composable
private fun DriverShareRow(name: String, color: Color, minutes: Int, km: Double, night: Int, share: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(name, color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(name, Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("${(share * 100).roundToInt()}%", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Meter(share, color, height = 10.dp)
            Text(
                listOfNotNull("${km.roundToInt()}km", durationText(minutes), night.takeIf { it > 0 }?.let { "야간 ${durationText(it)}" }).joinToString(" · "),
                fontSize = 20.sp,
                color = secondaryText(),
            )
        }
    }
}
