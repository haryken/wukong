package com.ubtrobot.mini.speech.framework.demo;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bảng intent built-in UBT (SkillHelper / speech category) theo tài liệu hãng + {@code call-builtin-skills.txt}.
 * Dùng để map MCP / tên tool → {@code SkillHelper.startSkillByIntent(utterance, ...)}.
 */
public final class UbtechBuiltinSkillCatalog {

    private static final Map<String, String> LOWER_TO_CANONICAL = new HashMap<>();
    /** Chuỗi con trong {@code toolName} (lowercase) → intent chuẩn; thứ tự chèn = ưu tiên (chuỗi dài/cụ thể trước). */
    private static final LinkedHashMap<String, String> SUBSTRING_TO_SKILL = new LinkedHashMap<>();

    static {
        reg("TAIJI");
        reg("exercise");
        reg("yoga");
        reg("kungfu");
        reg("sit_down");
        reg("foot_stand");
        reg("fly");
        reg("stand_up");
        reg("raisinghands");
        reg("raisingrightleg");
        reg("raisingleftleg");
        reg("stoop");
        reg("Keep_turning_right");
        reg("Keep_turning_left");
        reg("Keep_moving_forward");
        reg("Keep_going_backwards");
        reg("crouch");
        reg("bow_step");
        reg("BOW_STEP");
        reg("say_hi");
        reg("sneeze");
        reg("burp");
        reg("break_wind");
        reg("scratch");
        reg("hand_kiss");
        reg("hug");
        reg("Stretch");
        reg("doze");
        reg("frighten");
        reg("shake_hand");
        reg("nod");
        reg("shake_head");
        reg("turn_head");
        reg("be_cute");
        reg("dancing");
        reg("what_can");
        reg("how_bind");
        reg("how_connect");
        reg("how_volume");
        reg("how_stop");
        reg("how_shutoff");
        reg("how_rest");
        reg("how_lte");
        reg("how_photo");
        reg("FOURG_statu");
        reg("charge_statu");
        reg("which_wifi");
        reg("how_battery");
        reg("how_content");
        reg("how_phone");
        reg("wifi_switch");
        reg("volume_turn_down_min");
        reg("volume_turn_down");
        reg("volume_turn_mid");
        reg("volume_turn_to");
        reg("volume_turn_up_max");
        reg("volume_turn_up");
        reg("turn_off_robot");
        reg("SHUT_DOWN");
        putSub("shut_down", "SHUT_DOWN");

        /* Không map substring sit_down/stand_up theo *tên tool*: self.otto.sit_down / stand_up → StandUpApi trong MiniRobotActionInvoker; skill cùng tên vẫn gọi được qua arguments (skill_intent, …). */
        putSub("foot_stand", "foot_stand");
        /** walk_forward / go_back / direction Otto: xử lý trong {@link com.ubtrobot.mini.speech.framework.demo.MiniRobotActionInvoker} (đọc {@code direction}). */
        putSub("shake_hand", "shake_hand");
        putSub("raisinghands", "raisinghands");
        putSub("raisingrightleg", "raisingrightleg");
        putSub("raisingleftleg", "raisingleftleg");
        putSub("keep_turning_right", "Keep_turning_right");
        putSub("keep_turning_left", "Keep_turning_left");
        putSub("keep_moving_forward", "Keep_moving_forward");
        putSub("keep_going_backwards", "Keep_going_backwards");
        /** ROM SkillApi.SKILL_NAME — đi tới / lùi (ưu tiên trong {@link MiniRobotActionInvoker}). */
        reg("GO_AHEAD");
        reg("BACK_UP");
        putSub("go_ahead", "GO_AHEAD");
        putSub("back_up", "BACK_UP");
        reg("TURN_LEFT");
        reg("TURN_RIGHT");
        putSub("turn_left", "TURN_LEFT");
        putSub("turn_right", "TURN_RIGHT");
        putSub("bow_step", "BOW_STEP");
        putSub("taiji", "TAIJI");
        putSub("tai_chi", "TAIJI");
        putSub("thái cực", "TAIJI");
        putSub("thai cuc", "TAIJI");
        putSub("hand_kiss", "hand_kiss");
        putSub("break_wind", "break_wind");
        putSub("shake_head", "shake_head");
        putSub("turn_head", "turn_head");
        putSub("be_cute", "be_cute");
        putSub("how_battery", "how_battery");
        putSub("how_photo", "how_photo");
        putSub("how_volume", "how_volume");
        putSub("how_connect", "how_connect");
        putSub("how_bind", "how_bind");
        putSub("how_stop", "how_stop");
        putSub("how_shutoff", "how_shutoff");
        putSub("how_rest", "how_rest");
        putSub("how_lte", "how_lte");
        putSub("how_content", "how_content");
        putSub("how_phone", "how_phone");
        putSub("fourg_statu", "FOURG_statu");
        putSub("charge_statu", "charge_statu");
        putSub("which_wifi", "which_wifi");
        putSub("what_can", "what_can");
        putSub("volume_turn_down_min", "volume_turn_down_min");
        putSub("volume_turn_down", "volume_turn_down");
        putSub("volume_turn_mid", "volume_turn_mid");
        putSub("volume_turn_to", "volume_turn_to");
        putSub("volume_turn_up_max", "volume_turn_up_max");
        putSub("volume_turn_up", "volume_turn_up");
        putSub("turn_off_robot", "turn_off_robot");
        putSub("wifi_switch", "wifi_switch");
        putSub("exercise", "exercise");
        putSub("kungfu", "kungfu");
        putSub("dancing", "dancing");
        putSub("stretch", "Stretch");
        putSub("frighten", "frighten");
        putSub("scratch", "scratch");
        putSub("burp", "burp");
        putSub("sneeze", "sneeze");
        putSub("say_hi", "say_hi");
        putSub("crouch", "crouch");
        putSub("stoop", "stoop");
        putSub("fly", "fly");
        putSub("yoga", "yoga");
        putSub("doze", "doze");
        putSub("hug", "hug");
        putSub("nod", "nod");
        /** Otto MCP → Alpha Mini: tay nhỏ / skill tương ứng (ROM có thì mới chạy). */
        putSub("hands_up", "raisinghands");
        putSub("hands_down", "foot_stand");
        putSub("hand_wave", "shake_hand");
    }

    private static void reg(String canonical) {
        LOWER_TO_CANONICAL.put(canonical.toLowerCase(Locale.US), canonical);
    }

    private static void putSub(String needleLower, String canonicalIntent) {
        SUBSTRING_TO_SKILL.put(needleLower, canonicalIntent);
    }

    private UbtechBuiltinSkillCatalog() {}

    /** Chuẩn hóa {@code keep_moving_forward} → {@code Keep_moving_forward} cho SkillHelper; không đổi nếu không có trong bảng. */
    public static String toCanonicalIfKnown(String utterance) {
        if (utterance == null || utterance.isEmpty()) return utterance;
        String canon = LOWER_TO_CANONICAL.get(utterance.toLowerCase(Locale.US));
        return canon != null ? canon : utterance;
    }

    /** MCP: {@code skill_intent} / {@code intent} / {@code utterance} / {@code skill} khớp bảng built-in. */
    public static String resolveFromArguments(JSONObject arguments) {
        if (arguments == null) return null;
        String[] keys = {"skill_intent", "intent", "utterance", "skill", "skill_name"};
        for (String k : keys) {
            String v = arguments.optString(k, "").trim();
            if (v.isEmpty()) continue;
            String canon = LOWER_TO_CANONICAL.get(v.toLowerCase(Locale.US));
            if (canon != null) return canon;
        }
        return null;
    }

    /** Khớp chuỗi con trong tên tool (đã lowercase). */
    public static String resolveFromToolName(String toolNameLower) {
        if (toolNameLower == null || toolNameLower.isEmpty()) return null;
        for (Map.Entry<String, String> e : SUBSTRING_TO_SKILL.entrySet()) {
            if (toolNameLower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
