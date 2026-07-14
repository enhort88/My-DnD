# Идеи для дальнейшего ускорения генерации

## Контекст
Базовый фикс уже сделан и запушен в ветку `claude` (коммит `b5ecaaa`):
pinned threadpool на быстрый CPU-кластер устройства + увеличенный
prefill-батч в Director-aware пути. Результат на реальном ходе:
~126с → ~72.5с за ход (в 1.7 раза быстрее), без потери качества.

## 1. KV-cache между ходами — СДЕЛАНО (2026-07-14)

Проверено на устройстве: ход 1 (miss, весь промпт) = decode 16.5с,
ход 2 (hit) = decode 9.5с/595 токенов, ход 3 (hit) = decode 10.3с/592
токена вместо полных ~1130. Найденный по пути баг ("cache HIT молча
проваливался в MISS") был не про сравнение текста, а про то, что
KV-cache между ходами хранит ВСЮ предыдущую генерацию (tool-calls,
ответы, нарратив), а не только статический префикс — implicit position
tracking `llama_decode`/`llama_batch_get_one` продолжал считать с конца
прошлого хода, а не с границы префикса, и второй 128-чанк хвоста
вылетал за `n_ctx`. Фикс: `llama_memory_seq_rm(mem, 0, cached_prefix_tokens, -1)`
перед декодом нового хвоста — обрезает кэш до границы префикса.

### Что сделано
- `MyDndLlamaHandle` (native_bridge.cpp): добавлены поля
  `cached_static_prefix` (std::string) и `cached_static_prefix_tokens` (int).
- Новая функция `mydnd_clear_memory(handle)` — ЕДИНСТВЕННОЕ место, где
  теперь чистится KV-cache; попутно сбрасывает `cached_static_prefix`.
  Все 4 исходных сырых вызова `llama_memory_clear(...)` в файле заменены
  на неё (в nativeGenerate, nativeGenerateStream x2, nativeGenerateDirectorAwareStream).
- В `nativeGenerateDirectorAwareStream`: вместо безусловной очистки +
  полного decode промпта каждый раз — пытаемся переиспользовать
  KV-cache статического префикса ("SYSTEM:"+DirectorToolSpec.compactRules(mode)+declaration(),
  доказано byte-identical для одного mode — см. DirectorToolSpec.java,
  чистые функции без параметров кроме mode) и декодировать только
  динамический хвост (CURRENT_SCENE и далее).
- Три новых tokenize-хелпера: `tokenize_plain_text` (без BOS, без
  спецтокенов — уже использовался в пункте 3 перф-листа),
  `tokenize_continuation_text` (без BOS, СО спецтокенами — для
  динамического хвоста), `tokenize_new_sequence_text` (С BOS, СО
  спецтокенами — для префикса при cache-miss).
- Self-check на границе: если раздельная токенизация
  (prefix_tokens.size()+tail_tokens.size()) не совпадает с целой
  токенизацией всего prompt (n_prompt) — это доказательство артефакта
  токенизации на границе, кэш НЕ используется и НЕ сохраняется в этот
  раз (лог "prefix/tail split mismatch", полный safe fallback).

### Найденные по пути ловушки (на будущее, если снова трогать этот код)
1. **Маркер границы static/dynamic** — не тот, что в `PromptBuilder.java`
   (`"\n\nCURRENT_SCENE:\n"`). `MainActivity.prepareMasterPrompt()`
   переупаковывает сырой prompt в chat-turn разметку
   (`<|turn>system\n...<turn|>\n<|turn>user\nCURRENT_SCENE:...`) ДО того,
   как строка попадает в нативный код через JNI, и `.trim()` там съедает
   `"\n\n"` перед CURRENT_SCENE. Маркер в native_bridge.cpp — просто
   `"CURRENT_SCENE:"`.
2. **KV-cache хранит весь предыдущий ход, не только префикс** —
   `llama_decode`/`llama_batch_get_one`(pos=nullptr) продолжают отсчёт
   позиции от конца ПРОШЛОГО хода (tool-calls+ответы+нарратив), а не от
   границы кэшированного префикса. Без явного отката (`llama_memory_seq_rm`)
   новый хвост уезжает за `n_ctx` на 2-3 чанке и decode тихо падает,
   откатываясь на полный fallback без видимой ошибки в игре.

## 2. Меньше впустую потраченных попыток Режиссёра — ЧАСТИЧНО СДЕЛАНО
См. коммит `339560d` (кросс-типовой дедуп фактов, `DUPLICATE_FACT_DIFFERENT_TYPE`
форсирует `DONE_ONLY`) — не полное решение (не ловит переформулировки
другими словами), но реальный прогресс без изменения промпта.

## 3. Батчить принудительные (grammar-forced) токены — СДЕЛАНО
Коммит `68a0583`. Общий префикс `<|tool_call>call:director_action{type:"`
декодируется одним батчем до создания grammar-сэмплера; `DONE`-ответ
(нет реального выбора модели вообще) собирается одним batch-декодом без
сэмплера. Проверено на устройстве — парсинг корректен, крашей нет.

## 4. ARM-оптимизированный формат кванта — ГОТОВО, действий не требуется
Проверено 2026-07-14: текущий llama.cpp заменил офлайн-типы
`Q4_0_4_8`/`Q4_0_8_8` автоматическим runtime repack в `ggml-cpu/repack.cpp`
(включается сам при обнаружении i8mm на CPU — подтверждено в
`/proc/cpuinfo` телефона, и `CMakeLists.txt` уже собирает под
`armv8.6-a+dotprod+i8mm`). Ничего перегонять не нужно.
