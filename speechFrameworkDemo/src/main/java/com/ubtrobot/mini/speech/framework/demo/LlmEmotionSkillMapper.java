package com.ubtrobot.mini.speech.framework.demo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Map {@code emotion} server ({@code type:"llm"}) → skill ROM (không dùng {@code SAY_HI} — có âm hệ thống chen TTS):
 * {@code SCRATCH}, {@code HAND_KISS}, {@code HUG}, {@code BE_CUTE}, {@code FRIGHTEN}, {@code NOD}, {@code SHAKE_HAND}.
 * Nhóm chào/vui (happy, greeting, …) → random
 * {@code BE_CUTE}/{@code HAND_KISS}/{@code SCRATCH}/{@code HUG}/{@code NOD}/{@code SHAKE_HAND}.
 * Không khớp → {@link #DEFAULT_SKILL} ({@code BE_CUTE}).
 */
public final class LlmEmotionSkillMapper {

    public static final String SKILL_SCRATCH = "SCRATCH";
    public static final String SKILL_HAND_KISS = "HAND_KISS";
    public static final String SKILL_HUG = "HUG";
    public static final String SKILL_BE_CUTE = "BE_CUTE";
    public static final String SKILL_FRIGHTEN = "FRIGHTEN";
    public static final String SKILL_NOD = "NOD";
    /** Bắt tay — enum ROM thường {@code SHAKE_HAND} (doc: shake_hand). */
    public static final String SKILL_HANDSHAKE = "SHAKE_HAND";

    /** Mặc định — lắc người. */
    public static final String DEFAULT_SKILL = SKILL_BE_CUTE;

    /**
     * Pool random cho emotion chào / vui / hôn gió — tránh luôn cùng 1 skill.
     */
    private static final String[] FRIENDLY_RANDOM_POOL = {
            SKILL_BE_CUTE,
            SKILL_HAND_KISS,
            SKILL_SCRATCH,
            SKILL_HUG,
            SKILL_NOD,
            SKILL_HANDSHAKE
    };

    private static final String MARK_FRIENDLY_RANDOM = "__FRIENDLY_RANDOM__";
    private static final Random RANDOM = new Random();

    private static final Map<String, String> EMOTION_TO_SKILL = new HashMap<>();
    private static final Set<String> FRIENDLY_RANDOM_KEYS = new HashSet<>();

    static {
        /* Tên skill trực tiếp (gọi đúng skill đó) */
        reg("scratch", SKILL_SCRATCH);
        reg("hug", SKILL_HUG);
        reg("be_cute", SKILL_BE_CUTE);
        reg("frighten", SKILL_FRIGHTEN);

        /* Chào / vui / hôn gió → random 6 skill thân thiện */
        regFriendlyRandom("happy");
        regFriendlyRandom("greeting");
        regFriendlyRandom("wave");
        regFriendlyRandom("say_hi");
        regFriendlyRandom("hand_kiss");
        regFriendlyRandom("kissy");
        regFriendlyRandom("winking");
        regFriendlyRandom("chao");
        regFriendlyRandom("chào");
        regFriendlyRandom("hon");
        regFriendlyRandom("hôn");

        reg("embarrassed", SKILL_SCRATCH);

        reg("loving", SKILL_HUG);

        reg("surprised", SKILL_FRIGHTEN);
        reg("shocked", SKILL_FRIGHTEN);
        reg("scared", SKILL_FRIGHTEN);
        reg("frightened", SKILL_FRIGHTEN);

        reg("neutral", SKILL_BE_CUTE);
        reg("relaxed", SKILL_BE_CUTE);
        reg("funny", SKILL_BE_CUTE);
        reg("silly", SKILL_BE_CUTE);
        reg("laughing", SKILL_BE_CUTE);
        reg("confident", SKILL_BE_CUTE);
        reg("cool", SKILL_BE_CUTE);
        reg("delicious", SKILL_BE_CUTE);
        reg("sleepy", SKILL_BE_CUTE);
        reg("sad", SKILL_BE_CUTE);
        reg("crying", SKILL_BE_CUTE);
        reg("angry", SKILL_BE_CUTE);
        reg("thinking", SKILL_BE_CUTE);
        reg("confused", SKILL_BE_CUTE);

        reg("gai", SKILL_SCRATCH);
        reg("gãi", SKILL_SCRATCH);
        reg("om", SKILL_HUG);
        reg("ôm", SKILL_HUG);
        reg("de_thuong", SKILL_BE_CUTE);
        reg("dễ_thương", SKILL_BE_CUTE);
        reg("lac_nguoi", SKILL_BE_CUTE);
        reg("lắc_người", SKILL_BE_CUTE);
        reg("hoang", SKILL_FRIGHTEN);
        reg("hoảng", SKILL_FRIGHTEN);
    }

    private static void reg(String emotionKey, String skillApiName) {
        if (emotionKey == null || skillApiName == null) return;
        EMOTION_TO_SKILL.put(normalize(emotionKey), skillApiName);
    }

    private static void regFriendlyRandom(String emotionKey) {
        if (emotionKey == null) return;
        String key = normalize(emotionKey);
        FRIENDLY_RANDOM_KEYS.add(key);
        EMOTION_TO_SKILL.put(key, MARK_FRIENDLY_RANDOM);
    }

    private static String pickFriendlyRandom() {
        return FRIENDLY_RANDOM_POOL[RANDOM.nextInt(FRIENDLY_RANDOM_POOL.length)];
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    /**
     * @return skill ROM; không khớp → {@link #DEFAULT_SKILL}. Không bao giờ trả {@code SAY_HI}.
     */
    public static String resolveSkillName(String emotion) {
        if (emotion == null || emotion.isEmpty()) {
            return DEFAULT_SKILL;
        }
        String key = normalize(emotion);
        if (FRIENDLY_RANDOM_KEYS.contains(key) || MARK_FRIENDLY_RANDOM.equals(EMOTION_TO_SKILL.get(key))) {
            return pickFriendlyRandom();
        }
        String skill = EMOTION_TO_SKILL.get(key);
        if (skill != null) {
            if (MARK_FRIENDLY_RANDOM.equals(skill)) {
                return pickFriendlyRandom();
            }
            return skill;
        }
        for (Map.Entry<String, String> e : EMOTION_TO_SKILL.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                if (MARK_FRIENDLY_RANDOM.equals(e.getValue()) || FRIENDLY_RANDOM_KEYS.contains(e.getKey())) {
                    return pickFriendlyRandom();
                }
                return e.getValue();
            }
        }
        return DEFAULT_SKILL;
    }

    private LlmEmotionSkillMapper() {}
}
