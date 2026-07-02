#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <stdexcept>

#include "llama.h"
#include <android/log.h>

#define MYDND_LOG_TAG "MyDND_NATIVE"
#define MYDND_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MYDND_LOG_TAG, __VA_ARGS__)
#define MYDND_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MYDND_LOG_TAG, __VA_ARGS__)

struct MyDndLlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex mutex;
};

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

static jstring string_to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
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
        if (tokenized > 512) {
    MYDND_LOGE("nativeGenerate: prompt too long for one batch, tokenized = %d", tokenized);
    return string_to_jstring(
            env,
            "Ошибка: prompt слишком длинный для одного batch. Сейчас лимит 512 токенов."
    );
}

        if (tokenized < 0) {
            return string_to_jstring(env, "Ошибка: tokenizer вернул отрицательный результат.");
        }

        llama_sampler_chain_params sampler_params =
                llama_sampler_chain_default_params();

        sampler_params.no_perf = true;

        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);

        // Для первого запуска — greedy. Потом добавим temperature/top_p.
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size())
        );

//        MYDND_LOGI("nativeGenerate: before prompt decode");
        if (llama_decode(handle->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            return string_to_jstring(env, "Ошибка: llama_decode не смог обработать prompt.");
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
        jint maxTokens,
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

    try {
        std::string prompt = jstring_to_string(env, promptText);

        if (prompt.empty()) {
            return string_to_jstring(env, "");
        }

        const int n_predict = maxTokens > 0 ? maxTokens : 80;

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

        if (static_cast<uint32_t>(n_prompt + n_predict) >= n_ctx) {
            return string_to_jstring(
                    env,
                    "Ошибка: prompt слишком длинный для текущего контекста."
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

        if (tokenized > 512) {
            MYDND_LOGE("nativeGenerateStream: prompt too long for one batch, tokenized = %d", tokenized);
            return string_to_jstring(
                    env,
                    "Ошибка: prompt слишком длинный для одного batch. Сейчас лимит 512 токенов."
            );
        }

        llama_sampler_chain_params sampler_params =
                llama_sampler_chain_default_params();

        sampler_params.no_perf = true;

        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);

        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size())
        );

        if (llama_decode(handle->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            return string_to_jstring(env, "Ошибка: llama_decode не смог обработать prompt.");
        }

        std::string output;
        llama_token new_token_id;

        for (int i = 0; i < n_predict; i++) {
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
                std::string piece(buffer, piece_length);
                output.append(piece);

                jstring jToken = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(callbackObject, onTokenMethod, jToken);
                env->DeleteLocalRef(jToken);

                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    break;
                }
            }

            batch = llama_batch_get_one(&new_token_id, 1);

            if (llama_decode(handle->ctx, batch) != 0) {
                break;
            }
        }

        llama_sampler_free(sampler);

        MYDND_LOGI("nativeGenerateStream: FINISH, output length = %zu", output.size());

        return string_to_jstring(env, output);
    } catch (const std::exception & ex) {
        return string_to_jstring(env, std::string("Ошибка nativeGenerateStream: ") + ex.what());
    } catch (...) {
        return string_to_jstring(env, "Ошибка nativeGenerateStream: неизвестная ошибка.");
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