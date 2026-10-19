# OrientVibe — контекст проекта

## О приложении

**OrientVibe** — Android-приложение для ориентирования (орienteering) с камерой/галереей как источником карты.
Пользователь фотографирует карту или загружает изображение → приложение через YOLO обнаруживает контрольные пункты и их номера → пользователь ставит старт/финиш → привязывает к GPS → навигация в реальном времени.

**Технологии:** Kotlin, Jetpack Compose, ViewModel + StateFlow, ONNX Runtime (YOLO), Android GPS API.

**Минимальная версия SDK:** Android 23+ (копирайты/комментарии подтверждают minSdkVersion 23).

## Архитектура

```
MainActivity (入口)
    └── MainScreen (Composable, главный экран)
            ├── MapViewModel (StateFlow<MapState>) — загрузка карты, маршрут, orientation
            │       ├── MapDetector (детекция: YOLO → OCR → correlation)
            │       │       ├── OnnxObjectDetector ×2 (orientmapv8n.onnx + digitsv8n.onnx)
            │       │       └── DetectionResult / MapDetectionProgressListener
            │       └── BoundingBox / RoutePoint / PlacingMode / MapState
            │
            ├── NavViewModel (StateFlow<GpsState>) — GPS, трекинг, калибровка
            │       ├── GpsManager (GPS updates)
            │       ├── TrackRecorder (запись трека)
            │       ├── MapCalibrationUtils (расчёты: gps↔image, bearing, haversine)
            │       └── GpsState / GpsFix / CalibrationPoint / MapCalibration / RouteDistance
            │
            └── UI компоненты:
                    ├── SubsamplingMapView — основное отображение карты (pan/zoom)
                    ├── ControlPointOverlay — отрисовка кругов контрольных пунктов
                    ├── TrackOverlay — трек GPS-перемещения + индикатор направления
                    ├── RouteOverlayView — линия маршрута (старт→финиш)
                    ├── TopInfoPanel — верхняя панель с данными навигации
                    ├── BottomButtonPanel — нижняя панель шагов/кнопок
```

## Ключевые файлы

### Модели данных (`model/`)

#### `GpsModels.kt` — модели GPS и калибровки
- `GpsState` — **единственный источник правды** для всего GPS/калибровочного состояния. Все поля каноничны здесь.
  - `startCalibrated`, `finishCalibrated` — флаги привязки
  - `autoMode`, `autoCycleActive` — авто-режим навигации
  - `routeDistance` / `mapScale` — производные поля, вычисляются в NavViewModel при калибровке
  - `originalStartGps` — исходная GPS точка старта для перекалибровки
  - `accuracyLevel` — computed property: HIGH_ACCURACY (≤10м), LOW_ACCURACY (>10м), NO_FIX
- `GpsCoordinate` — latitude, longitude
- `GpsFix` — координаты + accuracy, bearing, speed, timestamp, altitude
- `CalibrationPoint` — связка GPS координат с относительной позицией на изображении (0..1)
- `MapCalibration` — результат двухточечной калибровки: pointA, pointB, scaleMetersPerPixel, bearingDegrees, magneticDeclination

#### `MapModels.kt` — модели карты
- `BoundingBox` — bbox с centerX/Y, width/height (rel 0..1), confidence, label, number (распознанный)
- `RoutePoint` — относительные координаты на карте (0..1) для старта/финиша
- `PlacingMode` — NONE / PLACING_START / PLACING_FINISH
- `MapState` — состояние карты: bitmap, URI, bboxes, startPoint, finishPoint, azimuth, northAngle, isProcessing

### ViewModels

#### `NavViewModel.kt` — GPS и навигация
- Объединяет GpsManager + TrackRecorder + калибровку в одном ViewModel
- **Всё состояние через `_gpsState: MutableStateFlow<GpsState>`** — standalone fields отсутствуют (Task #5 завершён)
- Внутренние поля `_autoMode` и `_autoCycleActive` обновляют GpsState при изменении
- `addCalibrationPoint()` — двухточечная калибровка. При 2+ точках мгновенно вычисляет MapCalibration и ставит оба флага calibrated
- Методы экранирования для MainScreen: `setOriginalStartGps()`, `getOriginalStartGps()`, `clearCalibration()`
- Вычисление routeDistance из трека при наличии originalStartGps

#### `MapViewModel.kt` — загрузка и обработка карты
- Делегирует всю детекцию в `MapDetector` (yolo/MapDetector.kt)
- `loadImageFromUri()` — открывает InputStream → декодит Bitmap → вызывает mapDetector.detect()
- Управляет маршрутом: `placeRoutePoint()`, `moveStartPoint()`, `moveFinishPoint()`
- `updateAzimuth()` — вычисляет азимут маршрута от start→finish
- В `onCleared()` закрывает детектор через `mapDetector.close()`

#### `MapDetector.kt` — вся логика детекции (outsource'd из MapViewModel)
- `init()` — загружает orientmapv8n.onnx + digitsv8n.onnx из assets
- `detect(bitmap)` → DetectionResult — полный пайплайн: YOLO → split control/number → OCR цифр → correlation
- `detectAndAssembleNumbers()` — digitsv8n для номеров внутри bbox, склейка в число по горизонтали
- `correlateNumbersWithControls()` — сопоставление номеров с control points по координатам
- Константы: `CONTROL_POINT_SNAP_THRESHOLD`, `DIGIT_ROI_EXPANSION_FACTOR`, `NUMBER_CORRELATION_THRESHOLD_MULT` (топ-левел)

### Экран и шаги

#### `MainScreen.kt` — главный Composable, 3 шага:
1. **Загрузка карты** — камера / галерея → loadImageFromUri()
2. **Маршрут** — нажатиями ставятся старт/финиш, auto-mode для циклической навигации
3. **Результат** — "Здесь старт" / "Здесь финиш" привязка GPS, авто-режим

**bindGpsToStart():**
- Сохраняет originalStartGps → NavViewModel
- Привязывает старт к GPS + создаёт синтетическую финишную точку (1км по bearing)
- Запускает трекинг

**bindGpsToFinish():**
- Перекалибровка: clearCalibration → addCalibrationPoint(start) → addCalibrationPoint(finish)
- Обновляет northAngle из calibration bearing
- Auto-cycle: если включён — возвращает на шаг 2, start = old finish, ставит режим PLACING_FINISH

### UI компоненты

#### `SubsamplingMapView.kt` — основное отображение карты
- CustomView с pan/zoom через Matrix
- Отображает bitmap + overlay слои (контрольные пункты, трек, маршрут)
- Принимает northAngle для вращения

#### `TopInfoPanel.kt` — верхняя панель
- Анимированное появление/исчезновение
- Данные: курс, азимут, дистанция от старта, GPS статус (цвет: красный/жёлтый/зелёный)
- Progress bar при обработке изображения

#### `BottomButtonPanel.kt` — нижняя панель
- 3 шага с кнопками (камера, галерея, старт, финиш, здесь-старт, здесь-финиш, авто)

#### `ControlPointOverlay.kt` и `TrackOverlay.kt`
- Paint-based отрисовка поверх bitmap через Canvas.draw*()
- ControlPointOverlay — круги контрольных пунктов
- TrackOverlay — трек GPS + индикатор направления (правильно закрывает ресурсы, исправлено всегда-истинное условие в строке 192)

## Паттерны работы с состоянием

### Золотое правило: GpsState — единственный источник правды для GPS
- **Чтение:** `by navViewModel.gpsState.collectAsState()` → деструктуризация полей
- **Запись:** только через методы NavViewModel (`setOriginalStartGps`, `setAutoMode`, `clearCalibration`)
- Никаких локальных `mutableStateOf` для GPS-полей в MainScreen (убрано в Task #5)

### Вычисление routeDistance и mapScale
- `routeDistance` вычисляется в NavViewModel: расстояние от originalStartGps до последней точки трека (haversine)
- `mapScale` берётся из calibration.scaleMetersPerPixel
- Оба поля nullable — `null` когда не привязаны, значение когда привязаны

### Авто-режим (auto-mode / auto-cycle)
- Включается кнопкой в шаге 3
- При связывании финиша: сбрасывает autoCycleActive → шаг 2 → start=old finish → режим PLACING_FINISH
- При новом финише: автоматический переход к навигации (step 2→3)

## Недавние изменения

### Detectors outsource (текущая задача)
- `MapDetector.kt` — вся детекция outsource'd из MapViewModel: инициализация ONNX, detect() пайплайн, OCR, correlation
- Константы (`CONTROL_POINT_SNAP_THRESHOLD` и др.) — топ-левел в MapDetector.kt
- MapViewModel теперь thin: image loading → delegate detect → state updates + route management

### Task #5 — Убрано дублирование state (завершено)
- `startCalibrated`, `finishCalibrated`, `routeDistance`, `mapScale`, `originalStartGps` перенесены из MainScreen в GpsState/NavViewModel
- `bindGpsToStart` использует `navViewModel.setOriginalStartGps()` для записи
- `bindGpsToFinish` читает оригинал через `navViewModel.getOriginalStartGps()`
- LaunchedEffect на привязку упрощён — данные читаются напрямую из gpsState

### Task #3 — detectorDigits в onCleared() (завершено)
- Добавлена закрытие detectorDigits в MapViewModel.onCleared()

## Потенциальные проблемы и решения

1. **Гонка в добавлении боксов:** при загрузке нового изображения старые bboxes удаляются, новые появляются после YOLO — MainScreen должен корректно обновлять состояние
2. **Синтетическая финишная точка:** при привязке старта создаётся финиш на 1км по bearing. Реальный финиш может быть ближе/дальше — перекалибровка при реальном финише исправляет масштаб
3. **auto-cycle цикл:** требует чтобы gpsState.autoMode И gpsState.finishCalibrated были true одновременно

## Структура проекта

```
app/src/main/java/ru/bondarenko/orientvibe/ng/
├── MainActivity.kt                    -- Точка входа, создаёт MapViewModel
├── screen/MainScreen.kt               -- Главный Composable (3 шага)
├── model/
│   ├── GpsModels.kt                   -- GpsState, GpsFix, CalibrationPoint, MapCalibration
│   └── MapModels.kt                   -- BoundingBox, RoutePoint, MapState, PlacingMode
├── viewmodel/MapViewModel.kt           -- Загрузка карты, маршрут (делегирование MapDetector)
├── gps/
│   ├── NavViewModel.kt                 -- GPS + трекинг + калибровка (единое состояние)
│   ├── GpsManager.kt                   -- Обёртка над LocationManager
│   ├── TrackRecorder.kt               -- Запись GPS-трека в GPX
│   └── MapCalibration.kt              -- Утилиты: gps↔image, bearing, haversine
├── ui/
│   ├── components/
│   │   ├── SubsamplingMapView.kt      -- Pan/zoom карта
│   │   ├── ControlPointOverlay.kt     -- Круги контрольных пунктов + боксы номеров
│   │   ├── TrackOverlay.kt            -- Трек GPS + направление
│   │   ├── RouteOverlayView.kt        -- Линия маршрута
│   │   ├── TopInfoPanel.kt            -- Верхняя панель данных
│   │   ├── BottomButtonPanel.kt       -- Нижняя панель шагов
│   │   └── CustomImageView.kt         -- Базовый класс с pan/zoom логикой
│   └── theme/                         -- Material3 тема
├── yolo/
│   ├── OnnxObjectDetector.kt          -- Обёртка над ONNX Runtime для YOLO
│   └── MapDetector.kt                 -- Пайплайн: детекция → OCR → correlation
└── model/PanelStep.kt                 -- Модели для BottomButtonPanel (кнопки и шаги)
```
