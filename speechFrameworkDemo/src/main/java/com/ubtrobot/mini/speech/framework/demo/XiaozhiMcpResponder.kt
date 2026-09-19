package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import info.dourok.voicebot.protocol.MqttProtocol
import info.dourok.voicebot.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * App Android trả lời MCP như **client handler** (Xiaozhi cloud gửi {@code type=mcp} xuống).
 * {@code tools/list} phải gần với bộ tool Xiaozhi/ESP32 (Otto + common) để LLM không bảo "không có tool ngồi/đứng"
 * trong khi firmware thật có tới vài chục self-control — trước đây chỉ khai 6 tool nên mô hình không thấy sit/stand/turn…
 */
object XiaozhiMcpResponder {
    private const val TAG = "XiaozhiMcp"

    /** Server gửi MCP {@code initialize} (tools/vision) — đợi sau khi mở kênh, trước listen. Không liên quan LLM emotion. */
    @Volatile
    private var initializeAck = CompletableDeferred<Unit>()

    fun resetInitializeHandshake() {
        initializeAck = CompletableDeferred()
    }

    fun isInitializeResponded(): Boolean = initializeAck.isCompleted

    private fun markInitializeResponded() {
        if (!initializeAck.isCompleted) initializeAck.complete(Unit)
    }

    suspend fun awaitInitializeResponded(timeoutMs: Long = 5000L) {
        try {
            withTimeout(timeoutMs) { initializeAck.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "mcp: timeout đợi initialize (${timeoutMs}ms) – vẫn gửi listen")
        }
    }

    private suspend fun respondInitialize(
        protocol: Protocol,
        sessionId: String,
        idNum: Int,
        params: JSONObject?,
    ) {
        params
            ?.optJSONObject("capabilities")
            ?.optJSONObject("vision")
            ?.let { v -> MiniRobotActionInvoker.updateVisionFromCapabilitiesJson(v) }
        val result = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().put("tools", JSONObject()))
            put(
                "serverInfo",
                JSONObject().apply {
                    put("name", "alpha-mini")
                    put("version", "1.0")
                },
            )
        }
        sendRpcResult(protocol, sessionId, idNum, result)
        markInitializeResponded()
        Log.i(TAG, "mcp: đã trả initialize id=$idNum session=$sessionId")
    }

    suspend fun handleIncomingMcp(root: JSONObject, protocol: Protocol) {
        val sessionId = root.optString("session_id", "").ifEmpty { protocol.currentSessionIdForMcp() }
        val payloadRaw = root.opt("payload") ?: run {
            Log.w(TAG, "mcp: thiếu payload")
            return
        }
        val rpc = when (payloadRaw) {
            is JSONObject -> payloadRaw
            is String -> try {
                JSONObject(payloadRaw as String)
            } catch (e: Exception) {
                Log.w(TAG, "mcp: payload không parse được JSON: ${e.message}")
                return
            }
            else -> {
                Log.w(TAG, "mcp: payload kiểu không hỗ trợ")
                return
            }
        }
        if (rpc.optString("jsonrpc") != "2.0") {
            Log.w(TAG, "mcp: jsonrpc != 2.0")
            return
        }
        val method = rpc.optString("method", "")
        if (method.startsWith("notifications")) {
            Log.d(TAG, "mcp: bỏ qua notification $method")
            return
        }
        val id = rpc.opt("id")
        if (id == null || id !is Number) {
            Log.w(TAG, "mcp: thiếu id số cho method=$method (id=$id)")
            return
        }
        val idNum = (id as Number).toInt()
        try {
            when (method) {
                "initialize" -> {
                    val params = rpc.optJSONObject("params")
                    // Giống ESP32 protocol.cc SendMcpMessage: session_id rỗng trước server hello là bình thường.
                    val effectiveSession = sessionId.ifEmpty { protocol.currentSessionIdForMcp() }
                    respondInitialize(protocol, effectiveSession, idNum, params)
                }
                "tools/list" -> {
                    val tools = alphaMiniMcpTools()
                    val result = JSONObject().put("tools", tools)
                    sendRpcResult(protocol, sessionId, idNum, result)
                    Log.i(TAG, "mcp: đã trả tools/list (${tools.length()} tools) id=$idNum")
                }
                "tools/call" -> {
                    val params = rpc.optJSONObject("params")
                    val nm = params?.optString("name", "") ?: ""
                    Log.i(TAG, "mcp tools/call (trước xử lý) name=\"$nm\"")
                    MiniRobotActionInvoker.noteMcpToolInvoked(nm)
                    val toolResultStr: String = when {
                        nm.equals("self.camera.take_photo", ignoreCase = true) -> {
                            val arguments = params?.optJSONObject("arguments")
                            val question = arguments?.optString("question", "")?.trim().orEmpty()
                                .ifEmpty { "Hãy mô tả ảnh chụp từ robot." }
                            val pair = withContext(Dispatchers.IO) {
                                MiniRobotActionInvoker.takePhotoAndExplainVision(question)
                            }
                            toolCallResultBody(pair.first, pair.second)
                        }
                        nm.equals("self.otto.get_student_info", ignoreCase = true)
                            || nm.equals("self.mini.get_student_info", ignoreCase = true) -> {
                            toolCallResultBody(true, DemoSpeech.selfControlGetStudentInfoJson())
                        }
                        nm.equals("self.otto.show_config_page", ignoreCase = true)
                            || nm.equals("self.mini.show_config_page", ignoreCase = true) -> {
                            val msg = withContext(Dispatchers.IO) {
                                DemoSpeech.selfControlShowConfigPage()
                            }
                            toolCallResultBody(true, msg)
                        }
                        nm.equals("self.otto.hide_config_page", ignoreCase = true)
                            || nm.equals("self.mini.hide_config_page", ignoreCase = true)
                            || nm.equals("self.otto.dismiss_qr", ignoreCase = true)
                            || nm.equals("self.mini.dismiss_qr", ignoreCase = true) -> {
                            val msg = withContext(Dispatchers.IO) {
                                DemoSpeech.selfControlHideConfigPage()
                            }
                            toolCallResultBody(true, msg)
                        }
                        nm.equals("self.otto.set_course", ignoreCase = true)
                            || nm.equals("self.mini.set_course", ignoreCase = true) -> {
                            val arguments = params?.optJSONObject("arguments")
                            val idx = arguments?.optInt("course_idx", -1) ?: -1
                            val mac = arguments?.optString("custom_mac", "")?.trim().orEmpty()
                            if (idx !in 0..7) {
                                toolCallResultBody(false, "course_idx must be 0..7")
                            } else {
                                val r = DemoSpeech.selfControlSetCourse(idx, mac.ifEmpty { null })
                                toolCallResultBody(true, r)
                            }
                        }
                        nm.equals("self.otto.shift_unit", ignoreCase = true)
                            || nm.equals("self.mini.shift_unit", ignoreCase = true)
                            || nm.equals("self.otto.next_unit", ignoreCase = true)
                            || nm.equals("self.mini.next_unit", ignoreCase = true)
                            || nm.equals("self.otto.prev_unit", ignoreCase = true)
                            || nm.equals("self.mini.prev_unit", ignoreCase = true) -> {
                            val arguments = params?.optJSONObject("arguments")
                            val dir = when {
                                nm.contains("next_unit", ignoreCase = true) -> "next"
                                nm.contains("prev_unit", ignoreCase = true) -> "prev"
                                else -> arguments?.optString("direction", "next")?.trim().orEmpty()
                                    .ifEmpty { "next" }
                            }
                            val r = DemoSpeech.selfControlShiftUnit(dir)
                            // Text ngắn để TTS đọc tên unit, không dump JSON.
                            val jo = org.json.JSONObject(r)
                            val msg = jo.optString("message", r)
                            toolCallResultBody(jo.optBoolean("success", false), msg)
                        }
                        nm.equals("self.otto.music.play", ignoreCase = true) -> {
                            val arguments = params?.optJSONObject("arguments")
                            val query = arguments?.optString("query", "")?.trim().orEmpty()
                            val r = OttoMusicPlayer.playFirstSearchResult(query)
                            val jo = org.json.JSONObject(r)
                            toolCallResultBody(jo.optBoolean("success", false), r)
                        }
                        nm.equals("self.otto.music.stop", ignoreCase = true) -> {
                            OttoMusicPlayer.stop()
                            toolCallResultBody(true, "ok")
                        }
                        else -> {
                            MiniRobotActionInvoker.dispatchFromXiaozhiJson(root)
                            toolCallResultBody(true, "ok")
                        }
                    }
                    sendRpcResult(protocol, sessionId, idNum, JSONObject(toolResultStr))
                    Log.i(TAG, "mcp: đã trả tools/call id=$idNum")
                }
                else -> {
                    sendRpcError(protocol, sessionId, idNum, "Method not implemented: $method")
                    Log.w(TAG, "mcp: method không hỗ trợ: $method")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "mcp handle error: ${e.message}", e)
            try {
                sendRpcError(protocol, sessionId, idNum, e.message ?: "error")
            } catch (ignored: Exception) { }
        }
    }

    private fun toolDef(name: String, description: String): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put(
                "inputSchema",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            )
        }

    /** Giống ESP32 {@code self.camera.take_photo}: có tham số {@code question} gửi kèm multipart vision. */
    private fun toolDefSelfCameraTakePhoto(): JSONObject =
        JSONObject().apply {
            put("name", "self.camera.take_photo")
            put(
                "description",
                "Chụp ảnh (TakePicApi), upload JPEG + question lên server vision (giống xiaozhi-esp32). Cần vision.url từ MCP initialize hoặc hello."
            )
            put(
                "inputSchema",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties",
                        JSONObject().apply {
                            put(
                                "question",
                                JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Câu hỏi về ảnh (gửi form field question).")
                                }
                            )
                        }
                    )
                }
            )
        }

    /** Alpha Mini: chỉ tool đã nối SkillApi / StandUpApi / motion / camera / explore (24 tool). */
    private fun alphaMiniMcpTools(): JSONArray =
        JSONArray().apply {
            put(toolDefSelfCameraTakePhoto())
            put(
                toolDef(
                    "self.shut_down",
                    "Tắt máy / tắt nguồn robot. Alpha Mini: SkillApi.startSkill(SHUT_DOWN) — skill #81 trên ROM; "
                            + "fallback utterance turn_off_robot."
                )
            )
            put(toolDef("self.otto.walk_forward", "Đi / tiến / lùi (Otto). Tham số direction: 1=tiến, -1=lùi (mặc định 1). Alpha Mini: SkillApi GO_AHEAD / BACK_UP; fallback Keep_moving_forward / Keep_going_backwards."))
            put(toolDef("self.otto.walk_backward", "Lùi / đi ngược. Alpha Mini: skill Keep_going_backwards."))
            put(toolDef("self.otto.turn_left", "Xoay trái. direction: 1=trái, -1=phải (Otto). Alpha Mini: SkillApi TURN_LEFT; fallback keep_turning_left."))
            put(toolDef("self.otto.turn_right", "Xoay phải. Alpha Mini: SkillApi TURN_RIGHT; fallback keep_turning_right."))
            put(toolDef("self.otto.jump", "Nhảy chỗ (jump). Alpha Mini: motion playAction, không phải điệu múa."))
            put(
                toolDef(
                    "self.otto.dance",
                    "Nhảy múa: SkillApi.startSkill giống GO_AHEAD — mỗi lần chọn ngẫu nhiên 1 trong các điệu "
                            + "DANCING, DANCE_SHOW, SEAWEED, BUG_FLY, LIKE_BATH, … (15 điệu trên ROM); chạy hết một chu kỳ."
                )
            )
            put(
                toolDef(
                    "self.otto.taiji",
                    "Múa / đánh thái cực (太极 Tai Chi). Alpha Mini: SkillApi.startSkill(TAIJI) — skill #2 trên ROM; "
                            + "chạy hết một chu kỳ; không nhầm với self.otto.dance ngẫu nhiên."
                )
            )
            put(
                toolDef(
                    "self.otto.kungfu",
                    "Đánh võ / công phu (功夫). Alpha Mini: ActionApi.playAction(\"013\") — motion 打功夫 "
                            + "(mini-outer-sdk-demo ActionApiActivity); không phải SkillApi TAIJI/dance."
                )
            )
            put(toolDef("self.otto.swing", "Đung đưa. Alpha Mini: motion."))
            put(toolDef("self.otto.moonwalk", "Moonwalk. Alpha Mini: motion."))
            put(toolDef("self.otto.bend", "Nghiêng người. Alpha Mini: motion."))
            put(toolDef("self.otto.shake_leg", "Lắc chân. Alpha Mini: motion."))
            put(toolDef("self.otto.updown", "Lên xuống. Alpha Mini: motion."))
            put(
                toolDef(
                    "self.otto.explore",
                    "Chế độ khám phá: robot tự tiến, rẽ trái, rẽ phải ngẫu nhiên (lùi ít hơn), "
                            + "giữ gần vị trí ban đầu. Chạy đến khi user gọi self.otto.stop hoặc nói dừng khám phá."
                )
            )
            put(toolDef("self.otto.hands_up", "Giơ tay. Alpha Mini: skill raisinghands (ROM có thì chạy)."))
            put(toolDef("self.otto.hands_down", "Hạ tay. Alpha Mini: skill foot_stand (ROM có thì chạy)."))
            put(toolDef("self.otto.hand_wave", "Vẫy tay. Alpha Mini: skill shake_hand."))
            put(
                toolDef(
                    "self.otto.bow_step",
                    "Cung bộ (弓步 / bow step). Alpha Mini: SkillApi.startSkill(BOW_STEP) — skill #37 trên ROM; "
                            + "tư thế chào / bước cung; fallback utterance bow_step."
                )
            )
            put(toolDef("self.otto.sit", "Ngồi xuống. Alpha Mini: StandUpApi.sitdown."))
            put(toolDef("self.otto.sit_down", "Ngồi xuống. Alpha Mini: StandUpApi.sitdown (tư thế ngồi)."))
            put(toolDef("self.otto.stand_up", "Đứng dậy. Alpha Mini: StandUpApi.standUp."))
            put(
                toolDef(
                    "self.otto.stop",
                    "Dừng mọi hành động, gồm chế độ khám phá (explore), nhạc YouTube đang phát, và motion đang chạy (ActionApi.stopAction)."
                )
            )
            // Self-Control (Otto parity)
            put(
                toolDef(
                    "self.otto.get_student_info",
                    "Trả thông tin học viên / khóa học / unit / Device-Id (Self-Control). Dùng khi hỏi 'tao là ai / đang học gì'."
                )
            )
            put(
                toolDef(
                    "self.otto.show_config_page",
                    "Hiện mã QR trang cấu hình Self-Control trên mắt robot (60 giây). " +
                        "Dùng khi nói 'mở cài đặt', 'mở trang cấu hình', 'hiện QR'. " +
                        "Chỉ nói ngắn: 'Đã mở mã QR.' — tuyệt đối KHÔNG đọc URL, IP, đường dẫn, http, :8080."
                )
            )
            put(
                toolDef(
                    "self.otto.hide_config_page",
                    "Tắt / đóng mã QR cấu hình Self-Control trên mắt robot. " +
                        "Dùng khi nói 'tắt QR', 'tắt mã QR', 'đóng trang cấu hình', 'ẩn QR'. " +
                        "Chỉ nói ngắn: 'Đã tắt mã QR.' — không đọc URL."
                )
            )
            put(toolDefSetCourse())
            put(toolDefShiftUnit())
            put(toolDefMusicPlay())
            put(
                toolDef(
                    "self.otto.music.stop",
                    "Dừng phát nhạc YouTube đang chạy trên loa."
                )
            )
        }

    private fun toolDefMusicPlay(): JSONObject =
        JSONObject().apply {
            put("name", "self.otto.music.play")
            put(
                "description",
                "Tìm nhạc trên YouTube (server kytuoi), chọn kết quả đầu tiên và phát MP3 qua loa robot. " +
                    "Dùng khi người dùng muốn nghe một bài (tên bài / ca sĩ). Trả về status=starting khi bắt đầu tìm."
            )
            put(
                "inputSchema",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties",
                        JSONObject().apply {
                            put(
                                "query",
                                JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Tên bài hát / ca sĩ / từ khóa tìm nhạc")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("query"))
                }
            )
        }

    private fun toolDefShiftUnit(): JSONObject =
        JSONObject().apply {
            put("name", "self.otto.shift_unit")
            put(
                "description",
                "Chọn unit tiếp theo hoặc unit trước trong khóa + sách đang học (Self-Control). " +
                    "Dùng khi nói 'unit tiếp theo', 'bài tiếp theo', 'unit trước', 'bài trước'. " +
                    "Tự đọc cấp độ hiện tại; hết unit thì sang sách con kế tiếp nếu có. " +
                    "Trả lời ngắn tên unit — không đổi MAC/Device-Id."
            )
            put(
                "inputSchema",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties",
                        JSONObject().apply {
                            put(
                                "direction",
                                JSONObject().apply {
                                    put("type", "string")
                                    put("description", "next | prev")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("direction"))
                }
            )
        }

    private fun toolDefSetCourse(): JSONObject =
        JSONObject().apply {
            put("name", "self.otto.set_course")
            put(
                "description",
                "Đổi khóa học Self-Control (course_idx 0..7). Đổi khóa → ApplyDeviceIdentity + tự chào. " +
                    "idx 6 cần custom_mac aa:bb:cc:dd:ee:ff. idx 7 = giao tiếp hằng ngày (random MAC pool)."
            )
            put(
                "inputSchema",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties",
                        JSONObject().apply {
                            put(
                                "course_idx",
                                JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "0 custom … 7 daily_chat")
                                }
                            )
                            put(
                                "custom_mac",
                                JSONObject().apply {
                                    put("type", "string")
                                    put("description", "MAC khi course_idx=0 hoặc 6")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("course_idx"))
                }
            )
        }

    /** Giống McpTool::Call bool → content text (mcp_server.h). */
    private fun toolCallResultBody(success: Boolean, text: String): String =
        JSONObject().apply {
            val content = JSONArray().put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                }
            )
            put("content", content)
            put("isError", !success)
        }.toString()

    private suspend fun sendRpcResult(protocol: Protocol, sessionId: String, id: Int, result: JSONObject) {
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        sendMcpEnvelope(protocol, sessionId, rpc)
    }

    private suspend fun sendRpcError(protocol: Protocol, sessionId: String, id: Int, message: String) {
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().put("message", message))
        }
        sendMcpEnvelope(protocol, sessionId, rpc)
    }

    private suspend fun sendMcpEnvelope(protocol: Protocol, sessionId: String, rpc: JSONObject) {
        val outer = JSONObject().apply {
            put("type", "mcp")
            put("session_id", sessionId)
            put("payload", rpc)
        }
        val body = outer.toString()
        if (protocol is MqttProtocol && !protocol.isAudioChannelOpened()) {
            protocol.sendTextHandshake(body)
        } else {
            protocol.sendText(body)
        }
    }
}
