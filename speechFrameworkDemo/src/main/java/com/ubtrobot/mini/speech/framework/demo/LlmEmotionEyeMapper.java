package com.ubtrobot.mini.speech.framework.demo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Map emotion LLM nguyên bản → biểu cảm mắt ROM ({@code emo_*}).
 * Nhiều mắt trong 1 cụm → random mỗi lần.
 */
public final class LlmEmotionEyeMapper {

    private static final String DEFAULT_EXPRESS = "emo_027";
    private static final Random RANDOM = new Random();
    private static final Map<String, String[]> EMOTION_TO_EYES = new HashMap<>();

    static {
        /* 1 Chào */
        reg("greeting", "emo_001");
        reg("say_hi", "emo_001");

        /* 2 Vẫy tay */
        reg("wave", "emo_003", "emo_001");

        /* 3 Vui */
        reg("happy", "emo_001");
        reg("laughing", "emo_001");

        /* 4 Hài / nghịch */
        reg("funny", "emo_008");
        reg("silly", "emo_008");

        /* 5 Tự tin */
        reg("confident", "emo_029");

        /* 6 Ngầu */
        reg("cool", "emo_005", "emo_029");

        /* 7 Dễ thương */
        reg("be_cute", "emo_003");

        /* 8 Hôn gió / nháy mắt */
        reg("kissy", "emo_003");
        reg("winking", "emo_003");
        reg("hand_kiss", "emo_003");

        /* 9 Yêu / ôm */
        reg("loving", "emo_003");
        reg("hug", "emo_003");

        /* 10 Ngại / gãi */
        reg("embarrassed", "emo_010");
        reg("scratch", "emo_010");

        /* 11 Buồn */
        reg("sad", "emo_001", "emo_014");

        /* 12 Buồn ngủ */
        reg("sleepy", "emo_020", "emo_002");

        /* 13 Giận */
        reg("angry", "emo_013");

        /* 14 Sợ */
        reg("scared", "emo_019");
        reg("frightened", "emo_019");
        reg("frighten", "emo_019");

        /* 15 Ngạc nhiên */
        reg("surprised", "emo_004");
        reg("shocked", "emo_004");

        /* 16 Đang nghĩ */
        reg("thinking", "emo_031", "emo_032");

        /* 17 Bối rối */
        reg("confused", "emo_030");

        /* 18 Trung tính */
        reg("neutral", "emo_027");
        reg("relaxed", "emo_027");
        reg("delicious", "emo_027");

        /* 19 Khóc */
        reg("crying", "emo_011");
    }

    private static void reg(String emotionKey, String... eyes) {
        if (emotionKey == null || eyes == null || eyes.length == 0) return;
        EMOTION_TO_EYES.put(normalize(emotionKey), eyes);
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    /**
     * @return tên express mắt; không khớp → {@link #DEFAULT_EXPRESS}.
     */
    public static String resolveExpressName(String emotion) {
        if (emotion == null || emotion.isEmpty()) {
            return DEFAULT_EXPRESS;
        }
        String key = normalize(emotion);
        String[] eyes = EMOTION_TO_EYES.get(key);
        if (eyes != null && eyes.length > 0) {
            return eyes[RANDOM.nextInt(eyes.length)];
        }
        for (Map.Entry<String, String[]> e : EMOTION_TO_EYES.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                String[] pool = e.getValue();
                if (pool != null && pool.length > 0) {
                    return pool[RANDOM.nextInt(pool.length)];
                }
            }
        }
        return DEFAULT_EXPRESS;
    }

    private LlmEmotionEyeMapper() {}
}
