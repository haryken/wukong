package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.util.Log

/**
 * Thế ván mới nhất từ web /games/ (engine chạy trong trình duyệt, đẩy lên qua POST /api/game_context).
 * Xiaozhi đọc qua MCP self.chess.summarize / self.xiangqi.summarize / self.game.summarize — giống wired.
 */
object SelfControlGameContext {
    private const val TAG = "SelfControlGameContext"
    /** Trang game đóng mà không gửi exit → coi như hết ván sau khoảng này. */
    private const val STALE_MS = 30 * 60 * 1000L
    private const val MAX_SUMMARY = 4000

    private class Snapshot(val game: String, val exitId: String, val summary: String, val at: Long)

    @Volatile private var current: Snapshot? = null

    private val LABELS = mapOf(
        "chess" to "cờ vua",
        "xiangqi" to "cờ tướng",
        "caro" to "cờ caro",
        "connect4" to "cờ thả cột (connect four)",
        "reversi" to "cờ lật (othello)",
        "checkers" to "cờ đam",
        "go9" to "cờ vây 9×9",
        "tictactoe" to "cờ caro 3×3 (tic-tac-toe)",
        "sudoku" to "sudoku",
        "g2048" to "2048",
        "mines" to "dò mìn",
        "minesweeper" to "dò mìn",
        "memory" to "lật thẻ",
        "battleship" to "bắn tàu",
        "simon" to "nhớ nhịp (simon)",
        "wordle" to "wordle",
        "hangman" to "treo cổ",
        "trivia" to "đố vui",
        "guess" to "đoán số",
        "guessnum" to "đoán số",
        "uno" to "uno",
        "blackjack" to "xì dách",
        "blackjackweb" to "xì dách",
        "poker" to "poker",
    )

    fun update(game: String?, exitId: String?, summary: String?) {
        val g = game?.trim().orEmpty()
        if (g.isEmpty()) {
            current = null
            Log.i(TAG, "game context cleared")
            return
        }
        val s = summary?.trim().orEmpty().take(MAX_SUMMARY)
        current = Snapshot(g, exitId?.trim().orEmpty(), s, System.currentTimeMillis())
    }

    private fun label(s: Snapshot): String =
        LABELS[s.exitId.lowercase()] ?: LABELS[s.game.lowercase()] ?: s.game

    private fun matches(s: Snapshot, wanted: String): Boolean {
        val w = wanted.lowercase()
        return s.exitId.lowercase() == w || s.game.lowercase() == w
    }

    /** @param wanted id game tool yêu cầu (chess, xiangqi…) hoặc null = game bất kỳ đang chơi. */
    fun summarizeForXiaozhi(wanted: String?): Pair<Boolean, String> {
        val s = current
        if (s == null || System.currentTimeMillis() - s.at > STALE_MS) {
            return false to "Hiện không có ván nào đang mở trên trang Trò chơi của robot. " +
                "Hãy bảo người dùng mở trang Trò chơi (nút 🎮 trên web :8080) và bắt đầu ván."
        }
        val ageSec = (System.currentTimeMillis() - s.at) / 1000
        val b = StringBuilder()
        if (!wanted.isNullOrBlank() && !matches(s, wanted)) {
            b.append("Lưu ý: ván đang chơi là ").append(label(s))
                .append(", không phải ").append(LABELS[wanted.lowercase()] ?: wanted).append(". ")
        }
        b.append("Ván ").append(label(s)).append(" đang chơi trên web Alpha Mini (cập nhật ")
            .append(ageSec).append(" giây trước). ")
        b.append(s.summary.replace("web Vector", "web Alpha Mini"))
        b.append("\nHãy phân tích ngắn gọn bằng tiếng Việt (2–4 câu): thế ván hiện tại, bên nào đang ưu thế, ")
            .append("nước gần nhất có tốt không, và gợi ý nước tiếp theo cho người chơi. Không đọc FEN hay ký hiệu thô.")
        return true to b.toString()
    }
}
