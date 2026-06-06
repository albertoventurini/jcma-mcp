package com.example.shapes;

/** An enum: two constants and a method (exercises ENUM + ENUM_CONSTANT). */
public enum Day {
    MONDAY,
    FRIDAY;

    public boolean isFriday() {
        return this == FRIDAY;
    }
}
