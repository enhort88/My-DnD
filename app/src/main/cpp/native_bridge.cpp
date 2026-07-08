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
#include <cstring>

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
        bool allow_world_update
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

    std::vector<std::string> branches;
    branches.push_back("done");

    if (player_action_mode) {
        branches.push_back("check");
        branches.push_back("inv-add");
        if (!inventory.empty()) branches.push_back("inv-remove");
        branches.push_back("hp");
        branches.push_back("money");
        branches.push_back("npc-upsert");
        if (!npcs.empty()) {
            branches.push_back("npc-memory");
            branches.push_back("npc-status");
        }
        branches.push_back("world-add");
        if (allow_world_update && !world_events.empty()) branches.push_back("world-update");
        if (!world_events.empty()) branches.push_back("world-resolve");
        branches.push_back("quest-start");
        if (!quests.empty()) {
            branches.push_back("quest-update");
            branches.push_back("quest-complete");
            branches.push_back("quest-fail");
        }
        branches.push_back("ability-add");
        if (!abilities.empty()) {
            branches.push_back("ability-update");
            branches.push_back("ability-remove");
        }
        branches.push_back("effect-add");
        if (!effects.empty()) branches.push_back("effect-remove");
        branches.push_back("location");
    } else if (check_result_mode) {
        branches.push_back("hp");
        branches.push_back("effect-add");
        branches.push_back("location");
    } else {
        branches.push_back("world-add");
        branches.push_back("npc-upsert");
        branches.push_back("quest-start");
        branches.push_back("effect-add");
    }

    auto field = [&](const std::string & rule) -> std::string {
        if (rule.empty()) {
            return quote + " " + quote;
        }
        return quote + " " + rule + " " + quote;
    };

    auto action_rhs = [&](const std::string & type,
                          const std::string & name_rule,
                          const std::string & value_rule,
                          const std::string & details_rule) -> std::string {
        std::string rhs;
        rhs += open;
        rhs += " \"call:director_action{type:\" ";
        rhs += quote;
        rhs += " \"" + type + "\" ";
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
    grammar += "hp ::= " + action_rhs("HP", "hp-target", "signed-int", "details-text") + "\n";
    grammar += "money ::= " + action_rhs("MONEY", "player-target", "signed-int", "details-text") + "\n";
    grammar += "npc-upsert ::= " + action_rhs("NPC_UPSERT", "free-name", "", "details-text") + "\n";
    if (!npcs.empty()) {
        grammar += "npc-memory ::= " + action_rhs("NPC_MEMORY", "npc-name", "memory-tone", "details-text") + "\n";
        grammar += "npc-status ::= " + action_rhs("NPC_STATUS", "npc-name", "npc-status-value", "details-opt") + "\n";
    }
    grammar += "world-add ::= " + action_rhs("WORLD_ADD", "free-name", "importance-opt", "details-text") + "\n";
    if (allow_world_update && !world_events.empty()) {
        grammar += "world-update ::= " + action_rhs("WORLD_UPDATE", "world-event-name", "importance-opt", "details-text") + "\n";
    }
    if (!world_events.empty()) {
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
    grammar += "check-stat ::= \"STR\" | \"DEX\" | \"INT\" | \"CHA\"\n";
    grammar += "check-dc ::= \"5\" | \"6\" | \"7\" | \"8\" | \"9\" | \"10\" | \"11\" | \"12\" | \"13\" | \"14\" | \"15\" | \"16\" | \"17\" | \"18\" | \"19\" | \"20\" | \"21\" | \"22\" | \"23\" | \"24\" | \"25\"\n";
    grammar += "player-target ::= \"PLAYER\"\n";
    grammar += "memory-tone ::= \"GOOD\" | \"BAD\" | \"NEUTRAL\"\n";
    grammar += "npc-status-value ::= \"ACTIVE\" | \"KNOWN\" | \"INACTIVE\" | \"DEAD\" | \"MISSING\" | \"HOSTILE\" | \"ALLY\"\n";
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
            "director strict grammar context: mode=%s inventory=%zu npcs=%zu quests=%zu world=%zu abilities=%zu effects=%zu worldUpdate=%s",
            director_mode.c_str(),
            inventory.size(),
            npcs.size(),
            quests.size(),
            world_events.size(),
            abilities.size(),
            effects.size(),
            allow_world_update ? "ON" : "OFF"
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
        bool allow_world_update
) {
    std::string grammar =
            build_director_action_grammar(
                    vocab,
                    prompt,
                    director_mode,
                    allow_world_update
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
                "nativeGenerateDirectorAwareStream: prompt tokenized = %d",
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
                        "nativeGenerateDirectorAwareStream: cancelled during prompt decode, pos=%d",
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
                        "nativeGenerateDirectorAwareStream: prompt decode failed, pos=%d, chunk=%d",
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
                "nativeGenerateDirectorAwareStream: ONE PASS prompt decode = %lld ms",
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
         * DIRECTOR PHASE.
         * The prompt is decoded once. Every structural action is then forced by
         * the universal grammar, executed synchronously in Java/Room, and its
         * tool response is decoded back into the SAME llama_context.
         * Nothing is streamed until DONE has been executed.
         */
        int context_tokens_used = tokenized;

        /*
         * Keep enough room for a useful answer after Director. Do not reserve
         * world-event memory here: it is best-effort and is checked using its
         * actual token count after the narrative is complete.
         */
        const int post_tool_narrative_reserve = narrative_min_reserve;
        const int max_director_actions_per_turn =
                director_mode == "CHECK_RESULT" ? 3 : 5;

        auto generate_director_action =
                [&](std::string & raw_tool_call,
                    int & generated_tokens,
                    bool force_done,
                    bool allow_world_update) -> bool {

            llama_sampler * decision_sampler =
                    force_done
                            ? create_director_done_sampler(handle->vocab)
                            : create_director_action_sampler(
                                    handle->vocab,
                                    prompt,
                                    director_mode,
                                    allow_world_update
                            );

            if (decision_sampler == nullptr) {
                MYDND_LOGE(
                        "nativeGenerateDirectorAwareStream: director grammar init failed"
                );
                return false;
            }

            raw_tool_call.clear();
            generated_tokens = 0;
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
                    bool & check_requested,
                    bool & force_done_next) -> bool {

            confirmed_done = false;
            action_applied = false;
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

            check_requested =
                    action_applied
                    && tool_response.find(
                            "code:<|\"|>CHECK_REQUESTED<|\"|>"
                    ) != std::string::npos;

            force_done_next =
                    tool_response.find(
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

            /*
             * Do not start another free-form structural action unless there is
             * room for that action, its Java response, the final DONE runway
             * and a useful narrative. This prevents a small model from burning
             * the last context on world/NPC churn and dying halfway through DONE.
             */
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

            const bool allow_world_update =
                    !has_applied_action;

            if (!force_done) {
                MYDND_LOGI(
                        "nativeGenerateDirectorAwareStream: Director tool #%d WORLD_UPDATE=%s",
                        tool_number,
                        allow_world_update ? "ON" : "OFF"
                );
            }

            if (!generate_director_action(
                    raw_tool_call,
                    action_tokens,
                    force_done,
                    allow_world_update
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
            bool check_requested = false;
            bool force_done_after_response = false;
            if (!execute_and_decode_tool_response(
                    raw_tool_call,
                    tool_number,
                    confirmed_done,
                    action_applied,
                    check_requested,
                    force_done_after_response
            )) {
                director_protocol_ok = false;
                break;
            }

            if (action_applied) {
                has_applied_action = true;
            }

            force_done_next_action = force_done_after_response;

            if (check_requested) {
                director_check_pending = true;
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
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }

    if (handle->model != nullptr) {
        llama_model_free(handle->model);
        handle->model = nullptr;
    }

    delete handle;
}