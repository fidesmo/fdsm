package com.fidesmo.fdsm;

import javax.security.auth.callback.CallbackHandler;
import java.util.List;
import java.util.Map;

public interface FormHandler extends CallbackHandler {
    Map<String, Field> processForm(List<Field> form);
}
