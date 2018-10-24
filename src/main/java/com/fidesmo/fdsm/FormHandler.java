package com.fidesmo.fdsm;

import java.util.List;
import java.util.Map;

public interface FormHandler {
    Map<String, Field> processForm(List<Field> form);
}
