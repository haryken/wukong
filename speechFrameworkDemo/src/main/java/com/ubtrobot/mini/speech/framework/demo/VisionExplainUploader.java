package com.ubtrobot.mini.speech.framework.demo;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * POST ảnh JPEG + câu hỏi lên endpoint vision của Xiaozhi (cùng ý tưởng
 * {@code Esp32Camera::Explain} trong xiaozhi-esp32-main).
 */
final class VisionExplainUploader {
    private static final String TAG = "VisionExplain";
    private static final MediaType JPEG = MediaType.parse("image/jpeg");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private VisionExplainUploader() {}

    static final class Result {
        final int httpCode;
        final String body;

        Result(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body != null ? body : "";
        }

        boolean isHttp2xx() {
            return httpCode >= 200 && httpCode < 300;
        }
    }

    /**
     * Multipart: field {@code question} + file part {@code camera.jpg} (giống ESP32).
     */
    static Result upload(String url, String bearerToken, String deviceId, String clientId,
            String question, byte[] jpeg) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("vision url trống");
        }
        if (jpeg == null || jpeg.length == 0) {
            throw new IOException("jpeg trống");
        }
        RequestBody filePart = RequestBody.create(JPEG, jpeg);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("question", question != null ? question : "")
                .addFormDataPart("file", "camera.jpg", filePart)
                .build();
        Request.Builder rb = new Request.Builder().url(url).post(body);
        if (deviceId != null && !deviceId.isEmpty()) {
            rb.header("Device-Id", deviceId);
        }
        if (clientId != null && !clientId.isEmpty()) {
            rb.header("Client-Id", clientId);
        }
        if (bearerToken != null && !bearerToken.trim().isEmpty()) {
            rb.header("Authorization", "Bearer " + bearerToken.trim());
        }
        Request request = rb.build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String respBody = response.body() != null ? response.body().string() : "";
            Log.i(TAG, "vision POST code=" + response.code() + " bytes=" + jpeg.length
                    + " bodyLen=" + respBody.length());
            return new Result(response.code(), respBody);
        } finally {
            response.close();
        }
    }
}
