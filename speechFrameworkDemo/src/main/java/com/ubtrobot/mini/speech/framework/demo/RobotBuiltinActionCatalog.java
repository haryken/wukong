package com.ubtrobot.mini.speech.framework.demo;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tên tài nguyên action/dance/emoticon built-in theo tài liệu hãng (Alpha Mini / ActionApi.playAction).
 * Dùng để map MCP/LLM → đúng {@code resource id}; emoticon có thể cùng pipeline playAction tùy firmware.
 */
public final class RobotBuiltinActionCatalog {

    /** Mặc định “nhảy múa” — mini-outer-sdk-demo dùng {@code dance_0002}; nhiều ROM không có {@code dance_0002en} (lỗi 10005 file not found). */
    public static final String DANCE_DEFAULT = "dance_0002";

    public static final String W_STAND_0001 = "w_stand_0001";
    public static final String W_STAND_0002 = "w_stand_0002";
    public static final String W_STAND_0003 = "w_stand_0003";
    public static final String W_STAND_0008 = "w_stand_0008";
    public static final String W_STAND_0009 = "w_stand_0009";
    public static final String W_STAND_0010 = "w_stand_0010";
    public static final String DANCE_0001EN = "dance_0001en";
    public static final String DANCE_0002EN = "dance_0002en";
    public static final String DANCE_0003EN = "dance_0003en";
    public static final String DANCE_0004EN = "dance_0004en";
    public static final String DANCE_0005EN = "dance_0005en";
    public static final String DANCE_0006EN = "dance_0006en";
    public static final String DANCE_0007EN = "dance_0007en";
    public static final String DANCE_0008EN = "dance_0008en";
    public static final String DANCE_0009EN = "dance_0009en";
    public static final String DANCE_0011EN = "dance_0011en";
    public static final String DANCE_0013 = "dance_0013";
    public static final String CUSTOM_0035 = "custom_0035";
    public static final String ACTION_014_TAI_CHI = "014";

    public static final String ACTION_009_RESET = "009";
    public static final String ACTION_012_PUSHUPS = "012";
    public static final String ACTION_016_GOLDEN_ROOSTER = "016";
    public static final String ACTION_024_YOGA = "024";
    public static final String ACTION_010_LAUGH = "010";
    public static final String RANDOM_SHORT2_HUG = "random_short2";
    public static final String ACTION_027_SIT = "027";
    public static final String ACTION_031_SQUAT = "031";
    public static final String ACTION_021_BENT_OVER = "021";
    public static final String ACTION_013_KUNG_FU = "013";
    public static final String ACTION_018_RAISE_RIGHT_LEG = "018";
    public static final String ACTION_019_RAISE_LEFT_LEG = "019";
    public static final String ACTION_017_RAISE_HANDS = "017";
    public static final String ACTION_015_WELCOME = "015";
    public static final String ACTION_011_NOD = "011";
    public static final String RANDOM_SHORT3_WAVE_LEFT = "random_short3";
    public static final String RANDOM_SHORT4_WAVE_RIGHT = "random_short4";
    public static final String ACTION_028_RIGHT_LUNGE = "028";
    public static final String ACTION_037_SHAKE_HEAD = "037";
    public static final String ACTION_038_TILT_HEAD = "038";
    public static final String SURVEILLANCE_001_HELLO = "Surveillance_001";
    public static final String SURVEILLANCE_003_HANDSHAKE = "Surveillance_003";
    public static final String SURVEILLANCE_004_BLOW_KISSES = "Surveillance_004";
    public static final String SURVEILLANCE_006_CUTE = "Surveillance_006";
    public static final String ACTION_007_SIT_STAND = "007";
    public static final String ACTION_014_INVITE = "action_014";
    public static final String ACTION_016_GOODBYE = "action_016";
    public static final String ACTION_012_SEEK_HUG = "action_012";
    public static final String ACTION_004_WOW = "action_004";
    public static final String ACTION_005_LIKE = "action_005";
    public static final String ACTION_006_OK = "action_006";
    public static final String ACTION_018_HEY_HA = "action_018";
    public static final String ACTION_020 = "action_020";
    public static final String ACTION_011_FLY = "action_011";
    public static final String ACTION_013_FACES = "action_013";
    public static final String ACTION_015_ASS_TWIST = "action_015";
    public static final String ACTION_007_KILL = "action_007";
    public static final String ACTION_019_HOLD_HEAD = "action_019";

    /**
     * Chuỗi con trong tên tool (lowercase) → id tài nguyên. Thứ tự chèn = độ ưu tiên (chuỗi cụ thể trước).
     */
    private static final LinkedHashMap<String, String> SUBSTRING_TO_ID = new LinkedHashMap<>();

    static {
        putKw("healthy song", DANCE_0001EN);
        putKw("youth training", DANCE_0003EN);
        putKw("little star", DANCE_0004EN);
        putKw("grass dance", DANCE_0005EN);
        putKw("seaweed dance", DANCE_0006EN);
        putKw("taking a bath", DANCE_0007EN);
        putKw("chongerfei", DANCE_0008EN);
        putKw("learn to meow", DANCE_0009EN);
        putKw("dura dance", DANCE_0011EN);
        putKw("buddha girl", DANCE_0013);
        putKw("shaolin", DANCE_DEFAULT);
        putKw("sinh nhật", CUSTOM_0035);
        putKw("happy birthday", CUSTOM_0035);
        putKw("birthday", CUSTOM_0035);
        putKw("tai chi", ACTION_014_TAI_CHI);
        putKw("thái cực", ACTION_014_TAI_CHI);
        putKw("push-up", ACTION_012_PUSHUPS);
        putKw("pushup", ACTION_012_PUSHUPS);
        putKw("chống đẩy", ACTION_012_PUSHUPS);
        putKw("yoga", ACTION_024_YOGA);
        putKw("reset", ACTION_009_RESET);
        putKw("laugh", ACTION_010_LAUGH);
        putKw("cười", ACTION_010_LAUGH);
        putKw("hug", RANDOM_SHORT2_HUG);
        putKw("ôm", RANDOM_SHORT2_HUG);
        putKw("wave left", RANDOM_SHORT3_WAVE_LEFT);
        putKw("waving left", RANDOM_SHORT3_WAVE_LEFT);
        putKw("wave right", RANDOM_SHORT4_WAVE_RIGHT);
        putKw("waving right", RANDOM_SHORT4_WAVE_RIGHT);
        putKw("vẫy tay trái", RANDOM_SHORT3_WAVE_LEFT);
        putKw("vẫy tay phải", RANDOM_SHORT4_WAVE_RIGHT);
        putKw("welcome", ACTION_015_WELCOME);
        putKw("chào mừng", ACTION_015_WELCOME);
        putKw("nodding", ACTION_011_NOD);
        putKw("gật đầu", ACTION_011_NOD);
        putKw("handshake", SURVEILLANCE_003_HANDSHAKE);
        putKw("bắt tay", SURVEILLANCE_003_HANDSHAKE);
        putKw("kiss", SURVEILLANCE_004_BLOW_KISSES);
        putKw("hôn gió", SURVEILLANCE_004_BLOW_KISSES);
        putKw("say hello", SURVEILLANCE_001_HELLO);
        putKw("xin chào", SURVEILLANCE_001_HELLO);
        putKw("goodbye", ACTION_016_GOODBYE);
        putKw("tạm biệt", ACTION_016_GOODBYE);
        putKw("kung fu", ACTION_013_KUNG_FU);
        putKw("công phu", ACTION_013_KUNG_FU);
        putKw("hiccup", W_STAND_0001);
        putKw("fart", W_STAND_0002);
        putKw("stretching", W_STAND_0003);
        putKw("sneeze", W_STAND_0008);
        putKw("hắt hơi", W_STAND_0008);
        putKw("tickle", W_STAND_0009);
        putKw("scare", W_STAND_0010);
        putKw("nhảy múa", DANCE_DEFAULT);
        putKw("nhảy", DANCE_DEFAULT);
        putKw("múa", DANCE_DEFAULT);
        putKw("dance", DANCE_DEFAULT);
    }

    private static void putKw(String substringLower, String id) {
        SUBSTRING_TO_ID.put(substringLower.toLowerCase(Locale.US), id);
    }

    private RobotBuiltinActionCatalog() {}

    /** Chỉ khi JSON có id tài nguyên rõ ràng (MCP arguments). */
    public static String resolveExplicitOnly(JSONObject arguments) {
        String explicit = optFirstNonEmpty(arguments,
                "action_id", "id", "action", "resource", "play_id", "motion_id");
        if (explicit.isEmpty()) return "";
        return normalizeResourceId(explicit);
    }

    /**
     * Ưu tiên: tham số explicit → map theo từ khóa trong tool name (chỉ khi tool là motion) → mặc định dance / kung fu.
     */
    public static String resolvePlayActionId(String toolNameLower, JSONObject arguments, boolean toolImpliesMotion) {
        String fromExplicit = resolveExplicitOnly(arguments);
        if (!fromExplicit.isEmpty()) {
            return fromExplicit;
        }
        if (!toolImpliesMotion) {
            return "";
        }
        for (Map.Entry<String, String> e : SUBSTRING_TO_ID.entrySet()) {
            if (toolNameLower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        if (toolNameLower.contains("moonwalk")) {
            return DANCE_DEFAULT;
        }
        if (toolNameLower.contains("jump")) {
            return DANCE_DEFAULT;
        }
        return DANCE_DEFAULT;
    }

    private static String optFirstNonEmpty(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /**
     * Chuẩn hóa id cũ (demo dùng {@code dance_0002} không hậu tố en) sang tên trong docs nếu khớp.
     */
    public static String normalizeResourceId(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        String lower = t.toLowerCase(Locale.US);
        switch (lower) {
            case "dance_0001": return DANCE_0001EN;
            case "dance_0002":
                return "dance_0002";
            case "dance_0003": return DANCE_0003EN;
            case "dance_0004": return DANCE_0004EN;
            case "dance_0005": return DANCE_0005EN;
            case "dance_0006": return DANCE_0006EN;
            case "dance_0007": return DANCE_0007EN;
            case "dance_0008": return DANCE_0008EN;
            case "dance_0009": return DANCE_0009EN;
            case "dance_0011": return DANCE_0011EN;
            default:
                return t;
        }
    }
}
