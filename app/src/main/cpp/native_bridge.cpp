#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <stdexcept>

#include "llama.h"
#include <android/log.h>
#include <atomic>
#include <ctime>
#include <chrono>
#include <algorithm>

#define MYDND_LOG_TAG "MyDND_NATIVE"
#define MYDND_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MYDND_LOG_TAG, __VA_ARGS__)
#define MYDND_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MYDND_LOG_TAG, __VA_ARGS__)

static void log_long_text(
        const char * label,
        const char * text
) {
    if (text == nullptr
        || text[0] == '\0') {

        MYDND_LOGI(
                "%s: <EMPTY>",
                label
        );

        return;
    }


    std::string value(
            text
    );


    constexpr size_t chunk_size =
            3000;


    for (
            size_t start = 0;
            start < value.size();
            start += chunk_size
            ) {

        size_t length =
                std::min(
                        chunk_size,
                        value.size() - start
                );


        std::string chunk =
                value.substr(
                        start,
                        length
                );


        MYDND_LOGI(
                "%s [%zu..%zu]:\n%s",
                label,
                start,
                start + length,
                chunk.c_str()
        );
    }
}

struct MyDndLlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex mutex;
    std::atomic_bool cancel_requested{false};
};

static bool ends_with_sentence_mark(const std::string & text) {
    if (text.empty()) {
        return false;
    }

    char last = text.back();

    return last == '.' ||
           last == '!' ||
           last == '?';
}

static bool ends_with_text(
        const std::string & text,
        const std::string & suffix
) {
    if (text.size() < suffix.size()) {
        return false;
    }

    return text.compare(
            text.size() - suffix.size(),
            suffix.size(),
            suffix
    ) == 0;
}



static bool is_complete_inventory_tool_call(
        const std::string & text
) {
    if (text.empty()) {
        return false;
    }

    if (text.find("<|tool_call>")
        != std::string::npos) {

        return text.find("<tool_call|>")
               != std::string::npos;
    }

    const bool known_short_call =
            text.find("add_item_to_inventory{")
                    != std::string::npos
            || text.find("remove_item_from_inventory{")
                    != std::string::npos
            || text.find("no_inventory_change{")
                    != std::string::npos;

    if (!known_short_call) {
        return false;
    }

    size_t end = text.find_last_not_of(
            " \t\r\n"
    );

    return end != std::string::npos
           && text[end] == '}';
}


static llama_sampler * create_sampler(
        float temperature,
        float top_p,
        int top_k,
        float repeat_penalty
) {
    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();

    sampler_params.no_perf = true;

    llama_sampler * sampler =
            llama_sampler_chain_init(
                    sampler_params
            );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(
                    96,
                    repeat_penalty,
                    0.20f,
                    0.10f
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_k(
                    top_k
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_p(
                    top_p,
                    1
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(
                    temperature
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(
                    static_cast<uint32_t>(
                            time(nullptr)
                    )
            )
    );

    return sampler;
}


static const char * MYDND_METADATA_GRAMMAR =
        R"GBNF(
root ::= no-change | item-player | item-world

no-change ::= "{\"type\":\"NONE\"}\n\n"

item-player ::= "{\"type\":\"ITEM\",\"holder\":\"PLAYER\",\"name\":\"" item-name "\"}\n\n"

item-world ::= "{\"type\":\"ITEM\",\"holder\":\"WORLD\",\"name\":\"" item-name "\"}\n\n"

item-name ::= [^"\r\n]{1,80}
)GBNF";

static bool g_backend_initialized = false;
static std::mutex g_backend_mutex;

static std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }


    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
static size_t complete_utf8_prefix_length(
        const std::string & text
) {
    if (text.empty()) {
        return 0;
    }

    size_t position =
            text.size();

    size_t continuation_count =
            0;

    // Идём с конца и считаем байты вида 10xxxxxx.
    while (position > 0) {

        unsigned char byte =
                static_cast<unsigned char>(
                        text[position - 1]
                );

        if ((byte & 0xC0) == 0x80) {

            continuation_count++;

            position--;

            continue;
        }

        size_t expected_length;

        if ((byte & 0x80) == 0x00) {

            expected_length = 1;

        } else if (
                (byte & 0xE0) == 0xC0
                ) {

            expected_length = 2;

        } else if (
                (byte & 0xF0) == 0xE0
                ) {

            expected_length = 3;

        } else if (
                (byte & 0xF8) == 0xF0
                ) {

            expected_length = 4;

        } else {

            // Неожиданный байт.
            // Отдаём Java всё как есть:
            // UTF-8 decoder обработает его безопаснее,
            // чем NewStringUTF.
            return text.size();
        }

        size_t actual_length =
                1 + continuation_count;

        if (actual_length
            < expected_length) {

            // Последний символ ещё не закончен.
            // Возвращаем всё ДО его первого байта.
            return position - 1;
        }

        return text.size();
    }

    // В буфере только continuation bytes.
    // Ждём следующий кусок.
    return 0;
}

static jstring string_to_jstring(
        JNIEnv * env,
        const std::string & value
) {
    jbyteArray bytes =
            env->NewByteArray(
                    static_cast<jsize>(
                            value.size()
                    )
            );

    if (bytes == nullptr) {
        return nullptr;
    }

    if (!value.empty()) {
        env->SetByteArrayRegion(
                bytes,
                0,
                static_cast<jsize>(
                        value.size()
                ),
                reinterpret_cast<
                        const jbyte *
                        >(
                        value.data()
                )
        );
    }

    jclass stringClass =
            env->FindClass(
                    "java/lang/String"
            );

    if (stringClass == nullptr) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }

    jmethodID constructor =
            env->GetMethodID(
                    stringClass,
                    "<init>",
                    "([BLjava/lang/String;)V"
            );

    if (constructor == nullptr) {
        env->DeleteLocalRef(
                stringClass
        );

        env->DeleteLocalRef(
                bytes
        );

        return nullptr;
    }

    jstring charsetName =
            env->NewStringUTF(
                    "UTF-8"
            );

    jobject result =
            env->NewObject(
                    stringClass,
                    constructor,
                    bytes,
                    charsetName
            );

    env->DeleteLocalRef(
            charsetName
    );

    env->DeleteLocalRef(
            stringClass
    );

    env->DeleteLocalRef(
            bytes
    );

    return static_cast<jstring>(
            result
    );
}

static void ensure_backend_initialized() {
    std::lock_guard<std::mutex> lock(g_backend_mutex);

    if (!g_backend_initialized) {
        llama_backend_init();
        ggml_backend_load_all();
        g_backend_initialized = true;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativePing(
        JNIEnv * env,
        jobject /* this */) {

    ensure_backend_initialized();

    std::string message = "JNI OK: llama.cpp готов к загрузке модели";
    return string_to_jstring(env, message);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeLoadModel(
        JNIEnv * env,
        jobject /* this */,
        jstring modelPath) {

    try {
        ensure_backend_initialized();

        std::string path = jstring_to_string(env, modelPath);

        llama_model_params model_params = llama_model_default_params();

        // Для первого MVP грузим на CPU. GPU/offload позже настроим отдельно.
        model_params.n_gpu_layers = 0;
        model_params.use_mmap = true;
        model_params.use_mlock = false;

        llama_model * model = llama_model_load_from_file(path.c_str(), model_params);

        char model_description[512] = {0};


        llama_model_desc(
                model,
                model_description,
                sizeof(model_description)
        );


        MYDND_LOGI(
                "MODEL DESCRIPTION: %s",
                model_description
        );


        const char * chat_template =
                llama_model_chat_template(
                        model,
                        nullptr
                );


        if (chat_template == nullptr) {

            MYDND_LOGE(
                    "CHAT TEMPLATE: NOT FOUND IN GGUF"
            );

        } else {

            MYDND_LOGI(
                    "CHAT TEMPLATE: FOUND"
            );
        }

        if (model == nullptr) {
            return 0;
        }

        llama_context_params ctx_params = llama_context_default_params();

        // Для телефона сначала скромный контекст.
        ctx_params.n_ctx = 2048;
        ctx_params.n_batch = 512;
        ctx_params.n_ubatch = 128;
        ctx_params.n_threads = 4;
        ctx_params.n_threads_batch = 4;
        ctx_params.no_perf = true;

        llama_context * ctx = llama_init_from_model(model, ctx_params);

        if (ctx == nullptr) {
            llama_model_free(model);
            return 0;
        }

        MyDndLlamaHandle * handle = new MyDndLlamaHandle();
        handle->model = model;
        handle->ctx = ctx;
        handle->vocab = llama_model_get_vocab(model);

        return reinterpret_cast<jlong>(handle);
    } catch (...) {
        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeCancel(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong nativeHandle) {

    if (nativeHandle == 0) {
        return;
    }

    MyDndLlamaHandle * handle =
            reinterpret_cast<MyDndLlamaHandle *>(nativeHandle);

    handle->cancel_requested.store(true);

    MYDND_LOGI("nativeCancel: cancel requested");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeGenerate(
        JNIEnv * env,
        jobject /* this */,
        jlong nativeHandle,
        jstring promptText,
        jint maxTokens) { MYDND_LOGI("nativeGenerate: ENTER");


    if (nativeHandle == 0) {
        return string_to_jstring(env, "Ошибка: модель не загружена.");
    }
//    MYDND_LOGI("nativeGenerate: handle OK");

    MyDndLlamaHandle * handle =
            reinterpret_cast<MyDndLlamaHandle *>(nativeHandle);

    std::lock_guard<std::mutex> lock(handle->mutex);

    try {
        std::string prompt = jstring_to_string(env, promptText);
//        MYDND_LOGI("nativeGenerate: before count tokenize");
        if (prompt.empty()) {
            return string_to_jstring(env, "");
        }

        const int n_predict = maxTokens > 0 ? maxTokens : 160;

        // Очищаем память контекста перед новым запросом.
        llama_memory_clear(llama_get_memory(handle->ctx), true);

        // Считаем число токенов промпта.
//        MYDND_LOGI("nativeGenerate: before count tokenize");
        int n_prompt = -llama_tokenize(
                handle->vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                nullptr,
                0,
                true,
                true
        );

        if (n_prompt <= 0) {
            return string_to_jstring(env, "Ошибка: не удалось токенизировать prompt.");
        }

        const uint32_t n_ctx = llama_n_ctx(handle->ctx);

        if (static_cast<uint32_t>(n_prompt + n_predict) >= n_ctx) {
            return string_to_jstring(
                    env,
                    "Ошибка: prompt слишком длинный для текущего контекста."
            );
        }

        std::vector<llama_token> prompt_tokens(n_prompt);

//        MYDND_LOGI("nativeGenerate: before real tokenize");
        int tokenized = llama_tokenize(
                handle->vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size()),
                true,
                true
        );
        MYDND_LOGI("nativeGenerate: tokenized = %d", tokenized);

        if (tokenized < 0) {
            return string_to_jstring(env, "Ошибка: tokenizer вернул отрицательный результат.");
        }

        llama_sampler_chain_params sampler_params =
                llama_sampler_chain_default_params();

        sampler_params.no_perf = true;

        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);

// Антиповторы.
// last_n — сколько последних токенов учитывать.
// repeat_penalty > 1.0 наказывает повтор.
// freq/present penalty дополнительно давят зацикливание.
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_penalties(
                        96,     // last_n
                        1.18f,  // repeat_penalty
                        0.20f,  // frequency_penalty
                        0.10f   // presence_penalty
                )
        );

// Нормальная выборка вместо greedy.
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.75f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) time(nullptr)));

        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size())
        );

//        MYDND_LOGI("nativeGenerate: before prompt decode");
        if (llama_decode(handle->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            return string_to_jstring(env, "Ошибка: llama_decode не смог обработать prompt.");
        }
        if (handle->cancel_requested.load()) {
            llama_sampler_free(sampler);
            MYDND_LOGI(
                    "nativeGenerate: cancelled after prompt decode"
            );
            return string_to_jstring(env, "");
        }
//        MYDND_LOGI("nativeGenerate: after prompt decode");


        std::string output;
        llama_token new_token_id;

//        MYDND_LOGI("nativeGenerate: start token loop, n_predict = %d", n_predict);
        for (int i = 0; i < n_predict; i++) {
//            MYDND_LOGI("nativeGenerate: token loop i = %d", i);
            new_token_id = llama_sampler_sample(sampler, handle->ctx, -1);

            if (llama_vocab_is_eog(handle->vocab, new_token_id)) {
                break;
            }

            char buffer[256];

            int piece_length = llama_token_to_piece(
                    handle->vocab,
                    new_token_id,
                    buffer,
                    sizeof(buffer),
                    0,
                    true
            );

            if (piece_length > 0) {
                output.append(buffer, piece_length);
            }

            batch = llama_batch_get_one(&new_token_id, 1);

            if (llama_decode(handle->ctx, batch) != 0) {
                break;
            }
        }

        llama_sampler_free(sampler);
        MYDND_LOGI("nativeGenerate: FINISH, output length = %zu", output.size());
        return string_to_jstring(env, output);
    } catch (const std::exception & ex) {
        return string_to_jstring(env, std::string("Ошибка nativeGenerate: ") + ex.what());
    } catch (...) {
        return string_to_jstring(env, "Ошибка nativeGenerate: неизвестная ошибка.");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeGenerateStream(
        JNIEnv * env,
        jobject /* this */,
        jlong nativeHandle,
        jstring promptText,
        jstring metadataPromptText,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty,
        jboolean useMetadataPhase,
        jobject callbackObject) {

    MYDND_LOGI("nativeGenerateStream: ENTER");

    if (nativeHandle == 0) {
        return string_to_jstring(env, "Ошибка: модель не загружена.");
    }

    if (callbackObject == nullptr) {
        return string_to_jstring(env, "Ошибка: callbackObject == nullptr.");
    }

    jclass callbackClass = env->GetObjectClass(callbackObject);
    if (callbackClass == nullptr) {
        return string_to_jstring(env, "Ошибка: не найден class callbackObject.");
    }

    jmethodID onTokenMethod = env->GetMethodID(
            callbackClass,
            "onToken",
            "(Ljava/lang/String;)V"
    );

    if (onTokenMethod == nullptr) {
        return string_to_jstring(env, "Ошибка: не найден метод onToken(String).");
    }

    MyDndLlamaHandle * handle =
            reinterpret_cast<MyDndLlamaHandle *>(nativeHandle);

    std::lock_guard<std::mutex> lock(handle->mutex);

    handle->cancel_requested.store(false);

    try {
        std::string prompt = jstring_to_string(env, promptText);
        std::string metadata_prompt =
                jstring_to_string(env, metadataPromptText);

        if (prompt.empty()) {
            return string_to_jstring(env, "");
        }

        const int n_predict = maxTokens > 0 ? maxTokens : 80;

        const int hard_max_tokens = n_predict + 60;

        llama_memory_clear(llama_get_memory(handle->ctx), true);

        int n_prompt = -llama_tokenize(
                handle->vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                nullptr,
                0,
                true,
                true
        );

        if (n_prompt <= 0) {
            return string_to_jstring(env, "Ошибка: не удалось токенизировать prompt.");
        }

        const uint32_t n_ctx = llama_n_ctx(handle->ctx);

        const int metadata_max_tokens = 64;

        if (static_cast<uint32_t>(
                    n_prompt
                    + hard_max_tokens
            ) >= n_ctx) {
            MYDND_LOGE(
                    "nativeGenerateStream: context overflow, prompt=%d, output=%d, ctx=%u",
                    n_prompt,
                    hard_max_tokens,
                    n_ctx
            );

            return string_to_jstring(
                    env,
                    "Ошибка: prompt и ответ не помещаются в текущий контекст модели."
            );
        }

        std::vector<llama_token> prompt_tokens(n_prompt);

        int tokenized = llama_tokenize(
                handle->vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size()),
                true,
                true
        );

        MYDND_LOGI("nativeGenerateStream: tokenized = %d", tokenized);

        if (tokenized < 0) {
            return string_to_jstring(env, "Ошибка: tokenizer вернул отрицательный результат.");
        }

        llama_sampler_chain_params sampler_params =
                llama_sampler_chain_default_params();

        sampler_params.no_perf = true;

        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);

// Антиповторы.
// last_n — сколько последних токенов учитывать.
// repeat_penalty > 1.0 наказывает повтор.
// freq/present penalty дополнительно давят зацикливание.
        const float safe_temperature =
                temperature > 0.0f
                ? temperature
                : 0.75f;

        const float safe_top_p =
                topP > 0.0f && topP <= 1.0f
                ? topP
                : 0.90f;

        const int safe_top_k =
                topK > 0
                ? topK
                : 40;

        const float safe_repeat_penalty =
                repeatPenalty >= 1.0f
                ? repeatPenalty
                : 1.18f;

        MYDND_LOGI(
                "nativeGenerateStream: sampler temp=%.2f, topP=%.2f, topK=%d, repeat=%.2f",
                safe_temperature,
                safe_top_p,
                safe_top_k,
                safe_repeat_penalty
        );

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_penalties(
                        96,
                        safe_repeat_penalty,
                        0.20f,
                        0.10f
                )
        );

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_top_k(
                        safe_top_k
                )
        );

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_top_p(
                        safe_top_p,
                        1
                )
        );

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_temp(
                        safe_temperature
                )
        );

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_dist(
                        static_cast<uint32_t>(time(nullptr))
                )
        );


        auto prompt_decode_start =
                std::chrono::steady_clock::now();

        const int prompt_chunk_size = 16;

        for (int pos = 0; pos < tokenized; pos += prompt_chunk_size) {
            if (handle->cancel_requested.load()) {
                llama_sampler_free(sampler);
                MYDND_LOGI("nativeGenerateStream: cancelled during prompt decode, pos = %d", pos);
                return string_to_jstring(env, "");
            }

            int chunk_size = prompt_chunk_size;

            if (pos + chunk_size > tokenized) {
                chunk_size = tokenized - pos;
            }

            llama_batch batch = llama_batch_get_one(
                    prompt_tokens.data() + pos,
                    chunk_size
            );

            if (llama_decode(handle->ctx, batch) != 0) {
                llama_sampler_free(sampler);
                MYDND_LOGE("nativeGenerateStream: prompt decode failed, pos = %d, chunk_size = %d", pos, chunk_size);

                return string_to_jstring(env, "Ошибка: llama_decode не смог обработать часть prompt.");
            }
        }
        auto prompt_decode_end =
                std::chrono::steady_clock::now();

        auto prompt_decode_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                        prompt_decode_end - prompt_decode_start
                ).count();
        MYDND_LOGI(
                "nativeGenerateStream: prompt decode = %lld ms",
                static_cast<long long>(prompt_decode_ms)
        );

        if (handle->cancel_requested.load()) {
            llama_sampler_free(sampler);
            MYDND_LOGI(
                    "nativeGenerateStream: cancelled after prompt decode"
            );
            return string_to_jstring(env, "");
        }

        std::string metadata_output;

        std::string output;

        // Буфер для байтов незаконченного UTF-8 символа между токенами.
        std::string streaming_pending;

        llama_token new_token_id;

        const int soft_min_tokens = n_predict * 3 / 4;

// Начинаем замер именно генерации ответа.
        auto generation_start =
                std::chrono::steady_clock::now();

        int generated_tokens = 0;

        for (int i = 0; i < hard_max_tokens; i++) {
            if (handle->cancel_requested.load()) {
                MYDND_LOGI(
                        "nativeGenerateStream: cancelled in token loop, i = %d",
                        i
                );
                break;
            }

            new_token_id =
                    llama_sampler_sample(
                            sampler,
                            handle->ctx,
                            -1
                    );

            if (llama_vocab_is_eog(
                    handle->vocab,
                    new_token_id
            )) {
                break;
            }

            // Токен реально сгенерирован.
            generated_tokens++;

            char buffer[256];

            int piece_length =
                    llama_token_to_piece(
                            handle->vocab,
                            new_token_id,
                            buffer,
                            sizeof(buffer),
                            0,
                            true
                    );

            if (piece_length > 0) {

                std::string piece(
                        buffer,
                        piece_length
                );

                // Полный ответ сохраняем как раньше.
                output.append(
                        piece
                );

                // Для streaming сначала накапливаем байты.
                streaming_pending.append(
                        piece
                );

                size_t ready_length =
                        complete_utf8_prefix_length(
                                streaming_pending
                        );

                if (ready_length > 0) {

                    std::string ready_text =
                            streaming_pending.substr(
                                    0,
                                    ready_length
                            );

                    streaming_pending.erase(
                            0,
                            ready_length
                    );

                    jstring jToken =
                            string_to_jstring(
                                    env,
                                    ready_text
                            );

                    if (jToken != nullptr) {

                        env->CallVoidMethod(
                                callbackObject,
                                onTokenMethod,
                                jToken
                        );

                        env->DeleteLocalRef(
                                jToken
                        );
                    }

                    if (env->ExceptionCheck()) {

                        env->ExceptionDescribe();

                        env->ExceptionClear();

                        break;
                    }
                }
            }

            if (i >= soft_min_tokens
                && ends_with_sentence_mark(output)) {
                break;
            }

            llama_batch batch =
                    llama_batch_get_one(
                            &new_token_id,
                            1
                    );

            if (llama_decode(handle->ctx, batch) != 0) {
                break;
            }
        }

// Закончили замер генерации.
        auto generation_end =
                std::chrono::steady_clock::now();

        auto generation_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                        generation_end - generation_start
                ).count();

        double tokens_per_second = 0.0;

        if (generation_ms > 0) {
            tokens_per_second =
                    generated_tokens * 1000.0
                    / static_cast<double>(generation_ms);
        }

        MYDND_LOGI(
                "nativeGenerateStream: generation = %lld ms, tokens = %d, speed = %.2f tok/s",
                static_cast<long long>(generation_ms),
                generated_tokens,
                tokens_per_second
        );

        llama_sampler_free(sampler);

        /*
         * Изолированный metadata-pass.
         *
         * К этому моменту художественный ответ уже полностью сгенерирован
         * и отправлен пользователю через streaming callback.
         *
         * Очищаем только память context, модель остаётся загруженной.
         * Затем декодируем короткий metadata prompt, который содержит
         * только текущее действие игрока.
         */
        if (useMetadataPhase == JNI_TRUE
            && !metadata_prompt.empty()
            && !handle->cancel_requested.load()) {

            MYDND_LOGI(
                    "nativeGenerateStream: isolated metadata START"
            );

            llama_memory_clear(
                    llama_get_memory(handle->ctx),
                    true
            );

            int metadata_prompt_count =
                    -llama_tokenize(
                            handle->vocab,
                            metadata_prompt.c_str(),
                            static_cast<int32_t>(metadata_prompt.size()),
                            nullptr,
                            0,
                            true,
                            true
                    );

            if (metadata_prompt_count > 0
                && static_cast<uint32_t>(
                           metadata_prompt_count
                           + metadata_max_tokens
                   ) < n_ctx) {

                std::vector<llama_token> metadata_prompt_tokens(
                        metadata_prompt_count
                );

                int metadata_tokenized =
                        llama_tokenize(
                                handle->vocab,
                                metadata_prompt.c_str(),
                                static_cast<int32_t>(metadata_prompt.size()),
                                metadata_prompt_tokens.data(),
                                static_cast<int32_t>(
                                        metadata_prompt_tokens.size()
                                ),
                                true,
                                true
                        );

                MYDND_LOGI(
                        "nativeGenerateStream: metadata prompt tokenized = %d",
                        metadata_tokenized
                );

                if (metadata_tokenized > 0) {

                    auto metadata_decode_start =
                            std::chrono::steady_clock::now();

                    bool metadata_decode_ok = true;

                    for (
                            int pos = 0;
                            pos < metadata_tokenized;
                            pos += prompt_chunk_size
                            ) {

                        if (handle->cancel_requested.load()) {
                            metadata_decode_ok = false;
                            break;
                        }

                        int chunk_size = prompt_chunk_size;

                        if (pos + chunk_size > metadata_tokenized) {
                            chunk_size = metadata_tokenized - pos;
                        }

                        llama_batch metadata_prompt_batch =
                                llama_batch_get_one(
                                        metadata_prompt_tokens.data() + pos,
                                        chunk_size
                                );

                        if (llama_decode(
                                handle->ctx,
                                metadata_prompt_batch
                        ) != 0) {

                            metadata_decode_ok = false;

                            MYDND_LOGE(
                                    "nativeGenerateStream: metadata prompt decode failed, pos=%d",
                                    pos
                            );

                            break;
                        }
                    }

                    auto metadata_decode_end =
                            std::chrono::steady_clock::now();

                    auto metadata_decode_ms =
                            std::chrono::duration_cast<
                                    std::chrono::milliseconds
                            >(
                                    metadata_decode_end
                                    - metadata_decode_start
                            ).count();

                    MYDND_LOGI(
                            "nativeGenerateStream: metadata prompt decode = %lld ms",
                            static_cast<long long>(
                                    metadata_decode_ms
                            )
                    );

                    if (metadata_decode_ok
                        && !handle->cancel_requested.load()) {

                        llama_sampler_chain_params metadata_params =
                                llama_sampler_chain_default_params();

                        metadata_params.no_perf = true;

                        llama_sampler * metadata_sampler =
                                llama_sampler_chain_init(
                                        metadata_params
                                );

                        llama_sampler * grammar_sampler =
                                llama_sampler_init_grammar(
                                        handle->vocab,
                                        MYDND_METADATA_GRAMMAR,
                                        "root"
                                );

                        if (grammar_sampler != nullptr) {

                            llama_sampler_chain_add(
                                    metadata_sampler,
                                    grammar_sampler
                            );

                            llama_sampler_chain_add(
                                    metadata_sampler,
                                    llama_sampler_init_top_k(20)
                            );

                            llama_sampler_chain_add(
                                    metadata_sampler,
                                    llama_sampler_init_top_p(
                                            0.90f,
                                            1
                                    )
                            );

                            llama_sampler_chain_add(
                                    metadata_sampler,
                                    llama_sampler_init_temp(0.20f)
                            );

                            llama_sampler_chain_add(
                                    metadata_sampler,
                                    llama_sampler_init_dist(
                                            static_cast<uint32_t>(
                                                    time(nullptr)
                                            )
                                    )
                            );

                            auto metadata_generation_start =
                                    std::chrono::steady_clock::now();

                            int metadata_tokens = 0;
                            bool metadata_complete = false;

                            for (
                                    int i = 0;
                                    i < metadata_max_tokens;
                                    i++
                                    ) {

                                if (handle->cancel_requested.load()) {
                                    break;
                                }

                                llama_token metadata_token =
                                        llama_sampler_sample(
                                                metadata_sampler,
                                                handle->ctx,
                                                -1
                                        );

                                if (llama_vocab_is_eog(
                                        handle->vocab,
                                        metadata_token
                                )) {
                                    break;
                                }

                                metadata_tokens++;

                                char metadata_buffer[256];

                                int metadata_piece_length =
                                        llama_token_to_piece(
                                                handle->vocab,
                                                metadata_token,
                                                metadata_buffer,
                                                sizeof(metadata_buffer),
                                                0,
                                                true
                                        );

                                if (metadata_piece_length > 0) {
                                    metadata_output.append(
                                            metadata_buffer,
                                            metadata_piece_length
                                    );
                                }

                                llama_batch metadata_batch =
                                        llama_batch_get_one(
                                                &metadata_token,
                                                1
                                        );

                                if (llama_decode(
                                        handle->ctx,
                                        metadata_batch
                                ) != 0) {

                                    MYDND_LOGE(
                                            "nativeGenerateStream: isolated metadata decode failed"
                                    );

                                    break;
                                }

                                if (ends_with_text(
                                        metadata_output,
                                        "\n\n"
                                )) {

                                    metadata_complete = true;
                                    break;
                                }
                            }

                            auto metadata_generation_end =
                                    std::chrono::steady_clock::now();

                            auto metadata_generation_ms =
                                    std::chrono::duration_cast<
                                            std::chrono::milliseconds
                                    >(
                                            metadata_generation_end
                                            - metadata_generation_start
                                    ).count();

                            MYDND_LOGI(
                                    "nativeGenerateStream: isolated metadata generation = %lld ms, tokens = %d",
                                    static_cast<long long>(
                                            metadata_generation_ms
                                    ),
                                    metadata_tokens
                            );

                            MYDND_LOGI(
                                    "nativeGenerateStream: metadata RAW = %s",
                                    metadata_output.c_str()
                            );

                            if (!metadata_complete) {
                                MYDND_LOGE(
                                        "nativeGenerateStream: isolated metadata incomplete"
                                );
                            }

                        } else {

                            MYDND_LOGE(
                                    "nativeGenerateStream: grammar init failed"
                            );
                        }

                        llama_sampler_free(
                                metadata_sampler
                        );
                    }
                }

            } else {

                MYDND_LOGE(
                        "nativeGenerateStream: invalid metadata prompt, tokens=%d",
                        metadata_prompt_count
                );
            }

            MYDND_LOGI(
                    "nativeGenerateStream: isolated metadata FINISH"
            );
        }

        std::string full_output =
                metadata_output
                + output;

        MYDND_LOGI(
                "nativeGenerateStream: FINISH"
                ", metadata length = %zu"
                ", narrative length = %zu",
                metadata_output.size(),
                output.size()
        );

        return string_to_jstring(
                env,
                full_output
        );
    } catch (const std::exception & ex) {
        return string_to_jstring(env, std::string("Ошибка nativeGenerateStream: ") + ex.what());
    } catch (...) {
        return string_to_jstring(env, "Ошибка nativeGenerateStream: неизвестная ошибка.");
    }
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeGenerateInventoryToolAwareStream(
        JNIEnv * env,
        jobject /* this */,
        jlong nativeHandle,
        jstring promptText,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty,
        jobject tokenCallbackObject,
        jobject toolCallbackObject) {

    MYDND_LOGI(
            "nativeGenerateInventoryToolAwareStream: ENTER"
    );

    if (nativeHandle == 0) {
        return string_to_jstring(
                env,
                "Ошибка: модель не загружена."
        );
    }

    if (tokenCallbackObject == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: tokenCallbackObject == nullptr."
        );
    }

    if (toolCallbackObject == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: toolCallbackObject == nullptr."
        );
    }

    jclass tokenCallbackClass =
            env->GetObjectClass(
                    tokenCallbackObject
            );

    if (tokenCallbackClass == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: не найден class tokenCallbackObject."
        );
    }

    jmethodID onTokenMethod =
            env->GetMethodID(
                    tokenCallbackClass,
                    "onToken",
                    "(Ljava/lang/String;)V"
            );

    if (onTokenMethod == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: не найден метод onToken(String)."
        );
    }

    jclass toolCallbackClass =
            env->GetObjectClass(
                    toolCallbackObject
            );

    if (toolCallbackClass == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: не найден class toolCallbackObject."
        );
    }

    jmethodID onToolCallMethod =
            env->GetMethodID(
                    toolCallbackClass,
                    "onToolCall",
                    "(Ljava/lang/String;)Ljava/lang/String;"
            );

    if (onToolCallMethod == nullptr) {
        return string_to_jstring(
                env,
                "Ошибка: не найден метод onToolCall(String)."
        );
    }

    MyDndLlamaHandle * handle =
            reinterpret_cast<MyDndLlamaHandle *>(
                    nativeHandle
            );

    std::lock_guard<std::mutex> lock(
            handle->mutex
    );

    handle->cancel_requested.store(
            false
    );

    try {
        std::string prompt =
                jstring_to_string(
                        env,
                        promptText
                );

        if (prompt.empty()) {
            return string_to_jstring(
                    env,
                    ""
            );
        }

        const int narrative_predict =
                maxTokens > 0
                        ? maxTokens
                        : 140;

        const int narrative_hard_max =
                narrative_predict + 60;

        const int decision_max_tokens =
                96;

        const int tool_response_reserve =
                192;

        const float safe_temperature =
                temperature > 0.0f
                        ? temperature
                        : 0.75f;

        const float safe_top_p =
                topP > 0.0f
                && topP <= 1.0f
                        ? topP
                        : 0.90f;

        const int safe_top_k =
                topK > 0
                        ? topK
                        : 40;

        const float safe_repeat_penalty =
                repeatPenalty >= 1.0f
                        ? repeatPenalty
                        : 1.12f;

        llama_memory_clear(
                llama_get_memory(
                        handle->ctx
                ),
                true
        );

        int n_prompt =
                -llama_tokenize(
                        handle->vocab,
                        prompt.c_str(),
                        static_cast<int32_t>(
                                prompt.size()
                        ),
                        nullptr,
                        0,
                        true,
                        true
                );

        if (n_prompt <= 0) {
            return string_to_jstring(
                    env,
                    "Ошибка: не удалось токенизировать prompt."
            );
        }

        const uint32_t n_ctx =
                llama_n_ctx(
                        handle->ctx
                );

        if (static_cast<uint32_t>(
                    n_prompt
                    + decision_max_tokens
                    + tool_response_reserve
                    + narrative_hard_max
            ) >= n_ctx) {

            MYDND_LOGE(
                    "nativeGenerateInventoryToolAwareStream: context overflow, prompt=%d, decision=%d, toolReserve=%d, narrative=%d, ctx=%u",
                    n_prompt,
                    decision_max_tokens,
                    tool_response_reserve,
                    narrative_hard_max,
                    n_ctx
            );

            return string_to_jstring(
                    env,
                    "Ошибка: tool-aware prompt и ответ не помещаются в контекст модели."
            );
        }

        std::vector<llama_token> prompt_tokens(
                n_prompt
        );

        int tokenized =
                llama_tokenize(
                        handle->vocab,
                        prompt.c_str(),
                        static_cast<int32_t>(
                                prompt.size()
                        ),
                        prompt_tokens.data(),
                        static_cast<int32_t>(
                                prompt_tokens.size()
                        ),
                        true,
                        true
                );

        if (tokenized < 0) {
            return string_to_jstring(
                    env,
                    "Ошибка: tokenizer вернул отрицательный результат."
            );
        }

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: prompt tokenized = %d",
                tokenized
        );

        const int prompt_chunk_size =
                16;

        auto prompt_decode_start =
                std::chrono::steady_clock::now();

        for (
                int pos = 0;
                pos < tokenized;
                pos += prompt_chunk_size
                ) {

            if (handle->cancel_requested.load()) {
                MYDND_LOGI(
                        "nativeGenerateInventoryToolAwareStream: cancelled during prompt decode, pos=%d",
                        pos
                );

                return string_to_jstring(
                        env,
                        ""
                );
            }

            int chunk_size =
                    prompt_chunk_size;

            if (pos + chunk_size > tokenized) {
                chunk_size =
                        tokenized - pos;
            }

            llama_batch batch =
                    llama_batch_get_one(
                            prompt_tokens.data() + pos,
                            chunk_size
                    );

            if (llama_decode(
                    handle->ctx,
                    batch
            ) != 0) {

                MYDND_LOGE(
                        "nativeGenerateInventoryToolAwareStream: prompt decode failed, pos=%d, chunk=%d",
                        pos,
                        chunk_size
                );

                return string_to_jstring(
                        env,
                        "Ошибка: llama_decode не смог обработать часть tool-aware prompt."
                );
            }
        }

        auto prompt_decode_end =
                std::chrono::steady_clock::now();

        auto prompt_decode_ms =
                std::chrono::duration_cast<
                        std::chrono::milliseconds
                >(
                        prompt_decode_end
                        - prompt_decode_start
                ).count();

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: ONE PASS prompt decode = %lld ms",
                static_cast<long long>(
                        prompt_decode_ms
                )
        );

        if (handle->cancel_requested.load()) {
            return string_to_jstring(
                    env,
                    ""
            );
        }

        /*
         * Фаза 1: короткое детерминированное решение о состоянии инвентаря.
         * Ничего из этой фазы пользователю не стримим.
         */
        llama_sampler * decision_sampler =
                create_sampler(
                        0.10f,
                        0.80f,
                        20,
                        1.00f
                );

        std::string decision_output;

        bool tool_call_complete =
                false;

        auto decision_start =
                std::chrono::steady_clock::now();

        int decision_tokens =
                0;

        for (
                int i = 0;
                i < decision_max_tokens;
                i++
                ) {

            if (handle->cancel_requested.load()) {
                break;
            }

            llama_token token =
                    llama_sampler_sample(
                            decision_sampler,
                            handle->ctx,
                            -1
                    );

            if (llama_vocab_is_eog(
                    handle->vocab,
                    token
            )) {
                break;
            }

            decision_tokens++;

            char buffer[256];

            int piece_length =
                    llama_token_to_piece(
                            handle->vocab,
                            token,
                            buffer,
                            sizeof(buffer),
                            0,
                            true
                    );

            if (piece_length > 0) {
                decision_output.append(
                        buffer,
                        piece_length
                );
            }

            llama_batch token_batch =
                    llama_batch_get_one(
                            &token,
                            1
                    );

            if (llama_decode(
                    handle->ctx,
                    token_batch
            ) != 0) {

                MYDND_LOGE(
                        "nativeGenerateInventoryToolAwareStream: decision token decode failed"
                );

                break;
            }

            if (is_complete_inventory_tool_call(
                    decision_output
            )) {
                tool_call_complete =
                        true;

                break;
            }
        }

        auto decision_end =
                std::chrono::steady_clock::now();

        auto decision_ms =
                std::chrono::duration_cast<
                        std::chrono::milliseconds
                >(
                        decision_end
                        - decision_start
                ).count();

        llama_sampler_free(
                decision_sampler
        );

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: decision = %lld ms, tokens=%d, tool=%s",
                static_cast<long long>(
                        decision_ms
                ),
                decision_tokens,
                tool_call_complete
                        ? "YES"
                        : "NO"
        );

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: TOOL RAW = %s",
                decision_output.c_str()
        );

        if (handle->cancel_requested.load()) {
            return string_to_jstring(
                    env,
                    ""
            );
        }

        if (!tool_call_complete) {
            /*
             * Безопасный fallback:
             * если модель нарушила протокол и сразу написала текст,
             * не ломаем игру и показываем его как обычный ответ.
             */
            MYDND_LOGE(
                    "nativeGenerateInventoryToolAwareStream: no complete tool call; fallback to direct output"
            );

            if (!decision_output.empty()) {
                jstring jText =
                        string_to_jstring(
                                env,
                                decision_output
                        );

                if (jText != nullptr) {
                    env->CallVoidMethod(
                            tokenCallbackObject,
                            onTokenMethod,
                            jText
                    );

                    env->DeleteLocalRef(
                            jText
                    );
                }
            }

            return string_to_jstring(
                    env,
                    decision_output
            );
        }

        /*
         * Фаза 2: синхронно отдаём raw tool call Java-коду.
         * Java валидирует команду, меняет Room и возвращает
         * уже готовый Gemma 4 <|tool_response>... блок.
         */
        jstring jToolCall =
                string_to_jstring(
                        env,
                        decision_output
                );

        jobject toolResponseObject =
                env->CallObjectMethod(
                        toolCallbackObject,
                        onToolCallMethod,
                        jToolCall
                );

        env->DeleteLocalRef(
                jToolCall
        );

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();

            return string_to_jstring(
                    env,
                    "Ошибка: Java tool callback завершился с исключением."
            );
        }

        if (toolResponseObject == nullptr) {
            return string_to_jstring(
                    env,
                    "Ошибка: Java tool callback вернул null."
            );
        }

        std::string tool_response =
                jstring_to_string(
                        env,
                        static_cast<jstring>(
                                toolResponseObject
                        )
                );

        env->DeleteLocalRef(
                toolResponseObject
        );

        if (tool_response.empty()) {
            return string_to_jstring(
                    env,
                    "Ошибка: Java tool callback вернул пустой ответ."
            );
        }

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: TOOL RESPONSE = %s",
                tool_response.c_str()
        );

        /*
         * Продолжаем ТОТ ЖЕ llama_context.
         * add_special=false: BOS в середине контекста не нужен.
         * parse_special=true: <|tool_response> должен стать special token.
         */
        int tool_response_count =
                -llama_tokenize(
                        handle->vocab,
                        tool_response.c_str(),
                        static_cast<int32_t>(
                                tool_response.size()
                        ),
                        nullptr,
                        0,
                        false,
                        true
                );

        if (tool_response_count <= 0) {
            return string_to_jstring(
                    env,
                    "Ошибка: не удалось токенизировать tool response."
            );
        }

        std::vector<llama_token> tool_response_tokens(
                tool_response_count
        );

        int tool_response_tokenized =
                llama_tokenize(
                        handle->vocab,
                        tool_response.c_str(),
                        static_cast<int32_t>(
                                tool_response.size()
                        ),
                        tool_response_tokens.data(),
                        static_cast<int32_t>(
                                tool_response_tokens.size()
                        ),
                        false,
                        true
                );

        if (tool_response_tokenized < 0) {
            return string_to_jstring(
                    env,
                    "Ошибка: tokenizer не обработал tool response."
            );
        }

        auto response_decode_start =
                std::chrono::steady_clock::now();

        for (
                int pos = 0;
                pos < tool_response_tokenized;
                pos += prompt_chunk_size
                ) {

            if (handle->cancel_requested.load()) {
                return string_to_jstring(
                        env,
                        ""
                );
            }

            int chunk_size =
                    prompt_chunk_size;

            if (pos + chunk_size
                > tool_response_tokenized) {

                chunk_size =
                        tool_response_tokenized
                        - pos;
            }

            llama_batch response_batch =
                    llama_batch_get_one(
                            tool_response_tokens.data()
                            + pos,
                            chunk_size
                    );

            if (llama_decode(
                    handle->ctx,
                    response_batch
            ) != 0) {

                MYDND_LOGE(
                        "nativeGenerateInventoryToolAwareStream: tool response decode failed, pos=%d",
                        pos
                );

                return string_to_jstring(
                        env,
                        "Ошибка: llama_decode не смог добавить tool response в текущий context."
                );
            }
        }

        auto response_decode_end =
                std::chrono::steady_clock::now();

        auto response_decode_ms =
                std::chrono::duration_cast<
                        std::chrono::milliseconds
                >(
                        response_decode_end
                        - response_decode_start
                ).count();

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: SAME CONTEXT tool response decode = %lld ms, tokens=%d",
                static_cast<long long>(
                        response_decode_ms
                ),
                tool_response_tokenized
        );

        /*
         * Фаза 3: художественное продолжение уже после результата Room.
         * Используем обычный narrative sampler и стримим только эту фазу.
         */
        llama_sampler * narrative_sampler =
                create_sampler(
                        safe_temperature,
                        safe_top_p,
                        safe_top_k,
                        safe_repeat_penalty
                );

        std::string narrative_output;
        std::string streaming_pending;

        const int soft_min_tokens =
                narrative_predict * 3 / 4;

        auto narrative_start =
                std::chrono::steady_clock::now();

        int narrative_tokens =
                0;

        for (
                int i = 0;
                i < narrative_hard_max;
                i++
                ) {

            if (handle->cancel_requested.load()) {
                break;
            }

            llama_token token =
                    llama_sampler_sample(
                            narrative_sampler,
                            handle->ctx,
                            -1
                    );

            if (llama_vocab_is_eog(
                    handle->vocab,
                    token
            )) {
                break;
            }

            narrative_tokens++;

            char buffer[256];

            int piece_length =
                    llama_token_to_piece(
                            handle->vocab,
                            token,
                            buffer,
                            sizeof(buffer),
                            0,
                            true
                    );

            if (piece_length > 0) {
                std::string piece(
                        buffer,
                        piece_length
                );

                narrative_output.append(
                        piece
                );

                streaming_pending.append(
                        piece
                );

                size_t ready_length =
                        complete_utf8_prefix_length(
                                streaming_pending
                        );

                if (ready_length > 0) {
                    std::string ready_text =
                            streaming_pending.substr(
                                    0,
                                    ready_length
                            );

                    streaming_pending.erase(
                            0,
                            ready_length
                    );

                    jstring jToken =
                            string_to_jstring(
                                    env,
                                    ready_text
                            );

                    if (jToken != nullptr) {
                        env->CallVoidMethod(
                                tokenCallbackObject,
                                onTokenMethod,
                                jToken
                        );

                        env->DeleteLocalRef(
                                jToken
                        );
                    }

                    if (env->ExceptionCheck()) {
                        env->ExceptionDescribe();
                        env->ExceptionClear();
                        break;
                    }
                }
            }

            llama_batch token_batch =
                    llama_batch_get_one(
                            &token,
                            1
                    );

            if (llama_decode(
                    handle->ctx,
                    token_batch
            ) != 0) {

                MYDND_LOGE(
                        "nativeGenerateInventoryToolAwareStream: narrative token decode failed"
                );

                break;
            }

            if (i >= soft_min_tokens
                && ends_with_sentence_mark(
                        narrative_output
                )) {

                break;
            }
        }

        if (!streaming_pending.empty()) {
            jstring jToken =
                    string_to_jstring(
                            env,
                            streaming_pending
                    );

            if (jToken != nullptr) {
                env->CallVoidMethod(
                        tokenCallbackObject,
                        onTokenMethod,
                        jToken
                );

                env->DeleteLocalRef(
                        jToken
                );
            }
        }

        auto narrative_end =
                std::chrono::steady_clock::now();

        auto narrative_ms =
                std::chrono::duration_cast<
                        std::chrono::milliseconds
                >(
                        narrative_end
                        - narrative_start
                ).count();

        double tokens_per_second =
                0.0;

        if (narrative_ms > 0) {
            tokens_per_second =
                    narrative_tokens * 1000.0
                    / static_cast<double>(
                            narrative_ms
                    );
        }

        llama_sampler_free(
                narrative_sampler
        );

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: narrative = %lld ms, tokens=%d, speed=%.2f tok/s",
                static_cast<long long>(
                        narrative_ms
                ),
                narrative_tokens,
                tokens_per_second
        );

        MYDND_LOGI(
                "nativeGenerateInventoryToolAwareStream: FINISH, narrative length=%zu",
                narrative_output.size()
        );

        return string_to_jstring(
                env,
                narrative_output
        );

    } catch (const std::exception & ex) {
        return string_to_jstring(
                env,
                std::string(
                        "Ошибка nativeGenerateInventoryToolAwareStream: "
                ) + ex.what()
        );
    } catch (...) {
        return string_to_jstring(
                env,
                "Ошибка nativeGenerateInventoryToolAwareStream: неизвестная ошибка."
        );
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_mydnd_llm_NativeLlmBridge_nativeRelease(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong nativeHandle) {

    if (nativeHandle == 0) {
        return;
    }

    MyDndLlamaHandle * handle =
            reinterpret_cast<MyDndLlamaHandle *>(nativeHandle);

    std::lock_guard<std::mutex> lock(handle->mutex);

    if (handle->ctx != nullptr) {
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }

    if (handle->model != nullptr) {
        llama_model_free(handle->model);
        handle->model = nullptr;
    }

    delete handle;
}