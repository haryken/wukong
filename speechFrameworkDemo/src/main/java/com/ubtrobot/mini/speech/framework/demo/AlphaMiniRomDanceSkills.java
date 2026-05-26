package com.ubtrobot.mini.speech.framework.demo;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Điệu nhảy trên ROM ({@code SkillApi.loadAllSkills}) — cùng cơ chế {@code GO_AHEAD}:
 * mỗi lần gọi chọn ngẫu nhiên <b>một</b> {@code SkillApi.startSkill(SKILL_NAME)} và để ROM chạy hết chu kỳ.
 */
public final class AlphaMiniRomDanceSkills {
    private static final String TAG = "RomDanceSkills";
    private static final Random RANDOM = new Random();

    /**
     * Danh sách cố định từ log {@code SkillApiActivity} (ROM 81 skill) — dùng khi chưa quét được ROM.
     */
    /** Điệu nhảy ROM — {@code DANCE_*}, {@code DANCING}, và các skill múa khác (vd. #53–55). */
    private static final String[] FALLBACK_DANCE_NAMES = {
            "DANCING",
            "DANCE_SHOW",
            "DANCE_MONK",
            "DANCE_YOUTH",
            "DANCE_HOLLYDOLLY",
            "DANCE_LOCA",
            "DANCE_TIE",
            "DANCE_SKIRT",
            "DANCE_ASH",
            "DANCE_98K",
            "DANCE_TIMO",
            "DANCE_DURA",
            "SEAWEED",
            "BUG_FLY",
            "LIKE_BATH",
    };

    private static volatile String[] cachedPool;

    private AlphaMiniRomDanceSkills() {}

    /** Một tên enum ngẫu nhiên — {@link #resolveDancePool()} ưu tiên skill thật trên ROM. */
    public static String pickRandomSkillName() {
        String[] pool = resolveDancePool();
        return pool[RANDOM.nextInt(pool.length)];
    }

    public static int count() {
        return resolveDancePool().length;
    }

    public static String[] resolveDancePool() {
        String[] c = cachedPool;
        if (c != null && c.length > 0) {
            return c;
        }
        synchronized (AlphaMiniRomDanceSkills.class) {
            if (cachedPool == null || cachedPool.length == 0) {
                String[] fromRom = loadDanceNamesFromSkillApi();
                if (fromRom.length > 0) {
                    cachedPool = fromRom;
                    Log.i(TAG, "Dance pool từ SkillApi.loadAllSkills: " + Arrays.toString(fromRom));
                } else {
                    cachedPool = FALLBACK_DANCE_NAMES.clone();
                    Log.w(TAG, "Dance pool fallback cố định (" + cachedPool.length + " điệu)");
                }
            }
            return cachedPool;
        }
    }

    /** {@code DANCING}, {@code DANCE_*}, {@code SEAWEED}, {@code BUG_FLY}, {@code LIKE_BATH}, … */
    public static boolean isRomDanceSkillName(String enumName) {
        if (enumName == null || enumName.isEmpty()) {
            return false;
        }
        String u = enumName.trim().toUpperCase(Locale.US);
        if ("DANCING".equals(u) || u.startsWith("DANCE_")) {
            return true;
        }
        return "SEAWEED".equals(u) || "BUG_FLY".equals(u) || "LIKE_BATH".equals(u);
    }

    /**
     * Quét {@code SkillApi.get().loadAllSkills()} — chỉ lấy skill nhảy (giống cách ROM liệt kê #14, #47–#76).
     */
    private static String[] loadDanceNamesFromSkillApi() {
        List<String> names = new ArrayList<>();
        try {
            Class<?> skillApiClass = Class.forName("com.ubtechinc.skill.SkillApi");
            Object api = skillApiClass.getMethod("get").invoke(null);
            Method loadAll = skillApiClass.getMethod("loadAllSkills");
            List<?> list = (List<?>) loadAll.invoke(api);
            if (list != null) {
                for (Object item : list) {
                    if (!(item instanceof Enum)) {
                        continue;
                    }
                    String name = ((Enum<?>) item).name();
                    if (isRomDanceSkillName(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "loadDanceNamesFromSkillApi: " + t.getMessage());
        }
        return names.toArray(new String[0]);
    }

    /** Chỉ MCP {@code self.otto.dance} — không khớp keyword trong text. */
    public static boolean matchesDanceControlIntent(String toolNameLower, String combinedLower) {
        if (toolNameLower == null) {
            return false;
        }
        String n = toolNameLower.toLowerCase(Locale.US);
        return n.contains("otto.dance") || n.endsWith(".dance");
    }
}
