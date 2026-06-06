package com.example.shapes;

/** A class with a field, constructor, two methods, and a nested static class (containment depth 2). */
public class Circle implements Shape {

    private final double radius;

    public Circle(double radius) {
        this.radius = radius;
    }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }

    private double circumference() {
        return 2 * Math.PI * radius;
    }

    public static class Builder {
        private double radius;

        public Circle build() {
            return new Circle(radius);
        }
    }
}
