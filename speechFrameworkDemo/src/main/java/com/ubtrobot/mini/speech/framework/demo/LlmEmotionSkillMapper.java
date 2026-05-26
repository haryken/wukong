package com.ubtrobot.mini.speech.framework.demo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Map {@code emotion} server ({@code type:"llm"}) → skill ROM (không dùng {@code SAY_HI} — có âm hệ thống chen TTS):
 * {@code SCRATCH}, {@code HAND_KISS}, {@code HUG}, {@code BE_CUTE}, {@code FRIGHTEN}.
 * Chào / greeting → {@code HAND_KISS}. Không khớp → {@link #DEFAULT_SKILL} ({@code BE_CUTE}).
 */
public final class LlmEmotionSkillMapper {

    public static final String SKILL_SCRATCH = "SCRATCH";
    public static final String SKILL_HAND_KISS = "HAND_KISS";
    public static final String SKILL_HUG = "HUG";
    public static final String SKILL_BE_CUTE = "BE_CUTE";
    public static final String SKILL_FRIGHTEN = "FRIGHTEN";

    /** Mặc định — lắc người. */
    public static final String DEFAULT_SKILL = SKILL_BE_CUTE;

    private static final Map<String, String> EMOTION_TO_SKILL = new HashMap<>();

    static {
        /* Tên skill trực tiếp — say_hi map sang HAND_KISS, không gọi SAY_HI */
        reg("say_hi", SKILL_HAND_KISS);
        reg("scratch", SKILL_SCRATCH);
        reg("hand_kiss", SKILL_HAND_KISS);
        reg("hug", SKILL_HUG);
        reg("be_cute", SKILL_BE_CUTE);
        reg("frighten", SKILL_FRIGHTEN);

        /* Otto / Xiaozhi: chào / vui → HAND_KISS hoặc BE_CUTE (không SAY_HI) */
        reg("greeting", SKILL_HAND_KISS);
        reg("wave", SKILL_HAND_KISS);
        reg("happy", SKILL_HAND_KISS);

        reg("embarrassed", SKILL_SCRATCH);

        reg("kissy", SKILL_HAND_KISS);
        reg("winking", SKILL_HAND_KISS);

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

        reg("chao", SKILL_HAND_KISS);
        reg("chào", SKILL_HAND_KISS);
        reg("gai", SKILL_SCRATCH);
        reg("gãi", SKILL_SCRATCH);
        reg("hon", SKILL_HAND_KISS);
        reg("hôn", SKILL_HAND_KISS);
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
        String skill = EMOTION_TO_SKILL.get(key);
        if (skill != null) {
            return skill;
        }
        for (Map.Entry<String, String> e : EMOTION_TO_SKILL.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                return e.getValue();
            }
        }
        return DEFAULT_SKILL;
    }

    private LlmEmotionSkillMapper() {}
}
