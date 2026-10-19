# Auto Navigation Mode — Текущее состояние реализации

**Дата**: 2026-09-12  
**Статус**: частично реализовано, требуется завершение UI (AutoModeScreen)

---

## 1. Модель данных (ЗАВЕРШЕНО)

### `app/src/main/java/ru/bondarenko/orientvibe/ng/model/AutoNavModels.kt`
- **AutoNavConstants** — константы: уровни достоверности (0.0, 0.5, 0.8, 1.0), пороги точности GPS (10m, 20m, 100m), пороги отклонения курса (30° для low confidence, 20° для high+), время секторов 20s
- **AutoTrackPoint** — точка трека авто режима: coordinate, timestamp, instantaneousBearing, averagedBearing, speed, inSector, movingToNextKp, confidence
- **AutoNavStatus** — полный статус навигации по 18 параметрам из spec. Включает computed properties: latestTrackPoint, currentAveragedBearing, hasValidGps, courseDeviation, inSectorFlag

### `app/src/main/java/ru/bondarenko/orientvibe/ng/model/GpsModels.kt`
- Добавлены в **GpsState**:
  - `autoMode: Boolean = false`
  - `autoCycleActive: Boolean = false`
  - `autoBindActive: Boolean = false`
  - `autoPhase: String = "IDLE"`
  - `autoKpNumberInput: Int = 1`
  - `autoTargetKpNumber: Int? = null` — целевой КП (параметр 6)
  - `autoNextKpNumber: Int? = null` — следующий КП (параметр 9)
  - `autoTakenKpNumber: Int? = null` — взятый КП (параметр 4)
  - `autoDistanceToTarget: Double? = null` — расстояние до целевого КП
  - `autoTimeInSector: Long = 0L` — таймер в секторе (сек)
  - `autoBearingToTarget: Float? = null` — пеленг на целевой КП
  - `autoAveragedBearing: Float? = null` — усреднённый курс
  - `autoMapScale: Double? = null` — масштаб карты
  - `autoDetectedKpCount: Int = 0` — число обнаруженных КП
  - `autoKpTakenFlag: Boolean = false` — флаг "КП взят" (параметр 18)
  - `autoConfidenceLevel: Float = 0f` — общий уровень достоверности

---

## 2. Business Logic (ЗАВЕРШЕНО)

### `app/src/main/java/ru/bondarenko/orientvibe/ng/nav/AutoNavigationManager.kt`
Полный жизненный цикл авто режима:

**Этапы навигации (AutoNavPhase enum):**
- `IDLE` — ожидание загрузки карты и ввода номера КП
- `WAIT_GPS` — карта загружена, ожидание точного GPS
- `ATTACHING` — есть GPS, начальная калибровка привязки
- `DETECTING` — запущена YOLO детекция КП на карте
- `NAVIGATING` — активная навигация с записью трека
- `AUTO_TAKE_DETECTED` — обнаружен автоматический взятый КП, требует перекалибровки
- `DONE` — перекалибровано, переход к следующему КП

**Ключевые методы:**
- `initAutoMode()` — инициализация режима, очистка состояния
- `onMapLoaded(bitmap)` — загрузка карты → WAIT_GPS phase
- `onKpNumberEntered(number)` — ввод начального номера КП
- `onGpsFixArrived(fix)` — получение GPS fix, переход к ATTACHING при хорошей точности
- `startDetection()` — запуск YOLO детекции
- `onDetectionComplete(boxes)` — обработка результатов детекции (заполнение параметров 2,3,7-10)
- `onGpsUpdateDuringNavigation(fix)` — обновление GPS во время навигации
- `checkAutoKpTake(nowMillis)` — проверка авто-взятого КП (таймер >= 20s + отклонение курса > 45° + совпадение расстояния ±50m)
- `performAutoTake(status, track, nowMillis)` — автоматический взятый КП + перекалибровка
- `cancel()` — отмена режима

---

## 3. Track Recorder (ЗАВЕРШЕНО)

### `app/src/main/java/ru/bondarenko/orientvibe/ng/gps/TrackRecorder.kt`
Расширен для поддержки авто режима:

**Дополнительные поля:**
- `_autoTrackPoints: LinkedList<AutoTrackPoint>` — трек авто режима
- `_sectorEnteredAt: Long` — время входа в сектор КП
- `_nextKpEnteredAt: Long` — время входа в направление к следующему КП
- `_autoNavStatus: StateFlow<AutoNavStatus>` — текущий статус авто навигации

**Ключевые методы:**
- `startAutoTrack(attachKpNumber)` — очистка трека, сброс таймеров
- `recordAutoPoint(fixGps, currentStatus)` — запись точки с фильтрацией по точности GPS (>100m пропускается), throttle 500ms, вычисление instant/averaged bearing, круговой mean за 10s окно, флаги секторов
- `computeInSector()` — проверка вхождения в сектор целевого КП (±30° для low confidence, ±20° для high)
- `computeMovingToNextKp()` — проверка движения к следующему КП
- `averageBearing()` — круговое среднее по sin/cos
- `_trimAutoTrackPoints()` — обрезка до MAX_TRACK_POINTS (300)
- `clearAutoTrack()` — полная очистка авто трека

---

## 4. NavViewModel интеграция (ЧАСТИЧНО ЗАВЕРШЕНО)

### `app/src/main/java/ru/bondarenko/orientvibe/ng/gps/NavViewModel.kt`

**Реализовано:**
- Добавлено поле: `val autoNavigationManager = AutoNavigationManager(context)`
- Колле́ктор фазы авто режима в `_gpsState.value.autoPhase`
- Колле́ктор статуса авто режима через `autoNavigationManager.autoStatus` — популирует поля GpsState (targetKpNumber, distanceToTarget, bearing и др.)
- Методы API:
  - `startAutoMode()` — старт GPS + initAutoMode
  - `onMapLoaded(bitmap)` — передаёт в autoNavigationManager
  - `setKpNumberEntered(kpNumber)` — ввод номера КП
  - `onGpsFixReceived(fix)` — передача GPS fix
  - `startDetection()` / `onDetectionComplete(boxes)` — управление детекцией
  - `cancelAutoMode()` — отмена режима

**Не реализовано:**
- Нет прямого обновления `autoCycleActive` и `autoBindActive` из NavViewModel в GpsState при смене фазы
- Требуется интеграция GPS updates в autoNavigationManager через LaunchedEffect в MainScreen

---

## 5. AutoModeScreen UI (НЕ НАЧАТО)

**Требуется создать:**
`app/src/main/java/ru/bondarenko/orientvibe/ng/screen/AutoModeScreen.kt`

**Экранный поток:**
1. **Start screen** — две кнопки "Авто" / "Ручной", индикатор статуса GPS (включён/выключен)
2. **Map loading overlay** — кнопка загрузки карты из галереи/фото, ввод номера начального КП (NumberPicker или TextField)
3. **Detection progress** — индикатор прогресса детекции YOLO (progress bar + message)
4. **Active navigation screen:**
   - Top info panel с полями:
     - Текущий этап (фазу): "Ожидание GPS", "Детекция...", "Навигация к КП-5" и т.д.
     - Номер целевого КП (targetKpNumber)
     - Расстояние до целевого КП (distanceToTarget, в метрах)
     - Усреднённый курс (averagedBearing, в градусах)
     - Пеленг на целевой КП (bearingToTarget)
     - Таймер в секторе (timeInSector, мм:сс)
     - Масштаб карты (mapScaleMetersPerPixel, м/px)
     - Число обнаруженных КП (detectedKpCount)
     - Уровень достоверности (confidence level indicator)
     - Флаг "КП взят!" (kpTakenFlag — красным мигающим текстом)
   - Карта с оверлеем:
     - Точки обнаруженных КП с номерами
     - Линия направления к целевому КП (пунктирная)
     - Трек авто режима (синяя линия из точек AutoTrackPoint)
     - Зелёный круг текущего GPS положения
   - Bottom panel с кнопками:
     - "Отмена" — отмена авто режима
     - "Ручной режим" — возврат к ручному

**Паттерны UI из MainScreen:**
- Использует `SubsamplingMapView` для отображения карты
- `TopInfoPanel` для данных GPS и навигации
- `BottomButtonPanel` с PanelStep/PanelButton
- Тёмная тема (0xFF121212 фон)
- Компоненты: MapDragListener, MapTapListener

---

## 6. Интеграция с MainScreen (НЕ НАЧАТО)

**Требуется:**
1. Добавить переключение между основным экраном и AutoModeScreen по кнопке "Авто" в BottomButtonPanel
2. Подключить `navViewModel.onGpsFixReceived()` из LaunchedEffect MainScreen при авто режиме
3. Передать обнаруженные KP (`mapState.controlsBoundingBoxes`, `mapState.numbersBoundingBoxes`) в autoNavigationManager после детекции
4. Отображение навигационных данных (target KP, distance, bearing) из `gpsState.auto*` полей

---

## 7. Тестирование (ПЛАНИРУЕТСЯ)

**Тестовые карты:**
- `app/src/main/assets/test_photo_mapdrag.jpg` — тестовая карта для разработки
- `PXL_20260830_092754005.jpg` — реальная фотокарта (в корневой директории репозитория)
- `PXL_20260726_131215883.jpg` — ещё одна реальная фотокарта

**Эмулятор:** Pixel 7 с эмулируемым GPS

**Проверки:**
- [ ] Все KP круги обнаруживаются YOLO с корректными номерами
- [ ] Калибровка по привязке GPS работает корректно
- [ ] Секторное обнаружение работает с порогами ±30°/±20°
- [ ] Авто-взятый КП срабатывает после 20s в секторе + отклонение курса > 45°
- [ ] Перекалибровка при авто-взятом КП обновляет calibration point
- [ ] Трек сохраняется с averaging window 10s, throttle 500ms
- [ ] Фильтрация по GPS accuracy (>100m пропускается)

---

## 8. Зависимости и внешние модули

**Существующие компоненты (не модифицированы):**
- `MapCalibrationUtils` — утилиты: calibrate, gpsToImage, imageToGps, bearing, haversineDistance, bindGpsToFinishWithTrack, calibrateSinglePoint
- `MapDetector` — YOLO пайплайн детекции: init(), detect(), onDetectionComplete()
- `BoundingBox` — результат детекции: centerX, centerY, width, height, confidence, label, number (Int?), tileId
- `MapGeometry` — чистая математика: bearing, haversine distance, координатные преобразования
- `GpsManager` — менеджер GPS обновлений из device sensors
- `NavigationViewModel` / `MapViewModel` — управление состоянием карты

**Модели весов:**
- `orientmapv8n.onnx` — детекция КП кругов (YOLOv8 nano)
- `digitsv8n.onnx` — распознавание номеров КП (digit classifier)

---

## 9. Архитектурные решения

1. **Круговое среднее** для bearing вычисляется через sin/cos summation — корректно обрабатывает переход 360°/0°
2. **LinkedList** для трека вместо List — O(1) удаление из головы при trim, критично для 300 точек
3. **Таймеры секторов** хранятся внутренне в TrackRecorder (`_sectorEnteredAt`, `_nextKpEnteredAt`), а не на AutoNavStatus (у него нет таких методов)
4. **Throttle 500ms** предотвращает flooding трека при高频 GPS обновлений (обычно 1-5Hz)
5. **Filter >100m** — точки с точностью GPS выше 100m не записываются в трек
6. **Авто-взятый КП** требует трёх условий: таймер >= 20s, отклонение курса > 45°, совпадение расстояния ±50m

---

## Следующие шаги

1. [ ] Создать AutoModeScreen.kt со всеми экранами (start → map input → detection → navigation)
2. [ ] Интегрировать в MainScreen с переключением по кнопке "Авто"
3. [ ] Подключить GPS updates к autoNavigationManager через LaunchedEffect
4. [ ] Протестировать на эмуляторе с тестовыми картами
5. [ ] Исправить баги и доделать UI polish
