package com.example.minicassandra.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Encoding {
    private Encoding() {
    }

    public static String b64(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String unb64(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
