#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <stdexcept>

#include "llama.h"
#include "ggml-cpu.h"
#include <android/log.h>
#include <atomic>
#include <ctime>
#include <chrono>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <unistd.h>

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
    ggml_threadpool * threadpool = nullptr;
    std::mutex mutex;
    std::atomic_bool cancel_requested{false};

    /*
     * Text of the static Director system-block prefix ("SYSTEM:" + rules +
     * tool declaration - see DirectorToolSpec.java, byte-identical for a
     * given mode across every turn) currently resident in this context's
     * KV-cache, or empty if nothing is cached. Must be invalidated (cleared)
     * by every code path that clears this handle's memory - see
     * mydnd_clear_memory().
     */
    std::string cached_static_prefix;
    int cached_static_prefix_tokens = 0;
};

/*
 * The ONLY way this context's KV-cache may be wiped. Every call site that
 * used to call llama_memory_clear() directly now goes through this instead,
 * so cached_static_prefix can never describe stale/wiped KV-cache content -
 * whichever native entrypoint clears the cache next, the prefix cache is
 * invalidated along with it.
 */
static void mydnd_clear_memory(MyDndLlamaHandle * handle) {
    llama_memory_clear(llama_get_memory(handle->ctx), true);
    handle->cached_static_prefix.clear();
    handle->cached_static_prefix_tokens = 0;
}

/*
 * Phones ship big.LITTLE (or big.MID.LITTLE) SoCs. Without an explicit
 * cpuset, Android's power-aware scheduler tends to park default-priority
 * background compute threads on the weak LITTLE cluster, which quietly
 * tanks decode speed (observed ~5.5 tok/s on a chip whose big cores can
 * do several times that). We rank cores by their own reported max
 * frequency and keep only the fastest tier(s), so this generalizes across
 * devices instead of hardcoding one phone's core indices.
 */
static int detect_performance_core_mask(bool mask[GGML_MAX_N_THREADS]) {
    for (int i = 0; i < GGML_MAX_N_THREADS; i++) {
        mask[i] = false;
    }

    long nproc = sysconf(_SC_NPROCESSORS_CONF);
    if (nproc <= 0) {
        return 0;
    }
    if (nproc > GGML_MAX_N_THREADS) {
        nproc = GGML_MAX_N_THREADS;
    }

    std::vector<long> max_freq_khz(static_cast<size_t>(nproc), -1);
    long highest = -1;

    for (int i = 0; i < nproc; i++) {
        char path[128];
        snprintf(
                path,
                sizeof(path),
                "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq",
                i
        );

        FILE * file = fopen(path, "r");
        if (file == nullptr) {
            continue;
        }

        long freq = -1;
        if (fscanf(file, "%ld", &freq) != 1) {
            freq = -1;
        }
        fclose(file);

        max_freq_khz[i] = freq;
        if (freq > highest) {
            highest = freq;
        }
    }

    if (highest <= 0) {
        // Couldn't read cpufreq (permissions or unsupported layout).
        // Caller falls back to default OS affinity.
        return 0;
    }

    long lowest = highest;
    for (long freq : max_freq_khz) {
        if (freq > 0 && freq < lowest) {
            lowest = freq;
        }
    }

    // Single frequency tier (homogeneous CPU) -> nothing to exclude, use all.
    // Otherwise drop only the slowest tier (the LITTLE cluster) and keep
    // every core that reported a strictly faster max frequency.
    int selected = 0;
    for (int i = 0; i < nproc; i++) {
        bool keep = max_freq_khz[i] > 0
                    && (lowest == highest || max_freq_khz[i] > lowest);
        mask[i] = keep;
        if (keep) {
            selected++;
        }
    }

    return selected;
}

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



static bool is_director_tool_call_text(
        const std::string & text
) {
    return text.find("director_action{")
           != std::string::npos;
}


static bool is_director_done_call(
        const std::string & text
) {
    return text.find("type:<|\"|>DONE<|\"|>")
                   != std::string::npos
           || text.find("type:DONE")
                   != std::string::npos;
}


static std::string extract_director_action_type(
        const std::string & text
) {
    const std::string marker = "type:<|\"|>";
    size_t start = text.find(marker);
    if (start == std::string::npos) {
        return "";
    }
    start += marker.size();

    const std::string end_marker = "<|\"|>";
    size_t end = text.find(end_marker, start);
    if (end == std::string::npos || end <= start) {
        return "";
    }

    return text.substr(start, end - start);
}


static std::string director_action_family(
        const std::string & action_type
) {
    if (action_type == "INV_ADD" || action_type == "INV_REMOVE") return "inventory";
    if (action_type == "HP") return "hp";
    if (action_type == "MONEY") return "money";
    if (action_type == "NPC_UPSERT" || action_type == "NPC_MEMORY" || action_type == "NPC_STATUS") return "npc";
    if (action_type == "WORLD_ADD" || action_type == "WORLD_UPDATE" || action_type == "WORLD_RESOLVE") return "world";
    if (action_type == "QUEST_START" || action_type == "QUEST_UPDATE" || action_type == "QUEST_COMPLETE" || action_type == "QUEST_FAIL") return "quest";
    if (action_type == "ABILITY_ADD" || action_type == "ABILITY_UPDATE" || action_type == "ABILITY_REMOVE") return "ability";
    if (action_type == "EFFECT_ADD" || action_type == "EFFECT_REMOVE") return "effect";
    if (action_type == "LOCATION") return "location";
    if (action_type == "CHECK") return "check";
    return "";
}


static bool contains_string(
        const std::vector<std::string> & values,
        const std::string & target
) {
    return std::find(values.begin(), values.end(), target) != values.end();
}


static bool is_complete_tool_call(
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
            text.find("director_action{")
                    != std::string::npos
            || text.find("remember_world_event{")
                    != std::string::npos
            || text.find("no_world_event{")
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


/*
 * Returns true only when text is one exact vocabulary token.
 * We use this for Gemma 4 protocol markers so the inventory grammar matches
 * special tokens directly instead of hoping character matching will handle
 * them the same way on every device / decode configuration.
 */
static bool get_single_special_token(
        const llama_vocab * vocab,
        const char * text,
        llama_token & result
) {
    if (vocab == nullptr
        || text == nullptr
        || text[0] == '\0') {

        return false;
    }

    int token_count =
            -llama_tokenize(
                    vocab,
                    text,
                    static_cast<int32_t>(
                            std::strlen(text)
                    ),
                    nullptr,
                    0,
                    false,
                    true
            );

    if (token_count != 1) {
        return false;
    }

    llama_token token = 0;

    int tokenized =
            llama_tokenize(
                    vocab,
                    text,
                    static_cast<int32_t>(
                            std::strlen(text)
                    ),
                    &token,
                    1,
                    false,
                    true
            );

    if (tokenized != 1) {
        return false;
    }

    result = token;
    return true;
}


static std::vector<llama_token> tokenize_text_impl(
        const llama_vocab * vocab,
        const std::string & text,
        bool add_special,
        bool parse_special
) {
    if (text.empty()) {
        return {};
    }

    int needed = -llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            nullptr,
            0,
            add_special,
            parse_special
    );

    if (needed <= 0) {
        return {};
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));

    int written = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            needed,
            add_special,
            parse_special
    );

    if (written != needed) {
        return {};
    }

    return tokens;
}


/*
 * Tokenizes plain literal text as a mid-generation continuation (no BOS,
 * no special-token parsing - the caller supplies special tokens like
 * <|tool_call> separately by ID). Returns an empty vector on failure or
 * for empty input.
 */
static std::vector<llama_token> tokenize_plain_text(
        const llama_vocab * vocab,
        const std::string & text
) {
    return tokenize_text_impl(vocab, text, false, false);
}


/*
 * Tokenizes a text span that continues an already-started sequence (no BOS,
 * but WITH special-token markup parsing, e.g. embedded "<|\"|>" quote
 * markers) - matches how the whole prompt used to be tokenized, minus BOS.
 */
static std::vector<llama_token> tokenize_continuation_text(
        const llama_vocab * vocab,
        const std::string & text
) {
    return tokenize_text_impl(vocab, text, false, true);
}


/*
 * Tokenizes text as the start of a brand new sequence (BOS + special-token
 * markup parsing) - matches the original whole-prompt tokenization exactly.
 */
static std::vector<llama_token> tokenize_new_sequence_text(
        const llama_vocab * vocab,
        const std::string & text
) {
    return tokenize_text_impl(vocab, text, true, true);
}


/*
 * Decodes a span of already-known tokens (a GBNF-forced literal the grammar
 * would have produced one token at a time anyway) in a single llama_decode
 * batch call instead of one llama_decode per token. Appends their text to
 * raw_tool_call and advances context_tokens_used/generated_tokens exactly as
 * the per-token loop in generate_director_action() would have. Used only for
 * spans with zero real model choice - never for a field the model actually
 * picks (type/name/value/details content).
 */
static bool decode_forced_token_span(
        MyDndLlamaHandle * handle,
        const std::vector<llama_token> & tokens,
        std::string & raw_tool_call,
        int & context_tokens_used,
        int & generated_tokens
) {
    if (tokens.empty()) {
        return true;
    }

    llama_batch batch = llama_batch_get_one(
            const_cast<llama_token *>(tokens.data()),
            static_cast<int32_t>(tokens.size())
    );

    if (llama_decode(handle->ctx, batch) != 0) {
        MYDND_LOGE(
                "decode_forced_token_span: llama_decode failed, count=%d",
                static_cast<int>(tokens.size())
        );
        return false;
    }

    for (llama_token token : tokens) {
        char buffer[256];
        int piece_length = llama_token_to_piece(
                handle->vocab,
                token,
                buffer,
                sizeof(buffer),
                0,
                true
        );

        if (piece_length > 0) {
            raw_tool_call.append(buffer, piece_length);
        }
    }

    context_tokens_used += static_cast<int>(tokens.size());
    generated_tokens += static_cast<int>(tokens.size());
    return true;
}


static std::string trim_copy(std::string value) {
    const char * whitespace = " \t\r\n";
    size_t start = value.find_first_not_of(whitespace);
    if (start == std::string::npos) {
        return "";
    }
    size_t end = value.find_last_not_of(whitespace);
    return value.substr(start, end - start + 1);
}


static std::vector<std::string> unique_non_empty(
        const std::vector<std::string> & values
) {
    std::vector<std::string> result;
    for (const std::string & value : values) {
        std::string clean = trim_copy(value);
        if (clean.empty()) {
            continue;
        }
        if (std::find(result.begin(), result.end(), clean) == result.end()) {
            result.push_back(clean);
        }
    }
    return result;
}


static std::vector<std::string> extract_prompt_list(
        const std::string & prompt,
        const std::string & header,
        bool take_name_before_pipe
) {
    size_t state_start = prompt.rfind("\nSTATE BEFORE");
    size_t roll_state_start = prompt.rfind("\nSTATE AFTER ROLL");
    if (roll_state_start != std::string::npos
        && (state_start == std::string::npos || roll_state_start > state_start)) {
        state_start = roll_state_start;
    }
    if (state_start == std::string::npos) {
        return {};
    }

    size_t state_end = prompt.find("\n\nPLAYER_ACTION:", state_start);
    size_t task_end = prompt.find("\n\nTASK:", state_start);
    if (state_end == std::string::npos
        || (task_end != std::string::npos && task_end < state_end)) {
        state_end = task_end;
    }
    if (state_end == std::string::npos) {
        state_end = prompt.size();
    }

    const std::string marker = "\n" + header + ":";
    size_t marker_pos = prompt.find(marker, state_start);
    if (marker_pos == std::string::npos || marker_pos >= state_end) {
        return {};
    }

    size_t line_end = prompt.find('\n', marker_pos + marker.size());
    if (line_end == std::string::npos || line_end >= state_end) {
        return {};
    }

    std::vector<std::string> values;
    size_t pos = line_end + 1;

    while (pos < state_end) {
        size_t next = prompt.find('\n', pos);
        if (next == std::string::npos || next > state_end) {
            next = state_end;
        }
        std::string line = trim_copy(prompt.substr(pos, next - pos));

        if (line.rfind("- ", 0) != 0) {
            break;
        }

        std::string value = trim_copy(line.substr(2));
        if (take_name_before_pipe) {
            size_t pipe = value.find(" | ");
            if (pipe != std::string::npos) {
                value = trim_copy(value.substr(0, pipe));
            }
        }
        if (!value.empty()) {
            values.push_back(value);
        }

        if (next >= state_end) {
            break;
        }
        pos = next + 1;
    }

    return unique_non_empty(values);
}


static std::string gbnf_escape_literal(const std::string & value) {
    std::string escaped;
    escaped.reserve(value.size() + 8);
    for (char ch : value) {
        switch (ch) {
            case '\\': escaped += "\\\\"; break;
            case '"': escaped += "\\\""; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default: escaped.push_back(ch); break;
        }
    }
    return escaped;
}


static void append_literal_rule(
        std::string & grammar,
        const std::string & rule_name,
        const std::vector<std::string> & raw_values
) {
    const std::vector<std::string> values = unique_non_empty(raw_values);
    if (values.empty()) {
        return;
    }

    grammar += rule_name + " ::= ";
    for (size_t i = 0; i < values.size(); i++) {
        if (i > 0) {
            grammar += " | ";
        }
        grammar += "\"" + gbnf_escape_literal(values[i]) + "\"";
    }
    grammar += "\n";
}


static std::string build_director_action_grammar(
        const llama_vocab * vocab,
        const std::string & prompt,
        const std::string & director_mode,
        bool allow_check,
        bool allow_world_actions,
        const std::string & retry_family,
        const std::vector<std::string> & disabled_action_types
) {
    llama_token tool_call_open = 0;
    llama_token tool_call_close = 0;
    llama_token tool_string_quote = 0;

    if (!get_single_special_token(vocab, "<|tool_call>", tool_call_open)
        || !get_single_special_token(vocab, "<tool_call|>", tool_call_close)
        || !get_single_special_token(vocab, "<|\"|>", tool_string_quote)) {
        return "";
    }

    // tool_call_open is validated above but no longer embedded in this grammar's
    // text - the "<|tool_call>call:director_action{type:\"" prefix (identical
    // across every branch) is decoded once as a forced batch before this
    // grammar is consulted; see action_rhs() below and generate_director_action().
    const std::string close = "<[" + std::to_string(tool_call_close) + "]>";
    const std::string quote = "<[" + std::to_string(tool_string_quote) + "]>";

    const std::vector<std::string> inventory =
            extract_prompt_list(prompt, "INVENTORY", false);
    const std::vector<std::string> npcs =
            extract_prompt_list(prompt, "NPCS", true);
    const std::vector<std::string> quests =
            extract_prompt_list(prompt, "QUESTS", true);
    const std::vector<std::string> world_events =
            extract_prompt_list(prompt, "WORLD_EVENTS", true);
    const std::vector<std::string> abilities =
            extract_prompt_list(prompt, "ABILITIES", true);
    const std::vector<std::string> effects =
            extract_prompt_list(prompt, "EFFECTS", true);

    std::vector<std::string> hp_targets = {"PLAYER"};
    hp_targets.insert(hp_targets.end(), npcs.begin(), npcs.end());
    hp_targets = unique_non_empty(hp_targets);

    const bool check_result_mode = director_mode == "CHECK_RESULT";
    const bool random_world_mode = director_mode == "RANDOM_WORLD_EVENT";
    const bool player_action_mode = !check_result_mode && !random_world_mode;

    auto disabled = [&](const std::string & action_type) -> bool {
        return contains_string(disabled_action_types, action_type);
    };

    std::vector<std::string> branches;
    branches.push_back("done");

    auto add_branch = [&](const std::string & branch,
                          const std::string & action_type) {
        if (!disabled(action_type)) {
            branches.push_back(branch);
        }
    };

    auto add_player_family = [&](const std::string & family) {
        if (family == "check") {
            if (allow_check) add_branch("check", "CHECK");
            return;
        }
        if (family == "inventory") {
            add_branch("inv-add", "INV_ADD");
            if (!inventory.empty()) add_branch("inv-remove", "INV_REMOVE");
            return;
        }
        if (family == "hp") {
            add_branch("hp", "HP");
            return;
        }
        if (family == "money") {
            add_branch("money", "MONEY");
            return;
        }
        if (family == "npc") {
            add_branch("npc-upsert", "NPC_UPSERT");
            if (!npcs.empty()) {
                add_branch("npc-memory", "NPC_MEMORY");
                add_branch("npc-status", "NPC_STATUS");
            }
            return;
        }
        if (family == "world") {
            if (!allow_world_actions) return;
            add_branch("world-add", "WORLD_ADD");
            if (!world_events.empty()) {
                add_branch("world-update", "WORLD_UPDATE");
                add_branch("world-resolve", "WORLD_RESOLVE");
            }
            return;
        }
        if (family == "quest") {
            add_branch("quest-start", "QUEST_START");
            if (!quests.empty()) {
                add_branch("quest-update", "QUEST_UPDATE");
                add_branch("quest-complete", "QUEST_COMPLETE");
                add_branch("quest-fail", "QUEST_FAIL");
            }
            return;
        }
        if (family == "ability") {
            add_branch("ability-add", "ABILITY_ADD");
            if (!abilities.empty()) {
                add_branch("ability-update", "ABILITY_UPDATE");
                add_branch("ability-remove", "ABILITY_REMOVE");
            }
            return;
        }
        if (family == "effect") {
            add_branch("effect-add", "EFFECT_ADD");
            if (!effects.empty()) add_branch("effect-remove", "EFFECT_REMOVE");
            return;
        }
        if (family == "location") {
            add_branch("location", "LOCATION");
        }
    };

    if (player_action_mode) {
        if (!retry_family.empty()) {
            add_player_family(retry_family);
        } else {
            if (allow_check) add_branch("check", "CHECK");
            add_branch("inv-add", "INV_ADD");
            if (!inventory.empty()) add_branch("inv-remove", "INV_REMOVE");
            add_branch("hp", "HP");
            add_branch("money", "MONEY");
            add_branch("npc-upsert", "NPC_UPSERT");
            if (!npcs.empty()) {
                add_branch("npc-memory", "NPC_MEMORY");
                add_branch("npc-status", "NPC_STATUS");
            }
            if (allow_world_actions) {
                add_branch("world-add", "WORLD_ADD");
                if (!world_events.empty()) {
                    add_branch("world-update", "WORLD_UPDATE");
                    add_branch("world-resolve", "WORLD_RESOLVE");
                }
            }
            add_branch("quest-start", "QUEST_START");
            if (!quests.empty()) {
                add_branch("quest-update", "QUEST_UPDATE");
                add_branch("quest-complete", "QUEST_COMPLETE");
                add_branch("quest-fail", "QUEST_FAIL");
            }
            add_branch("ability-add", "ABILITY_ADD");
            if (!abilities.empty()) {
                add_branch("ability-update", "ABILITY_UPDATE");
                add_branch("ability-remove", "ABILITY_REMOVE");
            }
            add_branch("effect-add", "EFFECT_ADD");
            if (!effects.empty()) add_branch("effect-remove", "EFFECT_REMOVE");
            add_branch("location", "LOCATION");
        }
    } else if (check_result_mode) {
        add_branch("hp", "HP");
    } else {
        add_branch("world-add", "WORLD_ADD");
        add_branch("npc-upsert", "NPC_UPSERT");
        add_branch("quest-start", "QUEST_START");
        add_branch("effect-add", "EFFECT_ADD");
    }

    auto field = [&](const std::string & rule) -> std::string {
        if (rule.empty()) {
            return quote + " " + quote;
        }
        return quote + " " + rule + " " + quote;
    };

    /*
     * The "<|tool_call>call:director_action{type:\"" prefix is identical for
     * every single branch below - it is decoded once as a forced batch in
     * generate_director_action() (native_bridge.cpp) before this grammar's
     * root rule is ever consulted, instead of being generated here one token
     * at a time. This rule intentionally starts right after that prefix
     * (at the TYPE literal), not at "root ::= <[open]> ...".
     */
    auto action_rhs = [&](const std::string & type,
                          const std::string & name_rule,
                          const std::string & value_rule,
                          const std::string & details_rule) -> std::string {
        std::string rhs;
        rhs += "\"" + type + "\" ";
        rhs += quote;
        rhs += " \",name:\" ";
        rhs += field(name_rule);
        rhs += " \",value:\" ";
        rhs += field(value_rule);
        rhs += " \",details:\" ";
        rhs += field(details_rule);
        rhs += " \"}\" ";
        rhs += close;
        return rhs;
    };

    std::string grammar = "root ::= ";
    for (size_t i = 0; i < branches.size(); i++) {
        if (i > 0) grammar += " | ";
        grammar += branches[i];
    }
    grammar += "\n\n";

    grammar += "done ::= " + action_rhs("DONE", "", "", "") + "\n";
    grammar += "check ::= " + action_rhs("CHECK", "check-stat", "check-dc", "details-text") + "\n";
    grammar += "inv-add ::= " + action_rhs("INV_ADD", "free-name", "", "details-opt") + "\n";
    if (!inventory.empty()) {
        grammar += "inv-remove ::= " + action_rhs("INV_REMOVE", "inventory-name", "", "details-opt") + "\n";
    }
    grammar += "hp ::= " + action_rhs(
            "HP",
            "hp-target",
            check_result_mode ? "check-damage" : "signed-int",
            "details-text"
    ) + "\n";
    grammar += "money ::= " + action_rhs("MONEY", "player-target", "signed-int", "details-text") + "\n";
    grammar += "npc-upsert ::= " + action_rhs("NPC_UPSERT", "free-name", "", "details-text") + "\n";
    if (!npcs.empty()) {
        grammar += "npc-memory ::= " + action_rhs("NPC_MEMORY", "npc-name", "memory-tone", "details-text") + "\n";
        grammar += "npc-status ::= " + action_rhs("NPC_STATUS", "npc-name", "npc-status-value", "details-opt") + "\n";
    }
    grammar += "world-add ::= " + action_rhs("WORLD_ADD", "free-name", "importance-opt", "details-text") + "\n";
    if (!world_events.empty()) {
        grammar += "world-update ::= " + action_rhs("WORLD_UPDATE", "world-event-name", "importance-opt", "details-text") + "\n";
        grammar += "world-resolve ::= " + action_rhs("WORLD_RESOLVE", "world-event-name", "", "details-opt") + "\n";
    }
    grammar += "quest-start ::= " + action_rhs("QUEST_START", "free-name", "", "details-text") + "\n";
    if (!quests.empty()) {
        grammar += "quest-update ::= " + action_rhs("QUEST_UPDATE", "quest-name", "", "details-text") + "\n";
        grammar += "quest-complete ::= " + action_rhs("QUEST_COMPLETE", "quest-name", "", "details-opt") + "\n";
        grammar += "quest-fail ::= " + action_rhs("QUEST_FAIL", "quest-name", "", "details-opt") + "\n";
    }
    grammar += "ability-add ::= " + action_rhs("ABILITY_ADD", "free-name", "ability-category", "details-opt") + "\n";
    if (!abilities.empty()) {
        grammar += "ability-update ::= " + action_rhs("ABILITY_UPDATE", "ability-name", "ability-category", "details-opt") + "\n";
        grammar += "ability-remove ::= " + action_rhs("ABILITY_REMOVE", "ability-name", "", "details-opt") + "\n";
    }
    grammar += "effect-add ::= " + action_rhs("EFFECT_ADD", "free-name", "", "details-text") + "\n";
    if (!effects.empty()) {
        grammar += "effect-remove ::= " + action_rhs("EFFECT_REMOVE", "effect-name", "", "details-opt") + "\n";
    }
    grammar += "location ::= " + action_rhs("LOCATION", "free-name", "", "details-opt") + "\n\n";

    grammar += "free-name ::= [^<>{};=\\r\\n]{1,96}\n";
    grammar += "details-text ::= [^<>{}\\r\\n]{1,180}\n";
    grammar += "details-opt ::= [^<>{}\\r\\n]{0,180}\n";
    grammar += "signed-int ::= (\"+\" | \"-\") [0-9]{1,4}\n";
    grammar += "check-damage ::= \"-1\" | \"-2\" | \"-3\" | \"-4\" | \"-5\" | \"-6\"\n";
    grammar += "check-stat ::= \"STR\" | \"DEX\" | \"INT\" | \"CHA\"\n";
    grammar += "check-dc ::= \"5\" | \"6\" | \"7\" | \"8\" | \"9\" | \"10\" | \"11\" | \"12\" | \"13\" | \"14\" | \"15\" | \"16\" | \"17\" | \"18\" | \"19\" | \"20\" | \"21\" | \"22\" | \"23\" | \"24\" | \"25\"\n";
    grammar += "player-target ::= \"PLAYER\"\n";
    grammar += "memory-tone ::= \"GOOD\" | \"BAD\" | \"NEUTRAL\"\n";
    grammar += "npc-status-value ::= \"ACTIVE\" | \"KNOWN\" | \"INACTIVE\" | \"MISSING\" | \"HOSTILE\" | \"ALLY\"\n";
    grammar += "ability-category ::= \"SKILL\" | \"SPELL\" | \"TRAIT\" | \"POWER\"\n";
    grammar += "importance-opt ::= \"\" | \"1\" | \"2\" | \"3\"\n";

    append_literal_rule(grammar, "inventory-name", inventory);
    append_literal_rule(grammar, "npc-name", npcs);
    append_literal_rule(grammar, "hp-target", hp_targets);
    append_literal_rule(grammar, "quest-name", quests);
    append_literal_rule(grammar, "world-event-name", world_events);
    append_literal_rule(grammar, "ability-name", abilities);
    append_literal_rule(grammar, "effect-name", effects);

    MYDND_LOGI(
            "director grammar: mode=%s toolCheck=%s world=%s retry=%s disabled=%zu",
            director_mode.c_str(),
            allow_check ? "ON" : "OFF",
            allow_world_actions ? "ON" : "OFF",
            retry_family.empty() ? "NONE" : retry_family.c_str(),
            disabled_action_types.size()
    );

    MYDND_LOGI(
            "director strict typed grammar:\n%s",
            grammar.c_str()
    );

    return grammar;
}


static std::string build_director_done_grammar(
        const llama_vocab * vocab
) {
    llama_token tool_call_open = 0;
    llama_token tool_call_close = 0;
    llama_token tool_string_quote = 0;

    if (!get_single_special_token(vocab, "<|tool_call>", tool_call_open)
        || !get_single_special_token(vocab, "<tool_call|>", tool_call_close)
        || !get_single_special_token(vocab, "<|\"|>", tool_string_quote)) {
        return "";
    }

    const std::string open = "<[" + std::to_string(tool_call_open) + "]>";
    const std::string close = "<[" + std::to_string(tool_call_close) + "]>";
    const std::string quote = "<[" + std::to_string(tool_string_quote) + "]>";

    std::string grammar = "root ::= ";
    grammar += open;
    grammar += " \"call:director_action{type:\" ";
    grammar += quote;
    grammar += " \"DONE\" ";
    grammar += quote;
    grammar += " \",name:\" ";
    grammar += quote;
    grammar += " ";
    grammar += quote;
    grammar += " \",value:\" ";
    grammar += quote;
    grammar += " ";
    grammar += quote;
    grammar += " \",details:\" ";
    grammar += quote;
    grammar += " ";
    grammar += quote;
    grammar += " \"}\" ";
    grammar += close;
    grammar += "\n";
    return grammar;
}


static llama_sampler * create_director_action_sampler(
        const llama_vocab * vocab,
        const std::string & prompt,
        const std::string & director_mode,
        bool allow_check,
        bool allow_world_actions,
        const std::string & retry_family,
        const std::vector<std::string> & disabled_action_types
) {
    std::string grammar =
            build_director_action_grammar(
                    vocab,
                    prompt,
                    director_mode,
                    allow_check,
                    allow_world_actions,
                    retry_family,
                    disabled_action_types
            );

    if (grammar.empty()) {
        MYDND_LOGE(
                "create_director_decision_sampler: Gemma tool special tokens are not single tokens"
        );

        return nullptr;
    }

    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();

    sampler_params.no_perf = true;

    llama_sampler * sampler =
            llama_sampler_chain_init(
                    sampler_params
            );

    llama_sampler * grammar_sampler =
            llama_sampler_init_grammar(
                    vocab,
                    grammar.c_str(),
                    "root"
            );

    if (grammar_sampler == nullptr) {
        MYDND_LOGE(
                "create_director_decision_sampler: llama_sampler_init_grammar returned nullptr"
        );

        llama_sampler_free(
                sampler
        );

        return nullptr;
    }

    /*
     * Grammar MUST be first. Then top-k/top-p only rank tokens valid for
     * the universal Director protocol. Keep this phase almost deterministic.
     */
    llama_sampler_chain_add(
            sampler,
            grammar_sampler
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(
                    96,
                    1.00f,
                    0.20f,
                    0.10f
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_k(
                    20
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_p(
                    0.80f,
                    1
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(
                    0.10f
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


static llama_sampler * create_director_done_sampler(
        const llama_vocab * vocab
) {
    std::string grammar = build_director_done_grammar(vocab);
    if (grammar.empty()) {
        return nullptr;
    }

    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();
    sampler_params.no_perf = true;

    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler * grammar_sampler =
            llama_sampler_init_grammar(vocab, grammar.c_str(), "root");

    if (grammar_sampler == nullptr) {
        llama_sampler_free(sampler);
        return nullptr;
    }

    llama_sampler_chain_add(sampler, grammar_sampler);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    return sampler;
}


static std::string build_world_event_grammar(
        const llama_vocab * vocab
) {
    llama_token tool_call_open = 0;
    llama_token tool_call_close = 0;
    llama_token tool_string_quote = 0;

    if (!get_single_special_token(vocab, "<|tool_call>", tool_call_open)
        || !get_single_special_token(vocab, "<tool_call|>", tool_call_close)
        || !get_single_special_token(vocab, "<|\"|>", tool_string_quote)) {

        return "";
    }

    const std::string open =
            "<[" + std::to_string(tool_call_open) + "]>";

    const std::string close =
            "<[" + std::to_string(tool_call_close) + "]>";

    const std::string quote =
            "<[" + std::to_string(tool_string_quote) + "]>";

    std::string grammar;

    grammar += "root ::= remember | no-event\n\n";

    grammar += "remember ::= ";
    grammar += open;
    grammar += " \"call:remember_world_event{text:\" ";
    grammar += quote;
    grammar += " event-text ";
    grammar += quote;
    grammar += " \",importance:\" importance \"}\" ";
    grammar += close;
    grammar += "\n\n";

    grammar += "no-event ::= ";
    grammar += open;
    grammar += " \"call:no_world_event{}\" ";
    grammar += close;
    grammar += "\n\n";

    grammar += "event-text ::= [^<>{}\\r\\n]{1,180}\n";
    grammar += "importance ::= \"1\" | \"2\" | \"3\"\n";

    return grammar;
}


static llama_sampler * create_world_event_sampler(
        const llama_vocab * vocab
) {
    std::string grammar = build_world_event_grammar(vocab);

    if (grammar.empty()) {
        MYDND_LOGE(
                "create_world_event_sampler: Gemma tool special tokens are not single tokens"
        );

        return nullptr;
    }

    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();

    sampler_params.no_perf = true;

    llama_sampler * sampler =
            llama_sampler_chain_init(sampler_params);

    llama_sampler * grammar_sampler =
            llama_sampler_init_grammar(
                    vocab,
                    grammar.c_str(),
                    "root"
            );

    if (grammar_sampler == nullptr) {
        llama_sampler_free(sampler);
        return nullptr;
    }

    llama_sampler_chain_add(sampler, grammar_sampler);
    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_k(20)
    );
    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_p(0.80f, 1)
    );
    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(0.10f)
    );
    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(
                    static_cast<uint32_t>(time(nullptr))
            )
    );

    return sampler;
}

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

        bool performance_cpu_mask[GGML_MAX_N_THREADS];
        int performance_core_count =
                detect_performance_core_mask(performance_cpu_mask);

        const int n_threads =
                performance_core_count > 0
                        ? performance_core_count
                        : 4;

        MYDND_LOGI(
                "nativeLoadModel: performance cores detected = %d, n_threads = %d",
                performance_core_count,
                n_threads
        );

        llama_context_params ctx_params = llama_context_default_params();

        // Для телефона сначала скромный контекст.
        ctx_params.n_ctx = 2048;
        ctx_params.n_batch = 512;
        ctx_params.n_ubatch = 128;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;
        ctx_params.no_perf = true;

        llama_context * ctx = llama_init_from_model(model, ctx_params);

        if (ctx == nullptr) {
            llama_model_free(model);
            return 0;
        }

        ggml_threadpool * threadpool = nullptr;

        if (performance_core_count > 0) {
            ggml_threadpool_params tpp;
            ggml_threadpool_params_init(&tpp, n_threads);
            std::memcpy(tpp.cpumask, performance_cpu_mask, sizeof(tpp.cpumask));
            tpp.strict_cpu = true;
            tpp.prio = GGML_SCHED_PRIO_HIGH;
            tpp.poll = 50;

            threadpool = ggml_threadpool_new(&tpp);

            if (threadpool != nullptr) {
                llama_attach_threadpool(ctx, threadpool, threadpool);
                MYDND_LOGI(
                        "nativeLoadModel: pinned threadpool attached, threads=%d",
                        n_threads
                );
            } else {
                MYDND_LOGE(
                        "nativeLoadModel: ggml_threadpool_new failed, falling back to default affinity"
                );
            }
        }

        MyDndLlamaHandle * handle = new MyDndLlamaHandle();
        handle->model = model;
        handle->ctx = ctx;
        handle->vocab = llama_model_get_vocab(model);
        handle->threadpool = threadpool;

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
        mydnd_clear_memory(handle);

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

        mydnd_clear_memory(handle);

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

        const int prompt_chunk_size = 64;

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

            mydnd_clear_memory(handle);

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
Java_com_example_mydnd_llm_NativeLlmBridge_nativeGenerateDirectorAwareStream(
        JNIEnv * env,
        jobject /* this */,
        jlong nativeHandle,
        jstring promptText,
        jstring directorModeText,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty,
        jboolean runWorldEventPhase,
        jstring worldEventBatchText,
        jobject tokenCallbackObject,
        jobject toolCallbackObject) {

    MYDND_LOGI(
            "nativeGenerateDirectorAwareStream: ENTER"
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

        std::string director_mode =
                jstring_to_string(
                        env,
                        directorModeText
                );
        if (director_mode.empty()) {
            director_mode = "PLAYER_ACTION";
        }

        const bool check_result_mode =
                director_mode == "CHECK_RESULT";

        const bool should_run_world_event_phase =
                runWorldEventPhase == JNI_TRUE;

        std::string world_event_batch_text =
                jstring_to_string(
                        env,
                        worldEventBatchText
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
                192;

        /*
         * Minimum live reserves, not worst-case sums. The previous preflight
         * added decision_max_tokens + a phantom tool reserve + world-event
         * reserve before generation had even started. With ctx=2048 that
         * rejected valid turns (the reported case missed by only five tokens).
         *
         * Real Director actions and tool responses are checked dynamically
         * against the actual token count below. World-event memory is optional
         * background work and must never block the main turn.
         */
        const int director_startup_reserve =
                96;

        const int tool_response_min_reserve =
                128;

        const int narrative_min_reserve =
                96;

        const int world_event_max_tokens =
                72;

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

        const int minimum_turn_reserve =
                director_startup_reserve
                + tool_response_min_reserve
                + narrative_min_reserve;

        if (static_cast<uint32_t>(
                    n_prompt
                    + minimum_turn_reserve
                    + 1
            ) >= n_ctx) {

            MYDND_LOGE(
                    "nativeGenerateDirectorAwareStream: context overflow, prompt=%d, directorMin=%d, toolMin=%d, narrativeMin=%d, ctx=%u",
                    n_prompt,
                    director_startup_reserve,
                    tool_response_min_reserve,
                    narrative_min_reserve,
                    n_ctx
            );

            return string_to_jstring(
                    env,
                    "Ошибка: prompt слишком большой для запуска Режиссёра в текущем контексте модели."
            );
        }

        MYDND_LOGI(
                "nativeGenerateDirectorAwareStream: context budget, prompt=%d, free=%d, directorMin=%d, toolMin=%d, narrativeMin=%d, worldEvent=%s, ctx=%u",
                n_prompt,
                static_cast<int>(n_ctx) - n_prompt,
                director_startup_reserve,
                tool_response_min_reserve,
                narrative_min_reserve,
                should_run_world_event_phase ? "YES" : "NO",
                n_ctx
        );

        // Matches ctx_params.n_ubatch (128): larger prefill batches let the
        // pinned worker threads split more work per llama_decode call
        // instead of paying per-call sync overhead every 16 tokens.
        const int prompt_chunk_size =
                128;

        auto decode_prompt_tokens =
                [&](const std::vector<llama_token> & tokens) -> bool {
            for (int pos = 0;
                    pos < static_cast<int>(tokens.size());
                    pos += prompt_chunk_size) {

                if (handle->cancel_requested.load()) {
                    MYDND_LOGI(
                            "nativeGenerateDirectorAwareStream: cancelled during prompt decode, pos=%d",
                            pos
                    );
                    return false;
                }

                int chunk_size = prompt_chunk_size;
                if (pos + chunk_size > static_cast<int>(tokens.size())) {
                    chunk_size = static_cast<int>(tokens.size()) - pos;
                }

                llama_batch batch = llama_batch_get_one(
                        const_cast<llama_token *>(tokens.data()) + pos,
                        chunk_size
                );

                if (llama_decode(handle->ctx, batch) != 0) {
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: prompt decode failed, pos=%d, chunk=%d",
                            pos,
                            chunk_size
                    );
                    return false;
                }
            }
            return true;
        };

        /*
         * The Director system-block prefix ("SYSTEM:" + DirectorToolSpec
         * rules/declaration - see PromptBuilder.java) is byte-identical for a
         * given director_mode across every single turn. If this context's
         * KV-cache still holds exactly that prefix (nothing else has cleared
         * it since - see mydnd_clear_memory()), skip re-decoding it and only
         * decode the dynamic tail (current state + player action).
         */
        /*
         * MainActivity.prepareMasterPrompt() re-wraps PromptBuilder's raw
         * "SYSTEM: ... \n\nCURRENT_SCENE: ..." output into chat-turn markup
         * ("<|turn>system\n...<turn|>\n<|turn>user\nCURRENT_SCENE:...") before
         * this native function ever sees it, trimming away the original
         * "\n\n" lead-in. Match the literal marker as it actually arrives
         * here, not PromptBuilder's pre-wrap text.
         */
        static const std::string kScenePromptMarker = "CURRENT_SCENE:";
        const size_t scene_marker_pos = prompt.find(kScenePromptMarker);

        int context_tokens_used = 0;
        bool prefix_cache_used = false;

        auto prompt_decode_start =
                std::chrono::steady_clock::now();

        if (scene_marker_pos != std::string::npos) {
            const std::string static_prefix = prompt.substr(0, scene_marker_pos);
            const std::string dynamic_tail = prompt.substr(scene_marker_pos);

            if (!handle->cached_static_prefix.empty()
                    && handle->cached_static_prefix == static_prefix) {

                /*
                 * The KV-cache still holds everything the PREVIOUS turn
                 * decoded past the static prefix too - director tool-calls,
                 * tool responses, the full narrative. llama_decode's implicit
                 * position tracking (llama_batch_get_one, pos=nullptr) keeps
                 * advancing from wherever that left off, not from
                 * cached_static_prefix_tokens. Roll the cache back to exactly
                 * the cached prefix boundary before decoding this turn's
                 * fresh tail, or the new tokens land on top of - and the
                 * position counter overflows past - last turn's leftovers.
                 */
                llama_memory_seq_rm(
                        llama_get_memory(handle->ctx),
                        0,
                        handle->cached_static_prefix_tokens,
                        -1
                );

                std::vector<llama_token> tail_tokens =
                        tokenize_continuation_text(handle->vocab, dynamic_tail);

                bool tail_decode_ok = !tail_tokens.empty() && decode_prompt_tokens(tail_tokens);

                if (tail_decode_ok) {
                    context_tokens_used =
                            handle->cached_static_prefix_tokens
                            + static_cast<int>(tail_tokens.size());
                    prefix_cache_used = true;

                    MYDND_LOGI(
                            "nativeGenerateDirectorAwareStream: prefix cache HIT, cachedPrefixTokens=%d, tailTokens=%d",
                            handle->cached_static_prefix_tokens,
                            static_cast<int>(tail_tokens.size())
                    );
                } else if (handle->cancel_requested.load()) {
                    return string_to_jstring(env, "");
                }
            }

            if (!prefix_cache_used) {
                mydnd_clear_memory(handle);

                std::vector<llama_token> prefix_tokens =
                        tokenize_new_sequence_text(handle->vocab, static_prefix);
                std::vector<llama_token> tail_tokens =
                        tokenize_continuation_text(handle->vocab, dynamic_tail);

                if (prefix_tokens.empty() || tail_tokens.empty()
                        || static_cast<int>(prefix_tokens.size() + tail_tokens.size()) != n_prompt) {
                    /*
                     * Tokenizing the two halves separately did not reproduce
                     * the whole-prompt token count - a tokenizer boundary
                     * artifact at the split point. Never risk caching a
                     * mismatched prefix: fall back to decoding the untouched
                     * original prompt as a single unsplit sequence.
                     */
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: prefix/tail split mismatch (prefix=%d, tail=%d, whole=%d) - falling back, caching disabled this call",
                            static_cast<int>(prefix_tokens.size()),
                            static_cast<int>(tail_tokens.size()),
                            n_prompt
                    );

                    std::vector<llama_token> whole_tokens =
                            tokenize_new_sequence_text(handle->vocab, prompt);

                    if (whole_tokens.empty() || !decode_prompt_tokens(whole_tokens)) {
                        if (handle->cancel_requested.load()) {
                            return string_to_jstring(env, "");
                        }
                        return string_to_jstring(
                                env,
                                "Ошибка: llama_decode не смог обработать tool-aware prompt."
                        );
                    }

                    context_tokens_used = static_cast<int>(whole_tokens.size());
                } else {
                    if (!decode_prompt_tokens(prefix_tokens)) {
                        if (handle->cancel_requested.load()) {
                            return string_to_jstring(env, "");
                        }
                        return string_to_jstring(
                                env,
                                "Ошибка: llama_decode не смог обработать часть tool-aware prompt."
                        );
                    }

                    if (!decode_prompt_tokens(tail_tokens)) {
                        if (handle->cancel_requested.load()) {
                            return string_to_jstring(env, "");
                        }
                        return string_to_jstring(
                                env,
                                "Ошибка: llama_decode не смог обработать часть tool-aware prompt."
                        );
                    }

                    handle->cached_static_prefix = static_prefix;
                    handle->cached_static_prefix_tokens = static_cast<int>(prefix_tokens.size());
                    context_tokens_used = static_cast<int>(prefix_tokens.size() + tail_tokens.size());
                }
            }
        } else {
            // No known split point (should not happen - every Director prompt
            // builder inserts this marker) - safest fallback, no caching.
            mydnd_clear_memory(handle);

            std::vector<llama_token> whole_tokens =
                    tokenize_new_sequence_text(handle->vocab, prompt);

            if (whole_tokens.empty() || !decode_prompt_tokens(whole_tokens)) {
                if (handle->cancel_requested.load()) {
                    return string_to_jstring(env, "");
                }
                return string_to_jstring(
                        env,
                        "Ошибка: llama_decode не смог обработать tool-aware prompt."
                );
            }

            context_tokens_used = static_cast<int>(whole_tokens.size());
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
                "nativeGenerateDirectorAwareStream: ONE PASS prompt decode = %lld ms, cacheHit=%s, tokensDecoded=%d",
                static_cast<long long>(
                        prompt_decode_ms
                ),
                prefix_cache_used ? "YES" : "NO",
                context_tokens_used
                        - (prefix_cache_used ? handle->cached_static_prefix_tokens : 0)
        );

        if (handle->cancel_requested.load()) {
            return string_to_jstring(
                    env,
                    ""
            );
        }

        /*
         * DIRECTOR PHASE.
         * The prompt is decoded once (partly from cache when possible). Every
         * structural action is then forced by the universal grammar, executed
         * synchronously in Java/Room, and its tool response is decoded back
         * into the SAME llama_context. Nothing is streamed until DONE has
         * been executed.
         */

        /*
         * Keep enough room for a useful answer after Director. Do not reserve
         * world-event memory here: it is best-effort and is checked using its
         * actual token count after the narrative is complete.
         */
        const int post_tool_narrative_reserve = narrative_min_reserve;
        const int max_director_actions_per_turn =
                director_mode == "CHECK_RESULT" ? 2 : 5;

        auto generate_director_action =
                [&](std::string & raw_tool_call,
                    int & generated_tokens,
                    bool force_done,
                    bool allow_check,
                    bool allow_world_actions,
                    const std::string & retry_family,
                    const std::vector<std::string> & disabled_action_types) -> bool {

            llama_token tool_call_open = 0;
            llama_token tool_call_close = 0;
            llama_token tool_string_quote = 0;

            if (!get_single_special_token(handle->vocab, "<|tool_call>", tool_call_open)
                || !get_single_special_token(handle->vocab, "<tool_call|>", tool_call_close)
                || !get_single_special_token(handle->vocab, "<|\"|>", tool_string_quote)) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: director special tokens not found"
                );
                return false;
            }

            raw_tool_call.clear();
            generated_tokens = 0;

            const int context_guard_reserve =
                    tool_response_min_reserve + post_tool_narrative_reserve;

            /*
             * DONE has zero real model choice anywhere - name/value/details are
             * always empty. Decode the entire fixed tool-call in one batch
             * instead of sampling ~20 forced tokens one llama_decode at a time.
             */
            if (force_done) {
                std::vector<llama_token> done_tokens;
                done_tokens.push_back(tool_call_open);
                for (llama_token t : tokenize_plain_text(handle->vocab, "call:director_action{type:")) done_tokens.push_back(t);
                done_tokens.push_back(tool_string_quote);
                for (llama_token t : tokenize_plain_text(handle->vocab, "DONE")) done_tokens.push_back(t);
                done_tokens.push_back(tool_string_quote);
                for (llama_token t : tokenize_plain_text(handle->vocab, ",name:")) done_tokens.push_back(t);
                done_tokens.push_back(tool_string_quote);
                done_tokens.push_back(tool_string_quote);
                for (llama_token t : tokenize_plain_text(handle->vocab, ",value:")) done_tokens.push_back(t);
                done_tokens.push_back(tool_string_quote);
                done_tokens.push_back(tool_string_quote);
                for (llama_token t : tokenize_plain_text(handle->vocab, ",details:")) done_tokens.push_back(t);
                done_tokens.push_back(tool_string_quote);
                done_tokens.push_back(tool_string_quote);
                for (llama_token t : tokenize_plain_text(handle->vocab, "}")) done_tokens.push_back(t);
                done_tokens.push_back(tool_call_close);

                if (context_tokens_used
                        + static_cast<int>(done_tokens.size())
                        + context_guard_reserve
                    >= static_cast<int>(n_ctx)) {
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: director action stopped at context boundary"
                    );
                    return false;
                }

                if (!decode_forced_token_span(
                        handle,
                        done_tokens,
                        raw_tool_call,
                        context_tokens_used,
                        generated_tokens
                )) {
                    return false;
                }

                return is_complete_tool_call(raw_tool_call);
            }

            /*
             * Real action path: "<|tool_call>call:director_action{type:\"" is
             * identical regardless of which branch the model ends up choosing
             * (verified against every rule build_director_action_grammar emits),
             * so decode it once as a batch before the grammar sampler - which
             * now starts its root rule right after this prefix - is even
             * created. Real choices (type/name/value/details) still go through
             * the per-token grammar-constrained loop below, unchanged.
             */
            std::vector<llama_token> prefix_tokens;
            prefix_tokens.push_back(tool_call_open);
            for (llama_token t : tokenize_plain_text(handle->vocab, "call:director_action{type:")) prefix_tokens.push_back(t);
            prefix_tokens.push_back(tool_string_quote);

            if (context_tokens_used
                    + static_cast<int>(prefix_tokens.size())
                    + context_guard_reserve
                >= static_cast<int>(n_ctx)) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: director action stopped at context boundary"
                );
                return false;
            }

            if (!decode_forced_token_span(
                    handle,
                    prefix_tokens,
                    raw_tool_call,
                    context_tokens_used,
                    generated_tokens
            )) {
                return false;
            }

            llama_sampler * decision_sampler =
                    create_director_action_sampler(
                            handle->vocab,
                            prompt,
                            director_mode,
                            allow_check,
                            allow_world_actions,
                            retry_family,
                            disabled_action_types
                    );

            if (decision_sampler == nullptr) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: director grammar init failed"
                );
                return false;
            }

            bool complete = false;

            for (int i = 0; i < decision_max_tokens; i++) {
                if (handle->cancel_requested.load()) {
                    break;
                }

                if (context_tokens_used
                        + tool_response_min_reserve
                        + post_tool_narrative_reserve
                        + 1
                    >= static_cast<int>(n_ctx)) {
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: director action stopped at context boundary"
                    );
                    break;
                }

                llama_token token =
                        llama_sampler_sample(
                                decision_sampler,
                                handle->ctx,
                                -1
                        );

                if (llama_vocab_is_eog(handle->vocab, token)) {
                    break;
                }

                generated_tokens++;

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
                    raw_tool_call.append(buffer, piece_length);
                }

                llama_batch token_batch =
                        llama_batch_get_one(&token, 1);

                if (llama_decode(handle->ctx, token_batch) != 0) {
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: director action token decode failed"
                    );
                    break;
                }

                context_tokens_used++;

                if (is_complete_tool_call(raw_tool_call)) {
                    complete = true;
                    break;
                }
            }

            llama_sampler_free(decision_sampler);
            return complete;
        };

        auto execute_and_decode_tool_response =
                [&](const std::string & raw_tool_call,
                    int tool_number,
                    bool & confirmed_done,
                    bool & action_applied,
                    bool & action_rejected,
                    bool & check_requested,
                    bool & force_done_next) -> bool {

            confirmed_done = false;
            action_applied = false;
            action_rejected = false;
            check_requested = false;
            force_done_next = false;

            MYDND_LOGI(
                    "nativeGenerateDirectorAwareStream: DIRECTOR TOOL #%d RAW = %s",
                    tool_number,
                    raw_tool_call.c_str()
            );

            jstring jToolCall =
                    string_to_jstring(env, raw_tool_call);

            jobject toolResponseObject =
                    env->CallObjectMethod(
                            toolCallbackObject,
                            onToolCallMethod,
                            jToolCall
                    );

            env->DeleteLocalRef(jToolCall);

            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: Java Director callback failed for #%d",
                        tool_number
                );
                return false;
            }

            if (toolResponseObject == nullptr) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: Java Director callback returned null for #%d",
                        tool_number
                );
                return false;
            }

            std::string tool_response =
                    jstring_to_string(
                            env,
                            static_cast<jstring>(toolResponseObject)
                    );
            env->DeleteLocalRef(toolResponseObject);

            if (tool_response.empty()) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: empty Director response for #%d",
                        tool_number
                );
                return false;
            }

            MYDND_LOGI(
                    "nativeGenerateDirectorAwareStream: DIRECTOR RESPONSE #%d = %s",
                    tool_number,
                    tool_response.c_str()
            );

            confirmed_done =
                    is_director_done_call(raw_tool_call)
                    && tool_response.find(
                            "status:<|\"|>NO_CHANGE<|\"|>"
                    ) != std::string::npos;

            action_applied =
                    tool_response.find(
                            "status:<|\"|>APPLIED<|\"|>"
                    ) != std::string::npos;

            action_rejected =
                    tool_response.find(
                            "status:<|\"|>REJECTED<|\"|>"
                    ) != std::string::npos;

            check_requested =
                    action_applied
                    && tool_response.find(
                            "code:<|\"|>CHECK_REQUESTED<|\"|>"
                    ) != std::string::npos;

            force_done_next =
                    tool_response.find(
                            "next:<|\"|>NARRATE_NOW<|\"|>"
                    ) != std::string::npos
                    || tool_response.find(
                            "next:<|\"|>DONE_ONLY<|\"|>"
                    ) != std::string::npos;

            /*
             * CHECK is a hard phase boundary. The Java card is already stored
             * and shown; no tool response needs to be decoded because the
             * current native context will not continue. This saves time and,
             * more importantly, prevents narrative generation before the roll.
             */
            if (check_requested) {
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: CHECK requested at tool #%d; pausing before narrative",
                        tool_number
                );
                return true;
            }

            int response_count =
                    -llama_tokenize(
                            handle->vocab,
                            tool_response.c_str(),
                            static_cast<int32_t>(tool_response.size()),
                            nullptr,
                            0,
                            false,
                            true
                    );

            if (response_count <= 0) {
                return false;
            }

            std::vector<llama_token> response_tokens(response_count);
            int response_tokenized =
                    llama_tokenize(
                            handle->vocab,
                            tool_response.c_str(),
                            static_cast<int32_t>(tool_response.size()),
                            response_tokens.data(),
                            static_cast<int32_t>(response_tokens.size()),
                            false,
                            true
                    );

            if (response_tokenized <= 0) {
                return false;
            }

            if (context_tokens_used
                    + response_tokenized
                    + post_tool_narrative_reserve
                >= static_cast<int>(n_ctx)) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: not enough context for Director response #%d",
                        tool_number
                );
                return false;
            }

            for (int pos = 0; pos < response_tokenized; pos += prompt_chunk_size) {
                if (handle->cancel_requested.load()) {
                    return false;
                }

                int chunk_size = prompt_chunk_size;
                if (pos + chunk_size > response_tokenized) {
                    chunk_size = response_tokenized - pos;
                }

                llama_batch response_batch =
                        llama_batch_get_one(
                                response_tokens.data() + pos,
                                chunk_size
                        );

                if (llama_decode(handle->ctx, response_batch) != 0) {
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: Director response decode failed for #%d",
                            tool_number
                    );
                    return false;
                }
            }

            context_tokens_used += response_tokenized;
            return true;
        };

        bool director_done = false;
        bool director_check_pending = false;
        bool director_protocol_ok = true;
        bool has_applied_action = false;
        bool force_done_next_action = false;
        std::string retry_family;
        std::vector<std::string> disabled_action_types;
        int director_action_count = 0;

        auto director_start = std::chrono::steady_clock::now();

        for (
                int tool_number = 1;
                tool_number <= max_director_actions_per_turn;
                tool_number++
                ) {

            if (handle->cancel_requested.load()) {
                director_protocol_ok = false;
                break;
            }

            std::string raw_tool_call;
            int action_tokens = 0;

            const int remaining_context =
                    static_cast<int>(n_ctx) - context_tokens_used;

            const int force_done_threshold =
                    post_tool_narrative_reserve
                    + tool_response_min_reserve
                    + director_startup_reserve
                    + decision_max_tokens;

            const bool force_done =
                    force_done_next_action
                    || tool_number == max_director_actions_per_turn
                    || remaining_context <= force_done_threshold;

            if (force_done
                    && tool_number < max_director_actions_per_turn) {
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: forcing DONE early, tool=%d, remaining=%d, threshold=%d",
                        tool_number,
                        remaining_context,
                        force_done_threshold
                );
            }

            const bool allow_check =
                    director_mode == "PLAYER_ACTION"
                    && tool_number == 1
                    && retry_family.empty();

            const bool allow_world_actions =
                    director_mode == "PLAYER_ACTION"
                    && tool_number == 1
                    && retry_family.empty();

            if (!force_done) {
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: Director tool #%d check=%s world=%s retry=%s disabled=%zu",
                        tool_number,
                        allow_check ? "ON" : "OFF",
                        allow_world_actions ? "ON" : "OFF",
                        retry_family.empty() ? "NONE" : retry_family.c_str(),
                        disabled_action_types.size()
                );
            }

            const bool was_family_retry = !retry_family.empty();

            if (!generate_director_action(
                    raw_tool_call,
                    action_tokens,
                    force_done,
                    allow_check,
                    allow_world_actions,
                    retry_family,
                    disabled_action_types
            )) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: incomplete Director action #%d: %s",
                        tool_number,
                        raw_tool_call.c_str()
                );
                director_protocol_ok = false;
                break;
            }

            director_action_count++;

            if (!is_director_tool_call_text(raw_tool_call)) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: non-Director action rejected: %s",
                        raw_tool_call.c_str()
                );
                director_protocol_ok = false;
                break;
            }

            bool confirmed_done = false;
            bool action_applied = false;
            bool action_rejected = false;
            bool check_requested = false;
            bool force_done_after_response = false;
            if (!execute_and_decode_tool_response(
                    raw_tool_call,
                    tool_number,
                    confirmed_done,
                    action_applied,
                    action_rejected,
                    check_requested,
                    force_done_after_response
            )) {
                director_protocol_ok = false;
                break;
            }

            const std::string action_type =
                    extract_director_action_type(raw_tool_call);

            bool rejection_forces_done = false;
            bool repaired_rejection = false;

            if (action_applied) {
                has_applied_action = true;
                repaired_rejection = was_family_retry;
                retry_family.clear();

                if (!action_type.empty()
                        && action_type != "DONE"
                        && action_type != "CHECK"
                        && !contains_string(disabled_action_types, action_type)) {
                    /*
                     * One applied action of each type per player turn. This is
                     * intentionally stricter than the old duplicate validator:
                     * the repeated branch disappears before the model can waste
                     * another 10-15 seconds generating the same kind of action.
                     */
                    disabled_action_types.push_back(action_type);
                }
            } else if (action_rejected) {
                const std::string family =
                        director_action_family(action_type);

                if (has_applied_action
                        || director_mode == "CHECK_RESULT"
                        || family.empty()
                        || family == "check"
                        || family == "world") {
                    retry_family.clear();
                    rejection_forces_done = true;
                } else {
                    retry_family = family;
                }
            }

            force_done_next_action =
                    force_done_after_response
                    || rejection_forces_done
                    || repaired_rejection;

            if (check_requested) {
                director_check_pending = true;
                break;
            }

            if (check_result_mode
                    && action_applied
                    && force_done_after_response) {
                director_done = true;
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: CHECK_RESULT auto-finish after applied tool #%d",
                        tool_number
                );
                break;
            }

            if (confirmed_done) {
                director_done = true;
                break;
            }
        }

        auto director_end = std::chrono::steady_clock::now();
        auto director_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                        director_end - director_start
                ).count();

        MYDND_LOGI(
                "nativeGenerateDirectorAwareStream: DIRECTOR = %lld ms, actions=%d, done=%s, check=%s, protocol=%s",
                static_cast<long long>(director_ms),
                director_action_count,
                director_done ? "YES" : "NO",
                director_check_pending ? "YES" : "NO",
                director_protocol_ok ? "OK" : "ERROR"
        );

        if (handle->cancel_requested.load()) {
            return string_to_jstring(env, "");
        }

        if (director_check_pending) {
            MYDND_LOGI(
                    "nativeGenerateDirectorAwareStream: PAUSED FOR CHECK; narrative skipped"
            );
            return string_to_jstring(env, "__MYDND_CHECK_PENDING__");
        }

        if (!director_done) {
            MYDND_LOGE(
                    "nativeGenerateDirectorAwareStream: Director cycle ended without DONE"
            );
            return string_to_jstring(
                    env,
                    "Ошибка: Режиссёр не завершил цикл действий."
            );
        }

        /*
         * NARRATIVE PHASE.
         * Only starts after the Director cycle. Any new tool marker here is a
         * protocol error and is suppressed instead of leaking into the chat.
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
        bool narrative_protocol_stop = false;
        const int soft_min_tokens = narrative_predict * 3 / 4;
        int narrative_text_tokens = 0;
        int generated_phase_tokens = 0;

        auto narrative_start = std::chrono::steady_clock::now();

        for (int i = 0; i < narrative_hard_max; i++) {
            if (handle->cancel_requested.load()) {
                break;
            }

            if (context_tokens_used
                    + 1
                >= static_cast<int>(n_ctx)) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: narrative stopped at context boundary"
                );
                break;
            }

            llama_token token =
                    llama_sampler_sample(
                            narrative_sampler,
                            handle->ctx,
                            -1
                    );

            if (llama_vocab_is_eog(handle->vocab, token)) {
                break;
            }

            generated_phase_tokens++;

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

            std::string piece;
            if (piece_length > 0) {
                piece.assign(buffer, piece_length);
            }

            llama_batch token_batch =
                    llama_batch_get_one(&token, 1);

            if (llama_decode(handle->ctx, token_batch) != 0) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: narrative token decode failed"
                );
                break;
            }

            context_tokens_used++;

            if (!piece.empty()) {
                size_t tool_start = piece.find("<|tool_call>");
                if (tool_start != std::string::npos) {
                    std::string prefix = piece.substr(0, tool_start);
                    if (!prefix.empty()) {
                        narrative_output.append(prefix);
                        streaming_pending.append(prefix);
                    }
                    narrative_protocol_stop = true;
                    MYDND_LOGE(
                            "nativeGenerateDirectorAwareStream: late tool call suppressed after DONE"
                    );
                    break;
                }

                narrative_output.append(piece);
                streaming_pending.append(piece);
                narrative_text_tokens++;

                size_t ready_length =
                        complete_utf8_prefix_length(streaming_pending);

                if (ready_length > 0) {
                    std::string ready_text =
                            streaming_pending.substr(0, ready_length);
                    streaming_pending.erase(0, ready_length);

                    jstring jToken = string_to_jstring(env, ready_text);
                    if (jToken != nullptr) {
                        env->CallVoidMethod(
                                tokenCallbackObject,
                                onTokenMethod,
                                jToken
                        );
                        env->DeleteLocalRef(jToken);
                    }

                    if (env->ExceptionCheck()) {
                        env->ExceptionDescribe();
                        env->ExceptionClear();
                        narrative_protocol_stop = true;
                        break;
                    }
                }
            }

            if (narrative_text_tokens >= soft_min_tokens
                && ends_with_sentence_mark(narrative_output)) {
                break;
            }
        }

        if (!streaming_pending.empty()) {
            jstring jToken = string_to_jstring(env, streaming_pending);
            if (jToken != nullptr) {
                env->CallVoidMethod(
                        tokenCallbackObject,
                        onTokenMethod,
                        jToken
                );
                env->DeleteLocalRef(jToken);
            }
        }

        auto narrative_end = std::chrono::steady_clock::now();
        auto narrative_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                        narrative_end - narrative_start
                ).count();

        double tokens_per_second = 0.0;
        if (narrative_ms > 0) {
            tokens_per_second =
                    generated_phase_tokens * 1000.0
                    / static_cast<double>(narrative_ms);
        }

        llama_sampler_free(narrative_sampler);

        MYDND_LOGI(
                "nativeGenerateDirectorAwareStream: narrative = %lld ms, textTokens=%d, generatedTokens=%d, protocolStop=%s, speed=%.2f tok/s",
                static_cast<long long>(narrative_ms),
                narrative_text_tokens,
                generated_phase_tokens,
                narrative_protocol_stop ? "YES" : "NO",
                tokens_per_second
        );

        /*
         * Фаза 4: память живого мира запускается только раз в 5 ходов.
         * Java передаёт четыре предыдущих ответа мастера отдельно.
         * Пятый, текущий ответ уже находится прямо выше в llama_context.
         */
        if (should_run_world_event_phase
            && !handle->cancel_requested.load()) {

            std::string world_event_instruction =
                    "<turn|>\n<|turn>user\n"
                    "Служебная проверка памяти мира. "
                    "Оцени только четыре предыдущих ответа мастера ниже "
                    "и текущий завершённый ответ мастера прямо выше. "
                    "Игнорируй описание мира, старые WORLD_CHANGES, инвентарь "
                    "и действия игрока как самостоятельные факты. "
                    "Сохрани максимум один НОВЫЙ и действительно важный "
                    "долговечный факт общего мира, который должен помнить будущий герой: "
                    "смерть важного персонажа, смена власти, начало или конец войны, "
                    "разрушение важного места, появление или исчезновение важной организации, "
                    "существенное устойчивое изменение важного NPC или локации. "
                    "Не сохраняй атмосферу, обычное движение, обычные предметы, "
                    "повтор уже известного факта и мелкие бытовые события. "
                    "Если сохраняешь событие, укажи importance: "
                    "1 = локальное и небольшое, 2 = важное региональное, "
                    "3 = переломное событие уровня власти, войны, города или крупной организации. "
                    "Формат: remember_world_event с text и importance. "
                    "Если подходящего нового факта нет, вызови no_world_event.\n"
                    "ПРЕДЫДУЩИЕ ОТВЕТЫ МАСТЕРА:\n";

            world_event_instruction += world_event_batch_text;

            world_event_instruction +=
                    "\n<turn|>\n<|turn>model\n";

            MYDND_LOGI(
                    "nativeGenerateDirectorAwareStream: WORLD EVENT CHECK ENABLED, batchChars=%zu",
                    world_event_batch_text.size()
            );

            int event_instruction_count =
                    -llama_tokenize(
                            handle->vocab,
                            world_event_instruction.c_str(),
                            static_cast<int32_t>(world_event_instruction.size()),
                            nullptr,
                            0,
                            false,
                            true
                    );

            if (event_instruction_count > 0
                && context_tokens_used
                   + event_instruction_count
                   + world_event_max_tokens
                   + 1
                   < static_cast<int>(n_ctx)) {

                std::vector<llama_token> event_instruction_tokens(
                        event_instruction_count
                );

                int event_instruction_tokenized =
                        llama_tokenize(
                                handle->vocab,
                                world_event_instruction.c_str(),
                                static_cast<int32_t>(world_event_instruction.size()),
                                event_instruction_tokens.data(),
                                static_cast<int32_t>(event_instruction_tokens.size()),
                                false,
                                true
                        );

                bool event_instruction_ok =
                        event_instruction_tokenized > 0;

                auto event_phase_start =
                        std::chrono::steady_clock::now();

                for (
                        int pos = 0;
                        event_instruction_ok && pos < event_instruction_tokenized;
                        pos += prompt_chunk_size
                        ) {

                    int chunk_size = prompt_chunk_size;

                    if (pos + chunk_size > event_instruction_tokenized) {
                        chunk_size = event_instruction_tokenized - pos;
                    }

                    llama_batch event_instruction_batch =
                            llama_batch_get_one(
                                    event_instruction_tokens.data() + pos,
                                    chunk_size
                            );

                    if (llama_decode(
                            handle->ctx,
                            event_instruction_batch
                    ) != 0) {

                        event_instruction_ok = false;

                        MYDND_LOGE(
                                "nativeGenerateDirectorAwareStream: world event instruction decode failed, pos=%d",
                                pos
                        );
                    }
                }

                if (event_instruction_ok
                    && !handle->cancel_requested.load()) {

                    llama_sampler * world_event_sampler =
                            create_world_event_sampler(
                                    handle->vocab
                            );

                    if (world_event_sampler != nullptr) {
                        std::string world_event_output;
                        bool world_event_complete = false;
                        int world_event_tokens = 0;

                        for (
                                int i = 0;
                                i < world_event_max_tokens;
                                i++
                                ) {

                            if (handle->cancel_requested.load()) {
                                break;
                            }

                            llama_token event_token =
                                    llama_sampler_sample(
                                            world_event_sampler,
                                            handle->ctx,
                                            -1
                                    );

                            if (llama_vocab_is_eog(
                                    handle->vocab,
                                    event_token
                            )) {
                                break;
                            }

                            world_event_tokens++;

                            char event_buffer[256];

                            int event_piece_length =
                                    llama_token_to_piece(
                                            handle->vocab,
                                            event_token,
                                            event_buffer,
                                            sizeof(event_buffer),
                                            0,
                                            true
                                    );

                            if (event_piece_length > 0) {
                                world_event_output.append(
                                        event_buffer,
                                        event_piece_length
                                );
                            }

                            llama_batch event_token_batch =
                                    llama_batch_get_one(
                                            &event_token,
                                            1
                                    );

                            if (llama_decode(
                                    handle->ctx,
                                    event_token_batch
                            ) != 0) {

                                MYDND_LOGE(
                                        "nativeGenerateDirectorAwareStream: world event token decode failed"
                                );

                                break;
                            }

                            if (is_complete_tool_call(
                                    world_event_output
                            )) {
                                world_event_complete = true;
                                break;
                            }
                        }

                        llama_sampler_free(
                                world_event_sampler
                        );

                        auto event_phase_end =
                                std::chrono::steady_clock::now();

                        auto event_phase_ms =
                                std::chrono::duration_cast<
                                        std::chrono::milliseconds
                                >(
                                        event_phase_end
                                        - event_phase_start
                                ).count();

                        MYDND_LOGI(
                                "nativeGenerateDirectorAwareStream: WORLD EVENT = %lld ms, tokens=%d, complete=%s, raw=%s",
                                static_cast<long long>(event_phase_ms),
                                world_event_tokens,
                                world_event_complete ? "YES" : "NO",
                                world_event_output.c_str()
                        );

                        if (world_event_complete
                            && !world_event_output.empty()
                            && !handle->cancel_requested.load()) {

                            jstring jWorldEventCall =
                                    string_to_jstring(
                                            env,
                                            world_event_output
                                    );

                            jobject ignoredResponse =
                                    env->CallObjectMethod(
                                            toolCallbackObject,
                                            onToolCallMethod,
                                            jWorldEventCall
                                    );

                            env->DeleteLocalRef(
                                    jWorldEventCall
                            );

                            if (ignoredResponse != nullptr) {
                                env->DeleteLocalRef(
                                        ignoredResponse
                                );
                            }

                            if (env->ExceptionCheck()) {
                                env->ExceptionDescribe();
                                env->ExceptionClear();
                            }
                        }
                    }
                }
            } else {
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: WORLD EVENT SKIPPED, not enough remaining context (used=%d, instruction=%d, maxOutput=%d, ctx=%u)",
                        context_tokens_used,
                        event_instruction_count,
                        world_event_max_tokens,
                        n_ctx
                );
            }
        } else {
            MYDND_LOGI(
                    "nativeGenerateDirectorAwareStream: WORLD EVENT SKIPPED"
            );
        }

        MYDND_LOGI(
                "nativeGenerateDirectorAwareStream: FINISH, narrative length=%zu",
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
                        "Ошибка nativeGenerateDirectorAwareStream: "
                ) + ex.what()
        );
    } catch (...) {
        return string_to_jstring(
                env,
                "Ошибка nativeGenerateDirectorAwareStream: неизвестная ошибка."
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
        llama_detach_threadpool(handle->ctx);
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }

    if (handle->model != nullptr) {
        llama_model_free(handle->model);
        handle->model = nullptr;
    }

    if (handle->threadpool != nullptr) {
        ggml_threadpool_free(handle->threadpool);
        handle->threadpool = nullptr;
    }

    delete handle;
}