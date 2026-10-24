# Тарілка · Tarilka

Офлайн-трекер калорій, нутрієнтів і ваги для Android з локальним ШІ.
Українська та англійська мови. Відкритий код, без реклами, без акаунтів, без хмари.

*An offline calorie, nutrient and weight tracker for Android with on-device AI. Ukrainian and English. [English below](#english).*

## Що вміє

- **Журнал їжі** за прийомами: сніданок, обід, вечеря, перекуси. Підсумок за день: калорії, білки, жири, вуглеводи, клітковина.
- **База продуктів українською** (226 позицій): крупи, овочі, фрукти, м'ясо, риба, молочне, а також борщ, вареники, сирники, голубці, олів'є тощо. Для кожного продукту видно джерело цифр.
- **Фото тарілки → розклад на калорії.** Модель Gemma 4 працює прямо на телефоні: розпізнає страви й оцінює вагу порцій. Цифри беруться з бази, а не вигадуються моделлю; вагу можна виправити перед збереженням.
- **Вода й напої**: кнопка «+250 мл» на головному екрані, норма ≈30 мл на кг ваги. Соки, кефір та інші напої з журналу рахуються автоматично.
- **Кроки** (за бажанням): з Health Connect або з датчика кроків телефону.
- **Health Connect** (за бажанням): їжа, напої та вага записуються туди, кроки читаються звідти.
- **Вага**: щоденні записи, графік, тренд за тиждень.
- **Норми й рекомендації**: формула Міффліна–Сан Жеора, мета (схуднути / тримати / набрати), прості прозорі поради. ШІ може переформулювати їх людською мовою.
- **Приватність**: усе зберігається лише на телефоні. Інтернет потрібен тільки один раз, щоб завантажити модель (на GrapheneOS після цього можна вимкнути дозвіл «Мережа»).

## Встановлення

1. Відкрийте на телефоні сторінку **[Releases](../../releases/latest)** і завантажте файл `tarilka-….apk`.
2. Відкрийте його та дозвольте встановлення з цього джерела.
3. Для автоматичних оновлень зручно використати [Obtainium](https://obtainium.imranr.dev/): додайте адресу цього репозиторію.

### Як увімкнути ШІ

Налаштування → «ШІ на телефоні» → **Завантажити модель** (≈2,6 ГБ, краще через Wi-Fi).
Або завантажте файл `gemma-4-E2B-it.litertlm` з [Hugging Face](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) самостійно і натисніть «Обрати файл моделі».

Модель запускається на GPU, а якщо він недоступний, то на процесорі. Перший аналіз довший, бо модель завантажується в пам'ять.

## Звідки цифри

| Позначка | Що означає |
|---|---|
| USDA | Лабораторні дані [USDA FoodData Central](https://fdc.nal.usda.gov/) (SR Legacy, суспільне надбання) |
| Типова етикетка | Типові значення з упаковок українських продуктів (сметана 15%, кисломолочний сир 5%, кефір 2,5% тощо) |
| Розрахунок за рецептом | Розраховано за типовою рецептурою (борщ, вареники, сирники…) |
| Оцінка ШІ | Продукт не знайдено в базі, цифри оцінила модель (позначено окремо) |

Перевірки якості:
- `scripts/check_foods.py` перевіряє кожен рядок: калорії мають сходитися з білками, жирами й вуглеводами. Запускається при кожній збірці.
- `scripts/verify_usda.py` звіряє всі рядки з позначкою USDA з офіційним набором даних USDA (workflow «Verify food data against USDA»).

Свої продукти (наприклад, з етикетки) можна додати у вкладці «Продукти».

## Обмеження

- Оцінка ваги порції з фото приблизна. Завжди перевіряйте грами.
- Страви за рецептом мають середні значення; домашній борщ може відрізнятися.
- Застосунок не замінює лікаря чи дієтолога.

## Збірка

APK збирається автоматично в GitHub Actions при кожному пуші в `main` і публікується в Releases.
Локально: Android Studio або `gradle assembleRelease` (JDK 17).

Технології: Kotlin, Jetpack Compose, Room, [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Apache 2.0) для Gemma 4.

---

<a name="english"></a>
## English

**Tarilka** ("plate" in Ukrainian) is a free, open-source, offline food and weight tracker.

- Meal diary with daily totals of calories, protein, fat, carbs and fiber.
- Built-in database of 226 foods in Ukrainian and English, including Ukrainian dishes; the source of every value is shown.
- Take a photo of your plate: Gemma 4 runs on the phone, recognizes the foods and estimates portions. Nutrient values come from the database; you confirm the grams.
- Water and drinks with a one-tap "+250 ml".
- Optional steps (Health Connect or the phone's step sensor) and optional Health Connect sync of meals, drinks and weight.
- Weight log with chart and weekly trend.
- Targets (Mifflin–St Jeor) and simple, transparent recommendations, optionally rephrased by the AI.
- Everything stays on the device. Internet is only needed once to download the model.

Install the APK from [Releases](../../releases/latest). Enable AI in Settings → On-device AI.

License: MIT. Food data: USDA FoodData Central (public domain), typical labels and recipe calculations.
