package com.avrgaming.civcraft.modern.service;

import java.util.regex.Pattern;

public final class Validation {
    private static final Pattern NAME = Pattern.compile("^[A-Za-zА-Яа-я0-9_ -]+$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-zА-Яа-я0-9_]+$");

    private Validation() {
    }

    public static boolean isValidName(String input) {
        return isValidName(input, 3, 32);
    }

    public static boolean isValidFoundationName(String input) {
        return isValidName(input, 5, 16);
    }

    public static boolean isValidName(String input, int min, int max) {
        return input != null && input.length() >= min && input.length() <= max && NAME.matcher(input).matches();
    }

    public static boolean isValidCivTag(String input) {
        return input != null && input.length() >= 3 && input.length() <= 5 && TAG.matcher(input).matches();
    }
}
