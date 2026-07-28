package com.example.api;

public class Existing {
    public static final int DELIBERATE_FIELD = 2;

    protected String protectedField = "visible to subclasses";

    public Existing() {
    }

    public Existing(int deliberateConstructorControl) {
    }

    public String value() {
        return "positive";
    }

    protected int stableProtectedMember() {
        return 1;
    }

    protected static class ProtectedNested {
        protected ProtectedNested() {
        }

        public void nestedMethod() {
        }
    }
}
