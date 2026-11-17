package com.example.irgupsrasp2

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

// --- МОДЕЛИ ДАННЫХ И НАСТРОЕК ---

enum class VisualStyle { MATERIAL_SYSTEM, MONOCHROME, LIQUID_GLASS }
enum class ContrastLevel { LOW, NORMAL, HIGH }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DarkVariant { STANDARD_141414, AMOLED_BLACK }
enum class BarPosition { BOTTOM, TOP }
enum class ScheduleSource { LINK, TABLE }

data class AppSettings(
    val source: ScheduleSource = ScheduleSource.LINK,
    val scheduleUrl: String = "https://rasp.irgups.ru/index.php?action=student&pid=5368&view=table",
    val visualStyle: VisualStyle = VisualStyle.MATERIAL_SYSTEM,
    val contrastLevel: ContrastLevel = ContrastLevel.NORMAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val darkVariant: DarkVariant = DarkVariant.STANDARD_141414,
    val barPosition: BarPosition = BarPosition.BOTTOM,
    val fontScale: Float = 1.0f,
    val mySubgroup: Int = 1,
    val hasCustomBg: Boolean = false,
    val bgBlurRadius: Float = 0f
)

data class LessonItem(
    val id: String = UUID.randomUUID().toString(),
    val dayOfWeekIndex: Int, // 0 = ПН .. 4 = ПТ
    val weekParity: Int = 0, // 0 = обе недели, 1 = числитель, 2 = знаменатель
    val specificDateIso: String? = null,
    val time: String,
    val title: String,
    val type: String,
    val teacher: String,
    val room: String,
    val subgroup: Int? = null, // null - общая, 1 или 2 - только для лабораторных
    val isCustom: Boolean = false
)

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val dateIso: String,
    val text: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrgupsScheduleRoot()
        }
    }
}

// --- КОРНЕВОЙ КОМПОНЕНТ И ТЕМИЗАЦИЯ ---

@Composable
fun IrgupsScheduleRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("irgups_v4_prefs", Context.MODE_PRIVATE) }

    var settings by remember { mutableStateOf(loadSettings(prefs)) }
    val updateSettings: (AppSettings) -> Unit = { newSettings ->
        settings = newSettings
        saveSettings(prefs, newSettings)
    }

    val systemInDark = androidx.compose.foundation.isSystemInDarkTheme()
    val useDarkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    // Принудительное управление цветом иконок статус-бара
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    val colorScheme = buildCustomColorScheme(
        context = context,
        darkTheme = useDarkTheme,
        style = settings.visualStyle,
        contrast = settings.contrastLevel,
        darkVariant = settings.darkVariant
    )

    val baseDensity = LocalDensity.current
    val customDensity = remember(baseDensity, settings.fontScale) {
        Density(baseDensity.density, baseDensity.fontScale * settings.fontScale)
    }

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainAppContent(
                    settings = settings,
                    onUpdateSettings = updateSettings,
                    prefs = prefs
                )
            }
        }
    }
}

@Composable
fun buildCustomColorScheme(
    context: Context,
    darkTheme: Boolean,
    style: VisualStyle,
    contrast: ContrastLevel,
    darkVariant: DarkVariant
): ColorScheme {
    val isAmoled = darkVariant == DarkVariant.AMOLED_BLACK || style == VisualStyle.MONOCHROME
    val baseBgDark = if (isAmoled) Color(0xFF000000) else Color(0xFF141414)
    val surfaceDark = if (isAmoled) Color(0xFF000000) else Color(0xFF1E1E1E)

    val baseScheme = when (style) {
        VisualStyle.MONOCHROME -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFFFFFFFF),
                    onPrimary = Color(0xFF000000),
                    primaryContainer = Color(0xFF222222),
                    onPrimaryContainer = Color(0xFFFFFFFF),
                    secondary = Color(0xFFCCCCCC),
                    secondaryContainer = Color(0xFF1C1C1C),
                    onSecondaryContainer = Color(0xFFE8E8E8),
                    tertiary = Color(0xFFAAAAAA),
                    background = Color(0xFF000000),
                    surface = Color(0xFF000000),
                    surfaceContainerHigh = Color(0xFF0D0D0D),
                    outlineVariant = Color(0xFF333333)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF111111),
                    onPrimary = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFE0E0E0),
                    onPrimaryContainer = Color(0xFF000000),
                    secondary = Color(0xFF444444),
                    secondaryContainer = Color(0xFFEEEEEE),
                    onSecondaryContainer = Color(0xFF111111),
                    tertiary = Color(0xFF555555),
                    background = Color(0xFFF7F7F7),
                    surface = Color(0xFFFFFFFF),
                    surfaceContainerHigh = Color(0xFFEAEAEA)
                )
            }
        }
        VisualStyle.LIQUID_GLASS -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF70D6FF),
                    onPrimary = Color(0xFF002635),
                    primaryContainer = Color(0xFF004A63),
                    onPrimaryContainer = Color(0xFFC4EFFF),
                    secondary = Color(0xFFB8C4FF),
                    secondaryContainer = Color(0xFF2B3766),
                    onSecondaryContainer = Color(0xFFE0E5FF),
                    background = baseBgDark,
                    surface = surfaceDark,
                    surfaceContainerHigh = Color(0xFF1C2230)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF006492),
                    onPrimary = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFC9E6FF),
                    onPrimaryContainer = Color(0xFF001E2F),
                    secondary = Color(0xFF495D92),
                    secondaryContainer = Color(0xFFDAE2FF),
                    onSecondaryContainer = Color(0xFF001945),
                    background = Color(0xFFEEF3F9),
                    surface = Color(0xFFFFFFFF),
                    surfaceContainerHigh = Color(0xFFE3ECF7)
                )
            }
        }
        VisualStyle.MATERIAL_SYSTEM -> {
            val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }
            if (darkTheme) {
                dynamic.copy(
                    background = baseBgDark,
                    surface = surfaceDark,
                    surfaceContainerHigh = if (isAmoled) Color(0xFF0A0A0A) else Color(0xFF252529)
                )
            } else dynamic
        }
    }

    return when (contrast) {
        ContrastLevel.NORMAL -> baseScheme
        ContrastLevel.HIGH -> {
            if (darkTheme) {
                baseScheme.copy(
                    primary = boostContrast(baseScheme.primary, 1.25f),
                    primaryContainer = boostContrast(baseScheme.primaryContainer, 1.35f),
                    secondaryContainer = boostContrast(baseScheme.secondaryContainer, 1.3f),
                    outlineVariant = Color.White.copy(alpha = 0.45f)
                )
            } else {
                baseScheme.copy(
                    primary = darkenForContrast(baseScheme.primary, 0.75f),
                    primaryContainer = boostContrast(baseScheme.primaryContainer, 1.15f),
                    outlineVariant = Color.Black.copy(alpha = 0.45f)
                )
            }
        }
        ContrastLevel.LOW -> {
            baseScheme.copy(
                primary = blendWithGray(baseScheme.primary, 0.35f),
                primaryContainer = blendWithGray(baseScheme.primaryContainer, 0.35f),
                secondaryContainer = blendWithGray(baseScheme.secondaryContainer, 0.35f)
            )
        }
    }
}

fun boostContrast(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha
)

fun darkenForContrast(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha
)

fun blendWithGray(color: Color, ratio: Float): Color {
    val gray = 0.5f
    return Color(
        red = color.red * (1 - ratio) + gray * ratio,
        green = color.green * (1 - ratio) + gray * ratio,
        blue = color.blue * (1 - ratio) + gray * ratio,
        alpha = color.alpha
    )
}

// --- ГЛАВНЫЙ КОНТРОЛЛЕР ЭКРАНОВ С ПЛАВНЫМИ АНИМАЦИЯМИ ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainAppContent(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    prefs: android.content.SharedPreferences
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showWelcomeDialog by remember { mutableStateOf(!prefs.getBoolean("welcome_shown_v4", false)) }
    var hasAskedTutorialOnMain by remember { mutableStateOf(prefs.getBoolean("tutorial_asked_v4", false)) }
    var showTutorialPromptDialog by remember { mutableStateOf(false) }
    var showTutorialStepsDialog by remember { mutableStateOf(false) }

    var isSettingsOpen by remember { mutableStateOf(false) }
    var highlightSourceBlockTrigger by remember { mutableIntStateOf(0) }

    var showCalendarSheet by remember { mutableStateOf(false) }
    var selectedCalendarDate by remember { mutableStateOf<LocalDate?>(null) }

    val today = remember { LocalDate.now() }
    var selectedWeek by remember { mutableIntStateOf(getWeekParityForDate(today)) }

    var week1Lessons by remember {
        mutableStateOf(loadCachedLessons(prefs, 1).ifEmpty { generateDefaultWeekLessons(1) })
    }
    var week2Lessons by remember {
        mutableStateOf(loadCachedLessons(prefs, 2).ifEmpty { generateDefaultWeekLessons(2) })
    }
    var customLessons by remember { mutableStateOf(loadCustomLessons(prefs)) }
    var notesList by remember { mutableStateOf(loadNotes(prefs)) }

    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var showAddLessonDialogForDate by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var showAddNoteForDateIso by remember { mutableStateOf<String?>(null) }
    var editingNote by remember { mutableStateOf<NoteItem?>(null) }

    var customBgBitmap by remember { mutableStateOf<Bitmap?>(loadCustomBgBitmap(context)) }

    LaunchedEffect(showWelcomeDialog, isSettingsOpen, hasAskedTutorialOnMain) {
        if (!showWelcomeDialog && !isSettingsOpen && !hasAskedTutorialOnMain) {
            hasAskedTutorialOnMain = true
            prefs.edit().putBoolean("tutorial_asked_v4", true).apply()
            showTutorialPromptDialog = true
        }
    }

    fun refreshSchedule() {
        scope.launch {
            isLoading = true
            statusMessage = null
            try {
                if (settings.source == ScheduleSource.LINK) {
                    val (w1, w2) = fetchScheduleFromUrlBothWeeks(settings.scheduleUrl)
                    if (w1.isNotEmpty() || w2.isNotEmpty()) {
                        week1Lessons = w1.ifEmpty { w2 }
                        week2Lessons = w2.ifEmpty { w1 }
                        saveCachedLessons(prefs, 1, week1Lessons)
                        saveCachedLessons(prefs, 2, week2Lessons)
                        statusMessage = "Расписание успешно обновлено с сайта"
                    } else {
                        statusMessage = "Сайт не вернул пар, используется кэш"
                    }
                } else {
                    statusMessage = "Режим работы с таблицей"
                }
            } catch (e: Exception) {
                statusMessage = "Офлайн-режим (используются кэшированные данные)"
            } finally {
                isLoading = false
            }
        }
    }

    // Плавные переходы между Главным экраном, Настройками и Деталями дня календаря
    val screenState = when {
        isSettingsOpen -> "SETTINGS"
        selectedCalendarDate != null -> "CALENDAR_DETAIL"
        else -> "MAIN"
    }

    AnimatedContent(
        targetState = screenState,
        transitionSpec = {
            fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260)) togetherWith
                    fadeOut(animationSpec = tween(200))
        },
        label = "screenTransition"
    ) { state ->
        when (state) {
            "SETTINGS" -> {
                BackHandler { isSettingsOpen = false }
                SettingsScreen(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    highlightSourceTrigger = highlightSourceBlockTrigger,
                    onBack = { isSettingsOpen = false },
                    onShowTutorial = {
                        isSettingsOpen = false
                        showTutorialStepsDialog = true
                    },
                    onImportTableFile = { uri ->
                        scope.launch {
                            isLoading = true
                            val (w1, w2, totalCount) = withContext(Dispatchers.IO) {
                                parseExcelTableFileBothWeeks(context, uri)
                            }
                            isLoading = false
                            if (totalCount > 0) {
                                week1Lessons = w1.ifEmpty { w2 }
                                week2Lessons = w2.ifEmpty { w1 }
                                saveCachedLessons(prefs, 1, week1Lessons)
                                saveCachedLessons(prefs, 2, week2Lessons)
                                statusMessage = "Таблица успешно загружена ($totalCount пар)"
                                isSettingsOpen = false
                            } else {
                                statusMessage = "Не удалось прочитать таблицу из файла"
                            }
                        }
                    },
                    onCustomBgPicked = { uri ->
                        val bmp = saveAndLoadGalleryBitmap(context, uri)
                        if (bmp != null) {
                            customBgBitmap = bmp
                            onUpdateSettings(settings.copy(hasCustomBg = true))
                        }
                    },
                    onClearCustomBg = {
                        clearCustomBgFile(context)
                        customBgBitmap = null
                        onUpdateSettings(settings.copy(hasCustomBg = false, bgBlurRadius = 0f))
                    }
                )
            }

            "CALENDAR_DETAIL" -> {
                val targetDate = selectedCalendarDate ?: today
                BackHandler { selectedCalendarDate = null }
                CalendarDateDetailScreen(
                    date = targetDate,
                    settings = settings,
                    lessons = getLessonsForSpecificDate(
                        date = targetDate,
                        week1 = week1Lessons,
                        week2 = week2Lessons,
                        custom = customLessons
                    ),
                    notes = notesList.filter { it.dateIso == targetDate.toString() },
                    onBack = { selectedCalendarDate = null },
                    onAddNote = { showAddNoteForDateIso = targetDate.toString() },
                    onEditNote = { editingNote = it },
                    onAddCustomLesson = {
                        val dayIdx = (targetDate.dayOfWeek.value - 1).coerceIn(0, 4)
                        showAddLessonDialogForDate = Pair(dayIdx, targetDate.toString())
                    },
                    onDeleteCustomLesson = { id ->
                        val updated = customLessons.filterNot { it.id == id }
                        customLessons = updated
                        saveCustomLessons(prefs, updated)
                    },
                    customBgBitmap = customBgBitmap
                )
            }

            else -> {
                val initialDayIdx = (today.dayOfWeek.value - 1).coerceIn(0, 4)
                val pagerState = rememberPagerState(initialPage = initialDayIdx, pageCount = { 5 })

                val mondayOfSelectedWeek = remember(today, selectedWeek) {
                    val currentParity = getWeekParityForDate(today)
                    val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                    if (selectedWeek == currentParity) thisMonday else thisMonday.plusWeeks(1)
                }

                // Шапка показывает день недели и дату выбранной вкладки или сегодняшнего дня
                val displayedDate = mondayOfSelectedWeek.plusDays(pagerState.currentPage.toLong())
                val dayOfWeekTitle = remember(displayedDate) {
                    displayedDate.format(DateTimeFormatter.ofPattern("EEEE", Locale("ru")))
                        .replaceFirstChar { it.titlecase(Locale("ru")) }
                }
                val dayMonthTitle = remember(displayedDate) {
                    displayedDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    BackgroundLayer(
                        visualStyle = settings.visualStyle,
                        customBgBitmap = customBgBitmap,
                        blurRadius = settings.bgBlurRadius
                    )

                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = if (settings.visualStyle == VisualStyle.LIQUID_GLASS)
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                ),
                                title = {
                                    AnimatedContent(
                                        targetState = Pair(dayOfWeekTitle, dayMonthTitle),
                                        transitionSpec = {
                                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                        },
                                        label = "topBarTitleAnim"
                                    ) { (dow, dm) ->
                                        Column {
                                            Text(
                                                text = dow,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = dm,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .clickable {
                                                val nextSub = if (settings.mySubgroup == 1) 2 else 1
                                                onUpdateSettings(settings.copy(mySubgroup = nextSub))
                                            }
                                    ) {
                                        Text(
                                            text = "Подгр. ${settings.mySubgroup}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }

                                    IconButton(onClick = { refreshSchedule() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                                    }

                                    IconButton(onClick = { isSettingsOpen = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            if (settings.barPosition == BarPosition.BOTTOM) {
                                DaysAndWeeksBar(
                                    selectedWeek = selectedWeek,
                                    onSelectWeek = { selectedWeek = it },
                                    mondayDate = mondayOfSelectedWeek,
                                    selectedDayIndex = pagerState.currentPage,
                                    onSelectDay = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
                                    getLessonsCountForDay = { dayIdx ->
                                        val d = mondayOfSelectedWeek.plusDays(dayIdx.toLong())
                                        val list = getLessonsForSpecificDate(d, week1Lessons, week2Lessons, customLessons)
                                        list.count { it.subgroup == null || it.subgroup == settings.mySubgroup }
                                    },
                                    visualStyle = settings.visualStyle,
                                    onOpenCalendar = { showCalendarSheet = true },
                                    isBottom = true
                                )
                            }
                        },
                        floatingActionButton = {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    val dayDate = mondayOfSelectedWeek.plusDays(pagerState.currentPage.toLong())
                                    showAddLessonDialogForDate = Pair(pagerState.currentPage, dayDate.toString())
                                },
                                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                text = { Text("Своя пара") }
                            )
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (isLoading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            // Если панель дней ВВЕРХУ — сначала рисуем панель дней, а статус-сообщение НИЖЕ неё
                            if (settings.barPosition == BarPosition.TOP) {
                                DaysAndWeeksBar(
                                    selectedWeek = selectedWeek,
                                    onSelectWeek = { selectedWeek = it },
                                    mondayDate = mondayOfSelectedWeek,
                                    selectedDayIndex = pagerState.currentPage,
                                    onSelectDay = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
                                    getLessonsCountForDay = { dayIdx ->
                                        val d = mondayOfSelectedWeek.plusDays(dayIdx.toLong())
                                        val list = getLessonsForSpecificDate(d, week1Lessons, week2Lessons, customLessons)
                                        list.count { it.subgroup == null || it.subgroup == settings.mySubgroup }
                                    },
                                    visualStyle = settings.visualStyle,
                                    onOpenCalendar = { showCalendarSheet = true },
                                    isBottom = false
                                )
                            }

                            AnimatedVisibility(visible = statusMessage != null) {
                                statusMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) { pageIdx ->
                                val pageDate = mondayOfSelectedWeek.plusDays(pageIdx.toLong())
                                val dayLessons = getLessonsForSpecificDate(
                                    date = pageDate,
                                    week1 = week1Lessons,
                                    week2 = week2Lessons,
                                    custom = customLessons
                                )
                                val dayNotes = notesList.filter { it.dateIso == pageDate.toString() }

                                DayScheduleList(
                                    lessons = dayLessons,
                                    notes = dayNotes,
                                    mySubgroup = settings.mySubgroup,
                                    visualStyle = settings.visualStyle,
                                    onDeleteCustomLesson = { id ->
                                        val updated = customLessons.filterNot { it.id == id }
                                        customLessons = updated
                                        saveCustomLessons(prefs, updated)
                                    },
                                    onNoteClick = { editingNote = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ДИАЛОГИ ---

    if (showWelcomeDialog) {
        AlertDialog(
            onDismissRequest = {
                prefs.edit().putBoolean("welcome_shown_v4", true).apply()
                showWelcomeDialog = false
            },
            title = { Text("Привет!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Добро пожаловать в расписание ИрГУПС. Настройте приложение под себя:", style = MaterialTheme.typography.bodyMedium)
                    Text("• В настройках доступны Liquid Glass, AMOLED, размытие своего фона и настройка шрифтов.\n• Расписание можно загрузить по ссылке или из таблицы (.xlsx).\n• Связь с разработчиком — в разделе помощи.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                prefs.edit().putBoolean("welcome_shown_v4", true).apply()
                                showWelcomeDialog = false
                            },
                            modifier = Modifier.weight(0.75f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) { Text("Ок", maxLines = 1) }
                        FilledTonalButton(
                            onClick = {
                                prefs.edit().putBoolean("welcome_shown_v4", true).apply()
                                showWelcomeDialog = false
                                isSettingsOpen = true
                            },
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) { Text("Настройки", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                        Button(
                            onClick = {
                                prefs.edit().putBoolean("welcome_shown_v4", true).apply()
                                showWelcomeDialog = false
                                isSettingsOpen = true
                                highlightSourceBlockTrigger++
                            },
                            modifier = Modifier.weight(1.35f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) { Text("Выбор расписания", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showTutorialPromptDialog) {
        AlertDialog(
            onDismissRequest = { showTutorialPromptDialog = false },
            title = { Text("Хотите узнать о возможностях и преимуществах этого приложения?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = { Text("Обучение всегда доступно в настройках.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(onClick = {
                    showTutorialPromptDialog = false
                    showTutorialStepsDialog = true
                }) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = { showTutorialPromptDialog = false }) { Text("Нет") }
            }
        )
    }

    if (showTutorialStepsDialog) {
        TutorialDialog(onDismiss = { showTutorialStepsDialog = false })
    }

    if (showCalendarSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            GoogleStyleCalendarContent(
                today = today,
                getLessonsCountForDate = { d ->
                    val list = getLessonsForSpecificDate(d, week1Lessons, week2Lessons, customLessons)
                    list.count { it.subgroup == null || it.subgroup == settings.mySubgroup }
                },
                hasNoteForDate = { d -> notesList.any { it.dateIso == d.toString() } },
                onSelectDate = { pickedDate ->
                    showCalendarSheet = false
                    selectedCalendarDate = pickedDate
                }
            )
        }
    }

    showAddLessonDialogForDate?.let { (dayIdx, dateIso) ->
        AddCustomLessonDialog(
            onDismiss = { showAddLessonDialogForDate = null },
            onAdd = { time, title, type, room, teacher, subgroup ->
                val newLesson = LessonItem(
                    dayOfWeekIndex = dayIdx,
                    specificDateIso = dateIso,
                    time = time,
                    title = title,
                    type = type,
                    room = room,
                    teacher = teacher,
                    subgroup = subgroup,
                    isCustom = true
                )
                val updated = customLessons + newLesson
                customLessons = updated
                saveCustomLessons(prefs, updated)
                showAddLessonDialogForDate = null
            }
        )
    }

    showAddNoteForDateIso?.let { dateIso ->
        NoteEditDialog(
            initialText = "",
            isNew = true,
            onDismiss = { showAddNoteForDateIso = null },
            onSave = { text ->
                val updated = notesList + NoteItem(dateIso = dateIso, text = text)
                notesList = updated
                saveNotes(prefs, updated)
                showAddNoteForDateIso = null
            },
            onDelete = {},
            onExport = { text -> shareNoteToKeepOrExternal(context, dateIso, text) }
        )
    }

    editingNote?.let { note ->
        NoteEditDialog(
            initialText = note.text,
            isNew = false,
            onDismiss = { editingNote = null },
            onSave = { newText ->
                val updated = notesList.map { if (it.id == note.id) it.copy(text = newText) else it }
                notesList = updated
                saveNotes(prefs, updated)
                editingNote = null
            },
            onDelete = {
                val updated = notesList.filterNot { it.id == note.id }
                notesList = updated
                saveNotes(prefs, updated)
                editingNote = null
            },
            onExport = { text -> shareNoteToKeepOrExternal(context, note.dateIso, text) }
        )
    }
}

// --- ПАНЕЛЬ ДНЕЙ И НЕДЕЛЬ (ПРИ НИЖНЕМ ПОЛОЖЕНИИ ЧИСЛИТЕЛЬ/ЗНАМЕНАТЕЛЬ ВЫШЕ ДНЕЙ) ---

@Composable
fun DaysAndWeeksBar(
    selectedWeek: Int,
    onSelectWeek: (Int) -> Unit,
    mondayDate: LocalDate,
    selectedDayIndex: Int,
    onSelectDay: (Int) -> Unit,
    getLessonsCountForDay: (Int) -> Int,
    visualStyle: VisualStyle,
    onOpenCalendar: () -> Unit,
    isBottom: Boolean
) {
    val shortNames = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ")
    val dateFmt = DateTimeFormatter.ofPattern("dd.MM")

    val containerModifier = if (visualStyle == VisualStyle.LIQUID_GLASS) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.09f))
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.14f))
                ),
                RoundedCornerShape(24.dp)
            )
            .padding(10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    }

    Column(
        modifier = containerModifier.animateContentSize(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        )
    ) {
        // Кнопка открытия календаря
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenCalendar() }
                .padding(bottom = 6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = "Календарь",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Календарь",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Если панель ВНИЗУ — кнопки Числитель / Знаменатель располагаются ВЫШЕ кнопок дней
        if (isBottom) {
            WeekParitySelectorRow(selectedWeek = selectedWeek, onSelectWeek = onSelectWeek)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 5 дней недели (ПН–ПТ)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (dayIdx in 0..4) {
                val date = mondayDate.plusDays(dayIdx.toLong())
                val count = getLessonsCountForDay(dayIdx)
                val isSelected = dayIdx == selectedDayIndex

                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                    else if (visualStyle == VisualStyle.LIQUID_GLASS) Color.White.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "dayChipBg"
                )
                val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface

                val parStr = formatPairsCount(count)

                Surface(
                    color = bgColor,
                    contentColor = contentColor,
                    shape = RoundedCornerShape(14.dp),
                    border = if (visualStyle == VisualStyle.LIQUID_GLASS && !isSelected)
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectDay(dayIdx) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text(
                            text = shortNames[dayIdx],
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = date.format(dateFmt),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = parStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Если панель ВВЕРХУ — кнопки Числитель / Знаменатель остаются под днями
        if (!isBottom) {
            Spacer(modifier = Modifier.height(8.dp))
            WeekParitySelectorRow(selectedWeek = selectedWeek, onSelectWeek = onSelectWeek)
        }
    }
}

@Composable
fun WeekParitySelectorRow(selectedWeek: Int, onSelectWeek: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WeekTogglePill(
            title = "Числитель",
            selected = selectedWeek == 1,
            indicatorColor = Color(0xFF4FC3F7),
            onClick = { onSelectWeek(1) },
            modifier = Modifier.weight(1f)
        )
        WeekTogglePill(
            title = "Знаменатель",
            selected = selectedWeek == 2,
            indicatorColor = Color(0xFFFFB74D),
            onClick = { onSelectWeek(2) },
            modifier = Modifier.weight(1f)
        )
    }
}

fun formatPairsCount(count: Int): String {
    return when {
        count % 10 == 1 && count % 100 != 11 -> "$count пара"
        count % 10 in 2..4 && (count % 100 < 10 || count % 100 >= 20) -> "$count пары"
        else -> "$count пар"
    }
}

@Composable
fun WeekTogglePill(
    title: String,
    selected: Boolean,
    indicatorColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        animationSpec = tween(220),
        label = "weekPill"
    )
    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

// --- СПИСОК ПАР И КАРТОЧКИ С АНИМАЦИЕЙ ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayScheduleList(
    lessons: List<LessonItem>,
    notes: List<NoteItem>,
    mySubgroup: Int,
    visualStyle: VisualStyle,
    onDeleteCustomLesson: (String) -> Unit,
    onNoteClick: (NoteItem) -> Unit
) {
    if (lessons.isEmpty() && notes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "В этот день пар и заметок нет 🎉",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(lessons, key = { it.id }) { lesson ->
            Box(modifier = Modifier.animateItem()) {
                LessonCardV2(
                    lesson = lesson,
                    mySubgroup = mySubgroup,
                    visualStyle = visualStyle,
                    onDeleteCustom = { onDeleteCustomLesson(lesson.id) }
                )
            }
        }

        items(notes, key = { it.id }) { note ->
            Box(modifier = Modifier.animateItem()) {
                NoteCardItem(
                    note = note,
                    visualStyle = visualStyle,
                    onClick = { onNoteClick(note) }
                )
            }
        }
    }
}

@Composable
fun LessonCardV2(
    lesson: LessonItem,
    mySubgroup: Int,
    visualStyle: VisualStyle,
    onDeleteCustom: () -> Unit
) {
    val isOtherSubgroup = lesson.subgroup != null && lesson.subgroup != mySubgroup
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(22.dp)
    val cardAlpha by animateFloatAsState(if (isOtherSubgroup) 0.62f else 1f, label = "cardAlpha")

    val glassModifier = if (visualStyle == VisualStyle.LIQUID_GLASS) {
        val topAlpha = if (isDark) 0.24f else 0.72f
        val botAlpha = if (isDark) 0.08f else 0.38f
        Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = topAlpha),
                        Color.White.copy(alpha = botAlpha),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.75f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.45f)
                    )
                ),
                shape = shape
            )
    } else null

    if (glassModifier != null) {
        Box(modifier = glassModifier) {
            LessonCardBody(lesson, isOtherSubgroup, onDeleteCustom)
        }
    } else {
        val cardColors = when {
            isOtherSubgroup -> CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
            )
            else -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }
        val cardBorder = when {
            lesson.isCustom -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            isOtherSubgroup -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            else -> null
        }

        Card(
            shape = shape,
            colors = cardColors,
            border = cardBorder,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(cardAlpha)
        ) {
            LessonCardBody(lesson, isOtherSubgroup, onDeleteCustom)
        }
    }
}

@Composable
fun LessonCardBody(
    lesson: LessonItem,
    isOtherSubgroup: Boolean,
    onDeleteCustom: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = lesson.time,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lesson.type.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = lesson.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (lesson.subgroup != null) {
                    Surface(
                        color = if (isOtherSubgroup) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Подгр. ${lesson.subgroup}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isOtherSubgroup) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Чистое название дисциплины
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = lesson.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (lesson.isCustom) {
                IconButton(
                    onClick = onDeleteCustom,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Строкой ниже после названия предмета — имя преподавателя
        if (lesson.teacher.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = lesson.teacher,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Аудитория
        if (lesson.room.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = lesson.room,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun NoteCardItem(
    note: NoteItem,
    visualStyle: VisualStyle,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (visualStyle == VisualStyle.LIQUID_GLASS)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.secondaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Заметка",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// --- КАЛЕНДАРЬ (ВСЕ 6 НЕДЕЛЬ МЕСЯЦА ПОМЕЩАЮТСЯ ЦЕЛИКОМ) ---

@Composable
fun GoogleStyleCalendarContent(
    today: LocalDate,
    getLessonsCountForDate: (LocalDate) -> Int,
    hasNoteForDate: (LocalDate) -> Boolean,
    onSelectDate: (LocalDate) -> Unit
) {
    val maxDate = remember(today) { today.plusMonths(2) }
    val monthsList = remember(today) {
        listOf(
            YearMonth.from(today),
            YearMonth.from(today.plusMonths(1)),
            YearMonth.from(today.plusMonths(2))
        ).distinct()
    }
    var selectedMonthIdx by remember { mutableIntStateOf(0) }
    val currentMonth = monthsList[selectedMonthIdx]

    val numeratorColor = Color(0xFF4FC3F7)
    val denominatorColor = Color(0xFFFFB74D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(numeratorColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Числитель", style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(denominatorColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Знаменатель", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (selectedMonthIdx > 0) selectedMonthIdx-- },
                enabled = selectedMonthIdx > 0
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Пред. месяц")
            }

            val monthTitle = currentMonth.atDay(1)
                .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
                .replaceFirstChar { it.titlecase(Locale("ru")) }

            Text(
                text = monthTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = { if (selectedMonthIdx < monthsList.lastIndex) selectedMonthIdx++ },
                enabled = selectedMonthIdx < monthsList.lastIndex
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "След. месяц")
            }
        }

        val daysHeader = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        Row(modifier = Modifier.fillMaxWidth()) {
            daysHeader.forEach { d ->
                Text(
                    text = d,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp)
                )
            }
        }

        val firstDayOfMonth = currentMonth.atDay(1)
        val emptyLeadingCells = firstDayOfMonth.dayOfWeek.value - 1
        val daysInMonth = currentMonth.lengthOfMonth()
        val totalCells = emptyLeadingCells + daysInMonth

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(380.dp)
        ) {
            items(totalCells) { cellIdx ->
                if (cellIdx < emptyLeadingCells) {
                    Box(modifier = Modifier.size(46.dp))
                } else {
                    val dayNum = cellIdx - emptyLeadingCells + 1
                    val cellDate = currentMonth.atDay(dayNum)
                    val isSelectable = !cellDate.isAfter(maxDate)
                    val isToday = cellDate == today
                    val parity = getWeekParityForDate(cellDate)
                    val lessonsCount = if (cellDate.dayOfWeek.value <= 5) getLessonsCountForDate(cellDate) else 0
                    val hasNote = hasNoteForDate(cellDate)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isToday) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isSelectable) 1f else 0.35f),
                        border = if (isToday) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .height(54.dp)
                            .clickable(enabled = isSelectable) { onSelectDate(cellDate) }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 4.dp, bottom = 3.dp, start = 2.dp, end = 2.dp)
                        ) {
                            Text(
                                text = "$dayNum",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (isSelectable) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )

                            if (isSelectable && (lessonsCount > 0 || hasNote)) {
                                val shortPar = formatPairsCount(lessonsCount)
                                Text(
                                    text = if (hasNote) "$shortPar •" else shortPar,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            } else {
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (parity == 1) numeratorColor else denominatorColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- ЭКРАН ПРОСМОТРА ДНЯ ИЗ КАЛЕНДАРЯ ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDateDetailScreen(
    date: LocalDate,
    settings: AppSettings,
    lessons: List<LessonItem>,
    notes: List<NoteItem>,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    onEditNote: (NoteItem) -> Unit,
    onAddCustomLesson: () -> Unit,
    onDeleteCustomLesson: (String) -> Unit,
    customBgBitmap: Bitmap?
) {
    val parity = getWeekParityForDate(date)
    val dateTitle = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")))
        .replaceFirstChar { it.titlecase(Locale("ru")) }

    Box(modifier = Modifier.fillMaxSize()) {
        BackgroundLayer(settings.visualStyle, customBgBitmap, settings.bgBlurRadius)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    title = {
                        Column {
                            Text(dateTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (parity == 1) "Числитель" else "Знаменатель",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = onAddNote,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                        text = { Text("Заметка") }
                    )
                    ExtendedFloatingActionButton(
                        onClick = onAddCustomLesson,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Пара") }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                DayScheduleList(
                    lessons = lessons,
                    notes = notes,
                    mySubgroup = settings.mySubgroup,
                    visualStyle = settings.visualStyle,
                    onDeleteCustomLesson = onDeleteCustomLesson,
                    onNoteClick = onEditNote
                )
            }
        }
    }
}

// --- ЭКРАН НАСТРОЕК (БЕЗ HEX-КОДОВ, БЕЗ "ПО УМОЛЧАНИЮ", С ПОЛЗУНКОМ РАЗМЫТИЯ ФОНА) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    highlightSourceTrigger: Int,
    onBack: () -> Unit,
    onShowTutorial: () -> Unit,
    onImportTableFile: (Uri) -> Unit,
    onCustomBgPicked: (Uri) -> Unit,
    onClearCustomBg: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var isSourceHighlighted by remember { mutableStateOf(false) }
    var urlInput by remember(settings.scheduleUrl) { mutableStateOf(settings.scheduleUrl) }

    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onCustomBgPicked(uri)
    }

    val tableFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onImportTableFile(uri)
    }

    LaunchedEffect(highlightSourceTrigger) {
        if (highlightSourceTrigger > 0) {
            listState.animateScrollToItem(0)
            repeat(2) {
                isSourceHighlighted = true
                delay(320)
                isSourceHighlighted = false
                delay(260)
            }
        }
    }

    val sourceBlockBg by animateColorAsState(
        targetValue = if (isSourceHighlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(250),
        label = "sourceBlink"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Настройки", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ИСТОЧНИК РАСПИСАНИЯ
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = sourceBlockBg),
                    border = if (isSourceHighlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Источник расписания",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.source == ScheduleSource.LINK,
                                onClick = { onUpdateSettings(settings.copy(source = ScheduleSource.LINK)) },
                                label = { Text("Ссылка (Сайт вуза)") }
                            )
                            FilterChip(
                                selected = settings.source == ScheduleSource.TABLE,
                                onClick = { onUpdateSettings(settings.copy(source = ScheduleSource.TABLE)) },
                                label = { Text("Таблица (.xlsx)") }
                            )
                        }

                        if (settings.source == ScheduleSource.LINK) {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = {
                                    urlInput = it
                                    onUpdateSettings(settings.copy(scheduleUrl = it))
                                },
                                label = { Text("Ссылка на расписание ИрГУПС") },
                                singleLine = true,
                                trailingIcon = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        if (urlInput.isNotEmpty()) {
                                            IconButton(onClick = {
                                                urlInput = ""
                                                onUpdateSettings(settings.copy(scheduleUrl = ""))
                                            }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        IconButton(onClick = {
                                            val clip = clipboardManager.primaryClip
                                            if (clip != null && clip.itemCount > 0) {
                                                val pasted = clip.getItemAt(0).text?.toString() ?: ""
                                                if (pasted.isNotBlank()) {
                                                    urlInput = pasted
                                                    onUpdateSettings(settings.copy(scheduleUrl = pasted))
                                                }
                                            }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = "Вставить", modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = {
                                            if (urlInput.isNotBlank()) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlInput))
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Открыть", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Button(
                                onClick = { tableFileLauncher.launch("*/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Выбрать таблицу (.xlsx / .csv)")
                            }
                        }
                    }
                }
            }

            // КАСТОМИЗАЦИЯ
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Кастомизация",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text("Стиль оформления", style = MaterialTheme.typography.labelLarge)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StyleRadioOption(
                                title = "Material 3 (Цвета системы)",
                                selected = settings.visualStyle == VisualStyle.MATERIAL_SYSTEM,
                                onClick = { onUpdateSettings(settings.copy(visualStyle = VisualStyle.MATERIAL_SYSTEM)) }
                            )
                            StyleRadioOption(
                                title = "Material 3 (Монохром Ч/Б)",
                                selected = settings.visualStyle == VisualStyle.MONOCHROME,
                                onClick = { onUpdateSettings(settings.copy(visualStyle = VisualStyle.MONOCHROME)) }
                            )
                            StyleRadioOption(
                                title = "Liquid Glass (Apple Style)",
                                selected = settings.visualStyle == VisualStyle.LIQUID_GLASS,
                                onClick = { onUpdateSettings(settings.copy(visualStyle = VisualStyle.LIQUID_GLASS)) }
                            )
                        }

                        HorizontalDivider()

                        Text("Цветовая контрастность акцентов", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = settings.contrastLevel == ContrastLevel.LOW,
                                onClick = { onUpdateSettings(settings.copy(contrastLevel = ContrastLevel.LOW)) },
                                label = { Text("Меньше", maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.contrastLevel == ContrastLevel.NORMAL,
                                onClick = { onUpdateSettings(settings.copy(contrastLevel = ContrastLevel.NORMAL)) },
                                label = { Text("Стандарт", maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.contrastLevel == ContrastLevel.HIGH,
                                onClick = { onUpdateSettings(settings.copy(contrastLevel = ContrastLevel.HIGH)) },
                                label = { Text("Высокая", maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider()

                        Text("Тема приложения", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.themeMode == ThemeMode.SYSTEM,
                                onClick = { onUpdateSettings(settings.copy(themeMode = ThemeMode.SYSTEM)) },
                                label = { Text("Системная") }
                            )
                            FilterChip(
                                selected = settings.themeMode == ThemeMode.LIGHT,
                                onClick = { onUpdateSettings(settings.copy(themeMode = ThemeMode.LIGHT)) },
                                label = { Text("Светлая") }
                            )
                            FilterChip(
                                selected = settings.themeMode == ThemeMode.DARK,
                                onClick = { onUpdateSettings(settings.copy(themeMode = ThemeMode.DARK)) },
                                label = { Text("Темная") }
                            )
                        }

                        // Вариант темной темы без hex-кодов
                        Text("Вариант темной темы", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.darkVariant == DarkVariant.STANDARD_141414,
                                onClick = { onUpdateSettings(settings.copy(darkVariant = DarkVariant.STANDARD_141414)) },
                                label = { Text("Стандартная") }
                            )
                            FilterChip(
                                selected = settings.darkVariant == DarkVariant.AMOLED_BLACK,
                                onClick = { onUpdateSettings(settings.copy(darkVariant = DarkVariant.AMOLED_BLACK)) },
                                label = { Text("AMOLED") }
                            )
                        }

                        HorizontalDivider()

                        // Положение дней недели без надписи "(по умолчанию)" и без упоминаний свайпа
                        Text("Положение дней недели и недель", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.barPosition == BarPosition.BOTTOM,
                                onClick = { onUpdateSettings(settings.copy(barPosition = BarPosition.BOTTOM)) },
                                label = { Text("Внизу") }
                            )
                            FilterChip(
                                selected = settings.barPosition == BarPosition.TOP,
                                onClick = { onUpdateSettings(settings.copy(barPosition = BarPosition.TOP)) },
                                label = { Text("Вверху") }
                            )
                        }

                        HorizontalDivider()

                        Text("Фон главного экрана", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                                Text("Стандартные (WIP)")
                            }
                            Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                                Text("Из галереи")
                            }
                        }

                        // Ползунок размытия для кастомного фона
                        if (settings.hasCustomBg) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Размытие фона", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${settings.bgBlurRadius.toInt()} dp",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.bgBlurRadius,
                                onValueChange = { onUpdateSettings(settings.copy(bgBlurRadius = it)) },
                                valueRange = 0f..25f
                            )
                            TextButton(onClick = onClearCustomBg) {
                                Text("Сбросить установленный фон")
                            }
                        }

                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Выбор иконки приложения (WIP)")
                        }
                    }
                }
            }

            // ДОСТУПНОСТЬ
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Доступность",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Размер шрифта", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (settings.fontScale == 1.0f) "Как в системе (100%)"
                                else "${(settings.fontScale * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.fontScale,
                            onValueChange = {
                                val snapped = kotlin.math.round(it * 20f) / 20f
                                onUpdateSettings(settings.copy(fontScale = snapped))
                            },
                            valueRange = 0.85f..1.30f
                        )
                    }
                }
            }

            // ПОМОЩЬ И СВЯЗЬ
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Помощь и обратная связь",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FilledTonalButton(onClick = onShowTutorial, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.School, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Показать обучение по приложению")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:developer@example.com?subject=Предложение по Расписанию ИрГУПС")
                                }
                                try { context.startActivity(intent) } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Email, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Связь с разработчиком")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StyleRadioOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- ОБУЧАЛКА (БЕЗ УПОМИНАНИЙ ВЕРТИКАЛЬНОГО СВАЙПА) ---

@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    val steps = listOf(
        "Календарь на 2 месяца вперёд" to "Нажми на кнопку «Календарь» на панели дней, чтобы открыть расписание на 2 месяца вперёд. В нём цветной полоской отмечены Числитель и Знаменатель, а также видно количество пар в каждый день!",
        "Заметки и экспорт в Google Keep" to "Нажми на любой день в календаре, чтобы добавить к нему Заметку. При нажатии на заметку её можно отредактировать или экспортировать в Google Keep / заметки телефона.",
        "Свои пары и подгруппы 1 / 2" to "В правом верхнем углу переключается твоя подгруппа (1 или 2). Лабораторные другой подгруппы приглушаются. Кнопка «+ Своя пара» добавляет личные занятия.",
        "Liquid Glass, AMOLED и размытие фона" to "В настройках можно включить стиль Apple Liquid Glass, чистый чёрный AMOLED-режим и установить свой фон из галереи с настраиваемым ползунком размытия."
    )
    var stepIdx by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "${stepIdx + 1}/${steps.size}. ${steps[stepIdx].first}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        text = { Text(text = steps[stepIdx].second, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = { if (stepIdx < steps.lastIndex) stepIdx++ else onDismiss() }) {
                Text(if (stepIdx < steps.lastIndex) "Далее" else "Понятно!")
            }
        },
        dismissButton = {
            if (stepIdx > 0) {
                TextButton(onClick = { stepIdx-- }) { Text("Назад") }
            } else {
                TextButton(onClick = onDismiss) { Text("Пропустить") }
            }
        }
    )
}

@Composable
fun NoteEditDialog(
    initialText: String,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onExport: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новая заметка" else "Редактирование заметки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст заметки") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.isNotBlank()) {
                    FilledTonalButton(onClick = { onExport(text) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Экспорт в Google Keep / Заметки")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

fun shareNoteToKeepOrExternal(context: Context, dateIso: String, noteText: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Заметка к расписанию ($dateIso)")
        putExtra(Intent.EXTRA_TEXT, "Заметка ($dateIso):\n$noteText")
    }
    context.startActivity(Intent.createChooser(intent, "Экспортировать заметку в..."))
}

@Composable
fun AddCustomLessonDialog(
    onDismiss: () -> Unit,
    onAdd: (time: String, title: String, type: String, room: String, teacher: String, subgroup: Int?) -> Unit
) {
    var time by remember { mutableStateOf("10:10 - 11:40") }
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Лекция") }
    var room by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var subgroupChoice by remember { mutableIntStateOf(0) }

    val timePresets = listOf("08:30 - 10:00", "10:10 - 11:40", "12:10 - 13:40", "13:50 - 15:20")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить свою пару") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Предмет *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(timePresets) { preset ->
                        AssistChip(onClick = { time = preset }, label = { Text(preset.substringBefore(" ")) })
                    }
                }
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Время") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = room,
                        onValueChange = { room = it },
                        label = { Text("Аудитория") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Тип (Лекция/Семинар...)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("Преподаватель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = subgroupChoice == 0, onClick = { subgroupChoice = 0 }, label = { Text("Общая") })
                    FilterChip(selected = subgroupChoice == 1, onClick = { subgroupChoice = 1 }, label = { Text("Подгр. 1") })
                    FilterChip(selected = subgroupChoice == 2, onClick = { subgroupChoice = 2 }, label = { Text("Подгр. 2") })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onAdd(time, title.trim(), type.trim(), room.trim(), teacher.trim(), if (subgroupChoice == 0) null else subgroupChoice)
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun BackgroundLayer(visualStyle: VisualStyle, customBgBitmap: Bitmap?, blurRadius: Float) {
    if (customBgBitmap != null) {
        val blurMod = if (blurRadius > 0.5f) Modifier.blur(blurRadius.dp) else Modifier
        Image(
            bitmap = customBgBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(blurMod)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
        )
    } else if (visualStyle == VisualStyle.LIQUID_GLASS) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )
    }
}

// --- ТОЧНЫЕ ПАРСЕРЫ САЙТА ИРГУПС (VIEW=TABLE И VIEW=LIST) И ФАЙЛА XLSX ---

// 16.11.2026 — Знаменатель (2), 23.11.2026 — Числитель (1)
fun getWeekParityForDate(date: LocalDate): Int {
    val sept1 = LocalDate.of(if (date.monthValue >= 9) date.year else date.year - 1, 9, 1)
    val firstMonday = sept1.minusDays((sept1.dayOfWeek.value - 1).toLong())
    val weeksBetween = ChronoUnit.WEEKS.between(firstMonday, date).toInt()
    // В ИрГУПС неделя с 31.08/01.09 = Знаменатель (или смещение делает 16.11 Знаменателем, а 23.11 Числителем)
    return if (weeksBetween % 2 == 0) 2 else 1
}

fun getLessonsForSpecificDate(
    date: LocalDate,
    week1: List<LessonItem>,
    week2: List<LessonItem>,
    custom: List<LessonItem>
): List<LessonItem> {
    val dayIdx = date.dayOfWeek.value - 1
    if (dayIdx !in 0..4) {
        return custom.filter { it.specificDateIso == date.toString() }.sortedBy { it.time }
    }
    val parity = getWeekParityForDate(date)
    val baseWeek = if (parity == 1) week1 else week2
    val regularForDay = baseWeek.filter { it.dayOfWeekIndex == dayIdx }
    val customForDay = custom.filter {
        it.specificDateIso == date.toString() || (it.specificDateIso == null && it.dayOfWeekIndex == dayIdx)
    }
    return (regularForDay + customForDay).sortedBy { it.time }
}

// Очистка строки дисциплины, извлечение ФИО преподавателя (3 слова или И.О.), типа пары и подгруппы лабы
fun cleanAndParseLessonBlock(
    rawBlock: String,
    explicitTeacher: String = "",
    explicitRoom: String = "",
    explicitType: String = "",
    explicitGroup: String = "",
    dayIdx: Int = 0,
    timeStr: String = "08:30 - 10:00",
    weekParity: Int = 0
): LessonItem? {
    val text = rawBlock.replace("\u00A0", " ").trim()
    if (text.length < 3 || Regex("""^\d{1,2}:\d{2}\s*[-–—]\s*\d{1,2}:\d{2}$""").matches(text)) return null

    // Определяем тип пары
    val detectedType = when {
        explicitType.contains("лаб", true) || text.contains("лабораторн", true) -> "Лабораторная"
        explicitType.contains("практ", true) || explicitType.contains("семин", true) ||
                text.contains("практическ", true) || text.contains("семинар", true) -> "Семинар"
        explicitType.contains("лекц", true) || text.contains("лекция", true) -> "Лекция"
        else -> "Лекция"
    }

    // Определяем группу (например, СОД.2-23-1(И,О)-2)
    val groupRegex = Regex("""[А-ЯЁA-Z]{2,6}\.\d-\d+-\d+\([^)]+\)(?:-[12])?""")
    val groupFound = explicitGroup.ifBlank { groupRegex.find(text)?.value ?: "" }

    // Подгруппа действует ТОЛЬКО для лабораторных работ (-1 или -2 после скобки группы)
    val subgroup = if (detectedType == "Лабораторная") {
        Regex("""\)\s*-([12])\b""").find(groupFound)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\)\s*-([12])\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    } else null

    // Аудитория (например, А-212, Б-206, Д-820)
    val roomRegex = Regex("""\b[А-ЯЁA-Z]-\d{3,4}[а-яa-z]?\b""")
    val roomFound = explicitRoom.ifBlank { roomRegex.find(text)?.value ?: "" }

    // Преподаватель: полное ФИО (Фамилия Имя Отчество) или Фамилия И.О.
    val fullFioRegex = Regex("""[А-ЯЁ][а-яё]{2,}\s+[А-ЯЁ][а-яё]{2,}\s+[А-ЯЁ][а-яё]{2,}""")
    val shortFioRegex = Regex("""[А-ЯЁ][а-яё]{2,}\s+[А-ЯЁ]\.\s*[А-ЯЁ]\.""")
    val teacherFound = explicitTeacher.ifBlank {
        fullFioRegex.find(text)?.value ?: shortFioRegex.find(text)?.value ?: ""
    }

    // Очищаем название предмета от всего лишнего (включая "Практические (семинарские) занятия)")
    var cleanTitle = text
        .replace(Regex("""(?i)\b(числитель|знаменатель)\b"""), "")
        .replace(Regex("""(?i)\(\s*практические\s*\(\s*семинарские\s*\)\s*занятия\s*\)"""), "")
        .replace(Regex("""(?i)практические\s*\(\s*семинарские\s*\)\s*занятия\s*\)?"""), "")
        .replace(Regex("""(?i)\(\s*(лекция|лабораторная\s*работа|практика|семинар)\s*\)"""), "")
        .replace(Regex("""(?i)\bзанятия\)"""), "")
        .replace(groupRegex, "")
        .replace(roomRegex, "")

    if (teacherFound.isNotBlank()) {
        cleanTitle = cleanTitle.replace(teacherFound, "")
    }

    cleanTitle = cleanTitle
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ',', ';', '-', '(', ')')

    if (cleanTitle.isBlank()) return null

    return LessonItem(
        dayOfWeekIndex = dayIdx.coerceIn(0, 4),
        weekParity = weekParity,
        time = timeStr,
        title = cleanTitle,
        type = detectedType,
        teacher = teacherFound.trim(),
        room = roomFound.trim(),
        subgroup = subgroup
    )
}

// Загрузка расписания по ссылке (поддерживает и view=table, и view=list, и разделяет Числитель / Знаменатель)
suspend fun fetchScheduleFromUrlBothWeeks(url: String): Pair<List<LessonItem>, List<LessonItem>> = withContext(Dispatchers.IO) {
    val cleanUrl = url.trim()
    val doc = Jsoup.connect(cleanUrl)
        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
        .timeout(12000)
        .get()

    val isListView = cleanUrl.contains("view=list", ignoreCase = true)
    val parsed = if (isListView) parseHtmlListView(doc) else parseHtmlTableView(doc)

    // Если в другом режиме пусто — пробуем альтернативный парсер на том же документе
    val allItems = if (parsed.isEmpty()) {
        if (isListView) parseHtmlTableView(doc) else parseHtmlListView(doc)
    } else parsed

    val week1 = allItems.filter { it.weekParity == 0 || it.weekParity == 1 }
    val week2 = allItems.filter { it.weekParity == 0 || it.weekParity == 2 }
    Pair(week1, week2)
}

// Разбор табличного вида (view=table): колонка 0 = Время, колонки 1..5 = ПН..ПТ
fun parseHtmlTableView(doc: Document): List<LessonItem> {
    val result = mutableListOf<LessonItem>()
    val timeRegex = Regex("""(\d{1,2}:\d{2})\s*[-–—]\s*(\d{1,2}:\d{2})""")

    for (tr in doc.select("tr")) {
        val cells = tr.select("td")
        if (cells.size < 2) continue

        val firstCellText = cells[0].text().trim()
        val timeMatch = timeRegex.find(firstCellText) ?: continue
        val timeStr = "${timeMatch.groupValues[1]} - ${timeMatch.groupValues[2]}"

        // Ячейки с индексами 1..5 соответствуют ПН (0) .. ПТ (4)
        val maxCol = minOf(cells.size - 1, 5)
        for (colIdx in 1..maxCol) {
            val dayIdx = colIdx - 1
            val cell = cells[colIdx]
            val rawCellText = cell.text().replace("\u00A0", " ").trim()
            if (rawCellText.length < 4) continue

            val lower = rawCellText.lowercase(Locale("ru"))
            val numIdx = lower.indexOf("числитель")
            val denIdx = lower.indexOf("знаменатель")

            when {
                numIdx != -1 && denIdx != -1 -> {
                    val firstIsNum = numIdx < denIdx
                    val part1 = if (firstIsNum) rawCellText.substring(numIdx, denIdx) else rawCellText.substring(denIdx, numIdx)
                    val part2 = if (firstIsNum) rawCellText.substring(denIdx) else rawCellText.substring(numIdx)
                    cleanAndParseLessonBlock(part1, dayIdx = dayIdx, timeStr = timeStr, weekParity = if (firstIsNum) 1 else 2)?.let { result.add(it) }
                    cleanAndParseLessonBlock(part2, dayIdx = dayIdx, timeStr = timeStr, weekParity = if (firstIsNum) 2 else 1)?.let { result.add(it) }
                }
                numIdx != -1 -> {
                    cleanAndParseLessonBlock(rawCellText, dayIdx = dayIdx, timeStr = timeStr, weekParity = 1)?.let { result.add(it) }
                }
                denIdx != -1 -> {
                    cleanAndParseLessonBlock(rawCellText, dayIdx = dayIdx, timeStr = timeStr, weekParity = 2)?.let { result.add(it) }
                }
                else -> {
                    // Общая пара для обеих недель
                    cleanAndParseLessonBlock(rawCellText, dayIdx = dayIdx, timeStr = timeStr, weekParity = 0)?.let { result.add(it) }
                }
            }
        }
    }
    return result
}

// Разбор списочного вида (view=list)
fun parseHtmlListView(doc: Document): List<LessonItem> {
    val result = mutableListOf<LessonItem>()
    val dateRegex = Regex("""(\d{2}\.\d{2}\.\d{4})""")
    val timeRegex = Regex("""(\d{1,2}:\d{2})\s*[-–—]\s*(\d{1,2}:\d{2})""")
    val dayNames = listOf("понедельник", "вторник", "среда", "четверг", "пятница")

    var currentDayIdx = 0
    var currentParity = 0

    for (el in doc.select("h1, h2, h3, h4, th, tr, div.day, p")) {
        val text = el.text().replace("\u00A0", " ").trim()

        // Проверяем, не заголовок ли это с датой (например, 16.11.2026) или днем недели
        if (el.tagName() != "tr") {
            val dMatch = dateRegex.find(text)
            if (dMatch != null) {
                val d = try { LocalDate.parse(dMatch.groupValues[1], DateTimeFormatter.ofPattern("dd.MM.yyyy")) } catch (_: Exception) { null }
                if (d != null) {
                    currentDayIdx = (d.dayOfWeek.value - 1).coerceIn(0, 4)
                    currentParity = getWeekParityForDate(d)
                }
            } else {
                val foundDay = dayNames.indexOfFirst { text.lowercase(Locale("ru")).contains(it) }
                if (foundDay != -1 && text.length < 45) currentDayIdx = foundDay
            }
            continue
        }

        // Если это строка таблицы <tr>
        val tMatch = timeRegex.find(text) ?: continue
        val timeStr = "${tMatch.groupValues[1]} - ${tMatch.groupValues[2]}"

        // Проверяем, нет ли даты прямо в первом столбце строки
        val rowDateMatch = dateRegex.find(text)
        if (rowDateMatch != null) {
            val d = try { LocalDate.parse(rowDateMatch.groupValues[1], DateTimeFormatter.ofPattern("dd.MM.yyyy")) } catch (_: Exception) { null }
            if (d != null) {
                currentDayIdx = (d.dayOfWeek.value - 1).coerceIn(0, 4)
                currentParity = getWeekParityForDate(d)
            }
        }

        val parity = when {
            text.contains("числитель", true) -> 1
            text.contains("знаменатель", true) -> 2
            else -> currentParity
        }

        val rowWithoutTimeAndDate = text
            .replace(tMatch.value, "")
            .replace(dateRegex, "")
            .trim()

        cleanAndParseLessonBlock(
            rawBlock = rowWithoutTimeAndDate,
            dayIdx = currentDayIdx,
            timeStr = timeStr,
            weekParity = parity
        )?.let { result.add(it) }
    }
    return result
}

// Надежный распаковщик .xlsx (без закрытия ZipInputStream и с XmlPullParser)
fun parseExcelTableFileBothWeeks(context: Context, uri: Uri): Triple<List<LessonItem>, List<LessonItem>, Int> {
    val allLessons = mutableListOf<LessonItem>()
    try {
        var sharedStringsBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val zis = ZipInputStream(inputStream)
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase(Locale.ROOT)
                if (name.endsWith("sharedstrings.xml")) {
                    sharedStringsBytes = zis.readBytes()
                } else if (name.endsWith("sheet1.xml") || (sheetBytes == null && name.contains("worksheets/sheet"))) {
                    sheetBytes = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = mutableListOf<String>()
        sharedStringsBytes?.let { bytes ->
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType
            var inSi = false
            val currentSiText = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (parser.name == "si") {
                        inSi = true
                        currentSiText.clear()
                    } else if (inSi && parser.name == "t") {
                        currentSiText.append(parser.nextText())
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "si") {
                    inSi = false
                    sharedStrings.add(currentSiText.toString())
                }
                event = parser.next()
            }
        }

        sheetBytes?.let { bytes ->
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType

            var currentRowNum = 0
            var currentColLetter = ""
            var currentCellType = ""
            val rowCells = mutableMapOf<String, String>()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "row" -> {
                                currentRowNum = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (currentRowNum + 1)
                                rowCells.clear()
                            }
                            "c" -> {
                                val ref = parser.getAttributeValue(null, "r") ?: ""
                                currentColLetter = ref.takeWhile { it.isLetter() }
                                currentCellType = parser.getAttributeValue(null, "t") ?: ""
                            }
                            "v", "t" -> {
                                if (currentColLetter.isNotEmpty()) {
                                    val rawVal = parser.nextText()
                                    val resolved = if (currentCellType == "s") {
                                        val idx = rawVal.toIntOrNull()
                                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawVal
                                    } else rawVal
                                    rowCells[currentColLetter] = resolved.trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "row" && currentRowNum > 1 && rowCells.isNotEmpty()) {
                            // Столбцы файла raspisanie.xlsx: A=Дата, B=Время, C=Дисциплина, D=Тип, E=Преподаватель, F=Группа, G=Аудитория
                            val dateStr = rowCells["A"] ?: ""
                            val timeStr = rowCells["B"] ?: "08:30 - 10:00"
                            val subjStr = rowCells["C"] ?: ""
                            val typeStr = rowCells["D"] ?: "Лекция"
                            val teachStr = rowCells["E"] ?: ""
                            val groupStr = rowCells["F"] ?: ""
                            val roomStr = rowCells["G"] ?: ""

                            if (subjStr.isNotBlank()) {
                                val parsedDate = try {
                                    LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                } catch (_: Exception) { null }

                                val dayIdx = parsedDate?.let { (it.dayOfWeek.value - 1).coerceIn(0, 4) } ?: 0
                                val parity = parsedDate?.let { getWeekParityForDate(it) } ?: 0

                                cleanAndParseLessonBlock(
                                    rawBlock = subjStr,
                                    explicitTeacher = teachStr,
                                    explicitRoom = roomStr,
                                    explicitType = typeStr,
                                    explicitGroup = groupStr,
                                    dayIdx = dayIdx,
                                    timeStr = timeStr,
                                    weekParity = parity
                                )?.let { allLessons.add(it) }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
    } catch (_: Exception) {}

    val week1 = allLessons.filter { it.weekParity == 0 || it.weekParity == 1 }
    val week2 = allLessons.filter { it.weekParity == 0 || it.weekParity == 2 }
    return Triple(week1, week2, allLessons.size)
}

// Дефолтное расписание для проверки работоспособности
fun generateDefaultWeekLessons(week: Int): List<LessonItem> {
    return if (week == 2) {
        // Знаменатель (16.11 - 20.11)
        listOf(
            LessonItem(dayOfWeekIndex = 0, weekParity = 2, time = "10:10 - 11:40", title = "Основы теории надежности", type = "Лекция", teacher = "Асташков Николай Павлович", room = "Б-206", subgroup = null),
            LessonItem(dayOfWeekIndex = 0, weekParity = 2, time = "12:10 - 13:40", title = "Основы научных исследований", type = "Лекция", teacher = "Федоров Марк Эдуардович", room = "Б-206", subgroup = null),
            LessonItem(dayOfWeekIndex = 0, weekParity = 2, time = "13:50 - 15:20", title = "Станционные системы автоматики и телемеханики", type = "Лабораторная", teacher = "Скоробогатов Максим Эдуардович", room = "А-212", subgroup = 2),
            LessonItem(dayOfWeekIndex = 1, weekParity = 2, time = "08:30 - 10:00", title = "Станционные системы автоматики и телемеханики", type = "Лекция", teacher = "Скоробогатов Максим Эдуардович", room = "А-207", subgroup = null),
            LessonItem(dayOfWeekIndex = 1, weekParity = 2, time = "10:10 - 11:40", title = "Станционные системы автоматики и телемеханики", type = "Семинар", teacher = "Скоробогатов Максим Эдуардович", room = "Б-316", subgroup = null),
            LessonItem(dayOfWeekIndex = 2, weekParity = 2, time = "12:10 - 13:40", title = "Специальные измерения и рельсовые цепи", type = "Лекция", teacher = "Скоробогатов Максим Эдуардович", room = "А-207", subgroup = null),
            LessonItem(dayOfWeekIndex = 3, weekParity = 2, time = "10:10 - 11:40", title = "Инженерная экология", type = "Семинар", teacher = "Федорова Наталья Владимировна", room = "Д-311", subgroup = null),
            LessonItem(dayOfWeekIndex = 4, weekParity = 2, time = "10:10 - 11:40", title = "Системы контроля параметров подвижного состава", type = "Лекция", teacher = "Алексеенко Владимир Александрович", room = "Д-820", subgroup = null)
        )
    } else {
        // Числитель (23.11 - 27.11)
        listOf(
            LessonItem(dayOfWeekIndex = 0, weekParity = 1, time = "10:10 - 11:40", title = "Основы теории надежности", type = "Лекция", teacher = "Асташков Николай Павлович", room = "Б-206", subgroup = null),
            LessonItem(dayOfWeekIndex = 0, weekParity = 1, time = "12:10 - 13:40", title = "Инженерная экология", type = "Лекция", teacher = "Федорова Наталья Владимировна", room = "Г-313", subgroup = null),
            LessonItem(dayOfWeekIndex = 1, weekParity = 1, time = "08:30 - 10:00", title = "Микропроцессорные информационно-управляющие системы", type = "Лабораторная", teacher = "Миронов Борис Михайлович", room = "А-212", subgroup = 2),
            LessonItem(dayOfWeekIndex = 1, weekParity = 1, time = "10:10 - 11:40", title = "Основы технической диагностики", type = "Лекция", teacher = "Пахомов Сергей Васильевич", room = "Г-309", subgroup = null),
            LessonItem(dayOfWeekIndex = 1, weekParity = 1, time = "12:10 - 13:40", title = "Основы технической диагностики", type = "Семинар", teacher = "Пахомов Сергей Васильевич", room = "Г-110", subgroup = null),
            LessonItem(dayOfWeekIndex = 1, weekParity = 1, time = "13:50 - 15:20", title = "Основы технической диагностики", type = "Лабораторная", teacher = "Пахомов Сергей Васильевич", room = "Г-110", subgroup = 1),
            LessonItem(dayOfWeekIndex = 2, weekParity = 1, time = "12:10 - 13:40", title = "Специальные измерения и рельсовые цепи", type = "Лекция", teacher = "Скоробогатов Максим Эдуардович", room = "А-207", subgroup = null),
            LessonItem(dayOfWeekIndex = 3, weekParity = 1, time = "10:10 - 11:40", title = "Инженерная экология", type = "Семинар", teacher = "Федорова Наталья Владимировна", room = "Д-311", subgroup = null),
            LessonItem(dayOfWeekIndex = 4, weekParity = 1, time = "12:10 - 13:40", title = "Микропроцессорные информационно-управляющие системы", type = "Лекция", teacher = "Миронов Борис Михайлович", room = "А-211", subgroup = null)
        )
    }
}

// --- СОХРАНЕНИЕ И КЭШИРОВАНИЕ ---

fun saveSettings(prefs: android.content.SharedPreferences, s: AppSettings) {
    prefs.edit()
        .putString("source", s.source.name)
        .putString("scheduleUrl", s.scheduleUrl)
        .putString("visualStyle", s.visualStyle.name)
        .putString("contrastLevel", s.contrastLevel.name)
        .putString("themeMode", s.themeMode.name)
        .putString("darkVariant", s.darkVariant.name)
        .putString("barPosition", s.barPosition.name)
        .putFloat("fontScale", s.fontScale)
        .putInt("mySubgroup", s.mySubgroup)
        .putBoolean("hasCustomBg", s.hasCustomBg)
        .putFloat("bgBlurRadius", s.bgBlurRadius)
        .apply()
}

fun loadSettings(prefs: android.content.SharedPreferences): AppSettings {
    return AppSettings(
        source = ScheduleSource.valueOf(prefs.getString("source", ScheduleSource.LINK.name)!!),
        scheduleUrl = prefs.getString("scheduleUrl", "https://rasp.irgups.ru/index.php?action=student&pid=5368&view=table")!!,
        visualStyle = VisualStyle.valueOf(prefs.getString("visualStyle", VisualStyle.MATERIAL_SYSTEM.name)!!),
        contrastLevel = ContrastLevel.valueOf(prefs.getString("contrastLevel", ContrastLevel.NORMAL.name)!!),
        themeMode = ThemeMode.valueOf(prefs.getString("themeMode", ThemeMode.SYSTEM.name)!!),
        darkVariant = DarkVariant.valueOf(prefs.getString("darkVariant", DarkVariant.STANDARD_141414.name)!!),
        barPosition = BarPosition.valueOf(prefs.getString("barPosition", BarPosition.BOTTOM.name)!!),
        fontScale = prefs.getFloat("fontScale", 1.0f),
        mySubgroup = prefs.getInt("mySubgroup", 1),
        hasCustomBg = prefs.getBoolean("hasCustomBg", false),
        bgBlurRadius = prefs.getFloat("bgBlurRadius", 0f)
    )
}

fun saveCachedLessons(prefs: android.content.SharedPreferences, week: Int, list: List<LessonItem>) {
    val arr = JSONArray()
    list.forEach { l ->
        arr.put(JSONObject().apply {
            put("id", l.id)
            put("dayOfWeekIndex", l.dayOfWeekIndex)
            put("weekParity", l.weekParity)
            put("specificDateIso", l.specificDateIso ?: "")
            put("time", l.time)
            put("title", l.title)
            put("type", l.type)
            put("teacher", l.teacher)
            put("room", l.room)
            put("subgroup", l.subgroup ?: 0)
        })
    }
    prefs.edit().putString("week_lessons_v4_$week", arr.toString()).apply()
}

fun loadCachedLessons(prefs: android.content.SharedPreferences, week: Int): List<LessonItem> {
    val raw = prefs.getString("week_lessons_v4_$week", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val sub = o.optInt("subgroup", 0)
            val dateIso = o.optString("specificDateIso", "").ifBlank { null }
            LessonItem(
                id = o.optString("id", UUID.randomUUID().toString()),
                dayOfWeekIndex = o.optInt("dayOfWeekIndex", 0),
                weekParity = o.optInt("weekParity", week),
                specificDateIso = dateIso,
                time = o.getString("time"),
                title = o.getString("title"),
                type = o.getString("type"),
                teacher = o.getString("teacher"),
                room = o.getString("room"),
                subgroup = if (sub == 0) null else sub
            )
        }
    } catch (_: Exception) { emptyList() }
}

fun saveCustomLessons(prefs: android.content.SharedPreferences, list: List<LessonItem>) {
    val arr = JSONArray()
    list.forEach { l ->
        arr.put(JSONObject().apply {
            put("id", l.id)
            put("dayOfWeekIndex", l.dayOfWeekIndex)
            put("specificDateIso", l.specificDateIso ?: "")
            put("time", l.time)
            put("title", l.title)
            put("type", l.type)
            put("teacher", l.teacher)
            put("room", l.room)
            put("subgroup", l.subgroup ?: 0)
        })
    }
    prefs.edit().putString("custom_lessons_v4", arr.toString()).apply()
}

fun loadCustomLessons(prefs: android.content.SharedPreferences): List<LessonItem> {
    val raw = prefs.getString("custom_lessons_v4", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val sub = o.optInt("subgroup", 0)
            val dateIso = o.optString("specificDateIso", "").ifBlank { null }
            LessonItem(
                id = o.getString("id"),
                dayOfWeekIndex = o.optInt("dayOfWeekIndex", 0),
                specificDateIso = dateIso,
                time = o.getString("time"),
                title = o.getString("title"),
                type = o.getString("type"),
                teacher = o.getString("teacher"),
                room = o.getString("room"),
                subgroup = if (sub == 0) null else sub,
                isCustom = true
            )
        }
    } catch (_: Exception) { emptyList() }
}

fun saveNotes(prefs: android.content.SharedPreferences, notes: List<NoteItem>) {
    val arr = JSONArray()
    notes.forEach { n ->
        arr.put(JSONObject().apply {
            put("id", n.id)
            put("dateIso", n.dateIso)
            put("text", n.text)
        })
    }
    prefs.edit().putString("schedule_notes_v4", arr.toString()).apply()
}

fun loadNotes(prefs: android.content.SharedPreferences): List<NoteItem> {
    val raw = prefs.getString("schedule_notes_v4", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            NoteItem(
                id = o.getString("id"),
                dateIso = o.getString("dateIso"),
                text = o.getString("text")
            )
        }
    } catch (_: Exception) { emptyList() }
}

fun saveAndLoadGalleryBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val file = File(context.filesDir, "custom_bg.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Exception) { null }
}

fun loadCustomBgBitmap(context: Context): Bitmap? {
    val file = File(context.filesDir, "custom_bg.jpg")
    return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
}

fun clearCustomBgFile(context: Context) {
    val file = File(context.filesDir, "custom_bg.jpg")
    if (file.exists()) file.delete()
}
