/*
 * Copyright (c) 2018 - present Fidesmo AB
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.fidesmo.fdsm;

import java.util.List;
import java.util.Optional;

public class Field {
    private final String id;
    private final String label;
    private final List<String> labels;
    private final String type;
    private final String format;
    private String value;

    public Field(String id, String label, String type, String format) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.format = format;
        this.labels = null;
    }

    public Field(String id, String label, String type, String format, List<String> labels) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.format = format;
        this.labels = List.copyOf(labels);
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getLabels() {
        return List.copyOf(labels);
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
