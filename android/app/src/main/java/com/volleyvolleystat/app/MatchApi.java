package com.volleyvolleystat.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class MatchApi {
    static final long MAX_VIDEO = 4L * 1024 * 1024 * 1024;
    final String url;

    MatchApi(boolean physicalDevice, String matchId) {
        String host = physicalDevice ? "127.0.0.1" : "10.0.2.2";
        url = "http://" + host + ":8080/v1/matches/" + matchId;
    }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = connect(method, path);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(bytes);
                }
            }
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    void upload(File file, Progress progress) throws Exception {
        long size = file.length();
        if (size <= 0 || size > MAX_VIDEO) {
            throw new IOException("Video boş veya 4 GiB sınırını aşıyor.");
        }
        byte[] buffer = new byte[256 * 1024];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder checksum = new StringBuilder();
        for (byte value : digest.digest()) {
            checksum.append(Character.forDigit((value & 0xff) >>> 4, 16));
            checksum.append(Character.forDigit(value & 0xf, 16));
        }

        HttpURLConnection connection = connect("PUT", "/video");
        try {
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-Content-SHA256", checksum.toString());
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(size);
            long sent = 0;
            int last = -1;
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = connection.getOutputStream()) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    sent += count;
                    int percent = (int) (sent * 100 / size);
                    if (percent != last) {
                        progress.update(percent);
                        last = percent;
                    }
                }
            }
            read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private JSONObject read(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        boolean successful = status >= 200 && status < 300;
        InputStream source = successful ? connection.getInputStream() : connection.getErrorStream();
        if (source == null) {
            throw new IOException("Sunucu yanıtı alınamadı (" + status + ").");
        }
        try (InputStream input = source; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            if (!successful) {
                throw new ApiError(status, response.optString("error", "İşlem tamamlanamadı."));
            }
            return response;
        }
    }

    static final class ApiError extends IOException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    interface Progress {
        void update(int percent);
    }
}
