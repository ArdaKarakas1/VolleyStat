package com.volleyvolleystat.app;

final class EventOptions {
    static final String[] TYPES = {
        "rally", "serve", "reception", "attack", "block", "assist", "dig", "handling"
    };
    static final String[] TYPE_LABELS = {
        "Ralli sayısı", "Servis", "Servis karşılama", "Hücum", "Blok", "Asist", "Savunma", "Top kullanımı"
    };
    static final String[][] OUTCOMES = {
        {"won"}, {"ace", "error", "in_play"}, {"in_play", "error"},
        {"kill", "error", "in_play"}, {"solo", "assist", "error"},
        {"credited"}, {"credited"}, {"error"}
    };
    static final String[][] OUTCOME_LABELS = {
        {"Sayı"}, {"Ace", "Hata", "Oyunda"}, {"Oyunda", "Hata"},
        {"Sayı", "Hata", "Oyunda"}, {"Tekli sayı bloğu", "Blok asisti", "Hata"},
        {"Asist"}, {"Çıkarılan top"}, {"Top kullanımı hatası"}
    };

    static int index(String[] values, String key) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(key)) {
                return i;
            }
        }
        return 0;
    }

    static String label(String type, String outcome) {
        int i = index(TYPES, type);
        return TYPE_LABELS[i] + " · " + OUTCOME_LABELS[i][index(OUTCOMES[i], outcome)];
    }

    private EventOptions() {}
}
