# Приёмка фундамента Android

## Ручной чек-лист

- [ ] Запустить приложение на Android 10+ в авиарежиме. Проверить заголовок «Словарь», пару EN ↔ RU и сообщение об отсутствии базы.
- [ ] Ввести русский и английский текст, дефис, апостроф и пробел. Очистить поле крестиком. При вводе приложение остаётся в пустом состоянии.
- [ ] Открыть экран статьи и вернуться стрелкой. Убедиться, что набранный запрос сохранился.
- [ ] Перейти в «Мой словарь» → «Открыть подборки». Системная кнопка «Назад» возвращает в «Мой словарь».
- [ ] Перейти в «Повторение» → «Посмотреть прогресс». Убедиться, что показано пустое состояние истории.
- [ ] Открыть настройки со страницы прогресса. Стрелка возвращает на прогресс, следующий возврат — к повторению.
- [ ] Повернуть устройство на вложенном экране и при редактировании запроса. Проверить заголовок, текст и возврат.
- [ ] Увеличить системный размер шрифта, проверить прокрутку, клавиатуру, жестовую и трёхкнопочную навигацию. Проверить подписи интерактивных элементов в TalkBack.
- [ ] Проверить выход системной кнопкой «Назад» с начального экрана.

## Решения и открытые вопросы

- На 25 ноября 2026 года для сборки выбраны локально доступные AGP 9.4.1, Gradle 9.6.0, Material 1.14.0 и compile/target SDK 37. Идентификатор прототипа — `app.lexiflow`.
- Источники требований — паспорт, HANDOFF от 24 ноября и отчёты предварительного аудита. Завершённый аудит в `origin/main:research/docs/LexiFlow_Data_Source_Audit_Report.md` оставляет открытыми права на отдельные материалы, выбор источников и сопоставление значений.
- Перед интеграцией корпуса требуется утвердить источники, состав поставки, структуру словарных и пользовательских данных, формат импорта и критерии качества EN ↔ RU.
- Для интерфейса предстоит оценить удобство трёх вкладок, читаемость системного шрифта и необходимость включения Arsenal.
- Для проверки целевых показателей паспорта потребуются реальный корпус и контрольное устройство. Размер APK каркаса не оценивает размер будущей словарной базы.

## Файлы реализации

Список исходных файлов, конфигурации и документации. README.md и .gitignore существовали в HEAD. Остальные файлы добавлены для Android-фундамента.

```text
.gitignore
AGENTS.md
app\build.gradle.kts
app\src\androidTest\java\app\lexiflow\ui\NavigationTest.java
app\src\main\AndroidManifest.xml
app\src\main\java\app\lexiflow\data\DictionaryRepository.java
app\src\main\java\app\lexiflow\data\LocalDictionaryRepository.java
app\src\main\java\app\lexiflow\ui\FoundationViewModel.java
app\src\main\java\app\lexiflow\ui\MainActivity.java
app\src\main\java\app\lexiflow\ui\Screen.java
app\src\main\res\drawable\ic_back.xml
app\src\main\res\drawable\ic_brand.xml
app\src\main\res\drawable\ic_review.xml
app\src\main\res\drawable\ic_search.xml
app\src\main\res\drawable\ic_settings.xml
app\src\main\res\drawable\ic_vocabulary.xml
app\src\main\res\layout\activity_main.xml
app\src\main\res\layout\screen_content.xml
app\src\main\res\menu\sections.xml
app\src\main\res\menu\toolbar.xml
app\src\main\res\values\colors.xml
app\src\main\res\values\strings.xml
app\src\main\res\values\themes.xml
app\src\test\java\app\lexiflow\ui\FoundationViewModelTest.java
build.gradle.kts
docs\android-foundation.md
gradle.properties
gradle\wrapper\gradle-wrapper.jar
gradle\wrapper\gradle-wrapper.properties
gradlew
gradlew.bat
README.md
settings.gradle.kts
```
