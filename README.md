# Тарілка · Tarilka

Приватний офлайн-трекер харчування для Android. Українська та англійська. Безкоштовно, без реклами й акаунтів.

> **Статус: тестова версія.** Відгуки й помилки: Опції → Про застосунок → «Написати відгук» або [Issues](../../issues).

## Особливості

- 🔒 **Приватність.** Дозволу на інтернет немає, дані лише на телефоні, хмарну копію вимкнено. За бажанням щоденник шифрується й відкривається відбитком або PIN-кодом; можна заборонити знімки екрана. Резервна копія вручну, за бажанням із паролем (AES-256).
- 🔬 **677 продуктів офлайн.** Дані USDA, етикетки українських продуктів і ~130 страв за рецептами: борщ, вареники, сочник, фастфуд, суші, торти. Біля кожного продукту видно джерело.
- 🍽️ **Швидкий запис.** Пошук, список текстом («гречка 200 г, котлета»), фото тарілки, «Як учора», свої продукти й рецепти.
- 🛡️ **Безпечні цілі.** Норма ніколи не нижча за базовий обмін. Схуднення не пропонується до 18 років, при ІМТ < 18,5, у вагітність і при годуванні груддю; до 18 років калорій не видно зовсім.
- 🌱 **Звички без осуду.** Кольорові плашки, підсумки за тиждень, місяць і рік із графіками, поради «менше · замінити · додати» з вашими продуктами й джерелом (ВООЗ, EFSA, NICE).
- 📄 **Звіт PDF** з графіками для лікаря чи дієтолога.
- 💧 **Рідина, кроки, вага, талія.** Супи й напої враховуються в рідині; кроки з Health Connect або датчика.
- 🌙 **За бажанням:** сон, «Чому я їм», тренування, кишківник, цикл, AUDIT-C, нагадування.
- 🤖 **Локальний ШІ (Gemma 4), за бажанням:** розпізнає їжу на фото й пише підсумки. Усе на телефоні.

## Встановлення

1. Завантажте `.apk` з [Releases](../../releases/latest). Для автооновлень — [Obtainium](https://obtainium.imranr.dev/).
2. ШІ (необовʼязково): Опції → «ШІ на телефоні» → «Завантажити модель» (≈2,6 ГБ) → «Обрати файл моделі». Файл перевіряється за контрольною сумою.

## Прозорість

- **Дозволи** лише для ввімкнених функцій: кроки, Health Connect, сповіщення. Збірка зупиняється, якщо зʼявляється дозвіл на інтернет.
- **Шифрування:** не шифруються профіль, налаштування, кроки за день, очікувана дата циклу й кілька чисел для нагадувань, список «Не знайдено». CSV-експорт не шифрується.
- **Збірка:** APK збирається з цього коду в GitHub Actions і підписується лише після схвалення автора. База — `scripts/food_sources/` і `scripts/build_foods.py`.
- **Як створено:** код написано за допомогою ШІ (Claude, Anthropic) під керівництвом і з тестуванням автора.
- **Застереження:** поради загальні й не замінюють лікаря; ШІ оцінює порції приблизно; записи в Health Connect бачать застосунки, яким ви дали там доступ.

Kotlin · Jetpack Compose · Room · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Ліцензія MIT.

---

# Tarilka · English

A private, offline food tracker for Android. Ukrainian and English. Free, no ads, no accounts.

> **Status: test version.** Feedback and bugs: Options → About → Send feedback, or [Issues](../../issues).

## Features

- 🔒 **Privacy.** No internet permission, data stays on the phone, cloud backup is off. Optionally the diary is encrypted and opens with fingerprint or PIN; screenshots can be blocked. Backups by hand, optionally with a password (AES-256).
- 🔬 **677 foods offline.** USDA data, Ukrainian product labels and ~130 dishes calculated from recipes: borscht, varenyky, sochnyk, fast food, sushi, cakes. Every food shows its source.
- 🍽️ **Quick logging.** Search, a typed list ("buckwheat 200 g, cutlet"), a plate photo, "Same as yesterday", your own foods and recipes.
- 🛡️ **Safe targets.** Never below basal metabolism. No weight loss offered under 18, with a BMI under 18.5, in pregnancy or while breastfeeding; no calories shown at all under 18.
- 🌱 **Habits without judgement.** Colour badges, weekly, monthly and yearly summaries with charts, "less · swap · add" suggestions with your own foods and the source (WHO, EFSA, NICE).
- 📄 **PDF report** with charts for a doctor or dietitian.
- 💧 **Fluids, steps, weight, waist.** Soups and drinks count as fluid; steps from Health Connect or the phone's sensor.
- 🌙 **Optional:** sleep, Why I eat, workouts, bowel, cycle, AUDIT-C, reminders.
- 🤖 **On-device AI (Gemma 4), optional:** recognises food on photos and writes summaries. Everything stays on the phone.

## Install

1. Download the `.apk` from [Releases](../../releases/latest). For updates, use [Obtainium](https://obtainium.imranr.dev/).
2. AI (optional): Options → On-device AI → Download model (≈2.6 GB) → Choose a model file. The file is checked by its checksum.

## Transparency

- **Permissions** only for features you turn on: steps, Health Connect, notifications. The build stops if an internet permission appears.
- **Encryption:** not encrypted are the profile, settings, daily steps, the expected cycle date and a few numbers for reminders, and the Not found list. CSV export is not encrypted.
- **Build:** the APK is built from this code in GitHub Actions and signed only after the author's approval. Food database: `scripts/food_sources/` and `scripts/build_foods.py`.
- **How it was made:** written with AI assistance (Claude, Anthropic), directed and tested by the author.
- **Disclaimer:** advice is general and does not replace a doctor; the AI estimates portions roughly; Health Connect records are visible to apps you allowed there.

Kotlin · Jetpack Compose · Room · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). MIT licence.
