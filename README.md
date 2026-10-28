# Тарілка · Tarilka

Приватний офлайн-трекер харчування для Android. Українська та англійська. Безкоштовно, без реклами й акаунтів.

> Відгуки й помилки: Опції → Про застосунок → «Написати відгук» або [Issues](../../issues).

## Особливості

- 🔒 **Приватність.** Дозволу на інтернет немає, дані лише на телефоні, хмарну копію вимкнено. За бажанням щоденник і профіль шифруються й відкриваються відбитком або PIN-кодом телефону; експорт і видалення ще раз просять підтвердження; можна заборонити знімки екрана. Резервна копія вручну, типово з паролем (AES-256).
- 🔬 **677 продуктів офлайн.** Дані USDA, етикетки українських продуктів і ~130 страв за рецептами: борщ, вареники, сочник, фастфуд, суші, торти. Біля кожного продукту видно джерело.
- 🍽️ **Швидкий запис.** Пошук, список текстом («гречка 200 г, котлета»), фото тарілки, «Як учора», свої продукти й рецепти.
- 🛡️ **Безпечні цілі.** Норма ніколи не нижча за базовий обмін. Схуднення не пропонується до 18 років, при ІМТ < 18,5, у вагітність і при годуванні груддю; до 18 років калорій не видно зовсім.
- 🌱 **Звички без осуду.** Кольорові плашки, підсумки за тиждень, місяць і рік із графіками, поради «менше · замінити · додати» з вашими продуктами й джерелом (ВООЗ, EFSA, WCRF, NHS).
- 📄 **Звіт PDF** з графіками для лікаря чи дієтолога.
- 💧 **Рідина, кроки, вага, талія.** Супи й напої враховуються в рідині; кроки з Health Connect або датчика.
- 🌙 **За бажанням:** сон, «Чому я їм», тренування, кишківник, цикл, AUDIT-C, нагадування.
- 🤖 **Локальний ШІ (Gemma 4), за бажанням:** розпізнає їжу на фото й пише підсумки. Усе на телефоні.

## Встановлення

1. Завантажте `.apk` з [Releases](../../releases/latest). Для автооновлень — [Obtainium](https://obtainium.imranr.dev/).
2. ШІ (необовʼязково): Опції → «ШІ на телефоні» → «Завантажити модель» (≈2,6 ГБ) → «Обрати файл моделі». Файл перевіряється за контрольною сумою.

## Прозорість

- **Дозволи** лише для ввімкнених функцій: кроки, Health Connect, сповіщення. Збірка зупиняється, якщо зʼявляється дозвіл на інтернет.
- **Шифрування:** із захистом не шифруються налаштування, журнал кроків, кілька чисел за сьогодні для нагадувань і очікувана дата циклу (лише якщо ввімкнено нагадування про цикл). Захист — це PIN телефону: хто його знає, відкриє й Тарілку. CSV і PDF не шифруються.
- **Підпис:** справжній APK підписано сертифікатом із відбитком SHA-256 `64:EA:F9:73:EC:74:AA:88:AB:62:A6:B3:2B:95:52:CC:87:13:04:23:85:FA:3D:74:9F:66:69:8B:CD:B2:5C:60`.
- **Збірка:** APK збирається з цього коду в GitHub Actions і підписується лише після схвалення автора. База — `scripts/food_sources/` і `scripts/build_foods.py`.
- **Як створено:** код написано за допомогою ШІ (Claude, Anthropic) під керівництвом і з тестуванням автора.
- **Застереження:** поради загальні й не замінюють лікаря; ШІ оцінює порції приблизно; записи в Health Connect бачать застосунки, яким ви дали там доступ.

Kotlin · Jetpack Compose · Room · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Ліцензія MIT.

---

# Tarilka · English

A private, offline food tracker for Android. Ukrainian and English. Free, no ads, no accounts.

> Feedback and bugs: Options → About → Send feedback, or [Issues](../../issues).

## Features

- 🔒 **Privacy.** No internet permission, data stays on the phone, cloud backup is off. Optionally the diary and profile are encrypted and open with fingerprint or the phone PIN; export and deleting ask again; screenshots can be blocked. Backups by hand, with a password by default (AES-256).
- 🔬 **677 foods offline.** USDA data, Ukrainian product labels and ~130 dishes calculated from recipes: borscht, varenyky, sochnyk, fast food, sushi, cakes. Every food shows its source.
- 🍽️ **Quick logging.** Search, a typed list ("buckwheat 200 g, cutlet"), a plate photo, "Same as yesterday", your own foods and recipes.
- 🛡️ **Safe targets.** Never below basal metabolism. No weight loss offered under 18, with a BMI under 18.5, in pregnancy or while breastfeeding; no calories shown at all under 18.
- 🌱 **Habits without judgement.** Colour badges, weekly, monthly and yearly summaries with charts, "less · swap · add" suggestions with your own foods and the source (WHO, EFSA, WCRF, NHS).
- 📄 **PDF report** with charts for a doctor or dietitian.
- 💧 **Fluids, steps, weight, waist.** Soups and drinks count as fluid; steps from Health Connect or the phone's sensor.
- 🌙 **Optional:** sleep, Why I eat, workouts, bowel, cycle, AUDIT-C, reminders.
- 🤖 **On-device AI (Gemma 4), optional:** recognises food on photos and writes summaries. Everything stays on the phone.

## Install

1. Download the `.apk` from [Releases](../../releases/latest). For updates, use [Obtainium](https://obtainium.imranr.dev/).
2. AI (optional): Options → On-device AI → Download model (≈2.6 GB) → Choose a model file. The file is checked by its checksum.

## Transparency

- **Permissions** only for features you turn on: steps, Health Connect, notifications. The build stops if an internet permission appears.
- **Encryption:** with protection on, not encrypted are settings, the step log, a few of today's numbers for reminders and the expected cycle date (only if cycle reminders are on). Protection is the phone PIN: anyone who knows it can open Tarilka. CSV and PDF are not encrypted.
- **Signature:** the genuine APK is signed with a certificate whose SHA-256 fingerprint is `64:EA:F9:73:EC:74:AA:88:AB:62:A6:B3:2B:95:52:CC:87:13:04:23:85:FA:3D:74:9F:66:69:8B:CD:B2:5C:60`.
- **Build:** the APK is built from this code in GitHub Actions and signed only after the author's approval. Food database: `scripts/food_sources/` and `scripts/build_foods.py`.
- **How it was made:** written with AI assistance (Claude, Anthropic), directed and tested by the author.
- **Disclaimer:** advice is general and does not replace a doctor; the AI estimates portions roughly; Health Connect records are visible to apps you allowed there.

Kotlin · Jetpack Compose · Room · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). MIT licence.
