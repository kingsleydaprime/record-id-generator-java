package com.itc.service;

import java.security.SecureRandom;

public class IdGeneratorService {
    private static final String DIGITS = "0123456789";
    private static final int LENGTH = 12;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }
}
