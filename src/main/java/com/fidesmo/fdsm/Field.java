package com.fidesmo.fdsm;

import java.util.Optional;

public class Field {
    private final String id;
    private final String label;
    private final String type;
    private final String format;
    private String value;

    public Field(String id, String label, String type, String format) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.format = format;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Optional<String> getFormat() {
        return Optional.ofNullable(format);
    }
}
