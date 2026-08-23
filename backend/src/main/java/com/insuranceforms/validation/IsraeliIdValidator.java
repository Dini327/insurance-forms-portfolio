package com.insuranceforms.validation;

public final class IsraeliIdValidator {

    private IsraeliIdValidator() {
    }

    public static boolean isValid(String rawId) {
        if (rawId == null || !rawId.matches("\\d{1,9}")) {
            return false;
        }
        String padded = String.format("%09d", Long.parseLong(rawId));
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = padded.charAt(i) - '0';
            int weight = (i % 2 == 0) ? 1 : 2;
            int product = digit * weight;
            sum += (product > 9) ? product - 9 : product;
        }
        return sum % 10 == 0;
    }
}
