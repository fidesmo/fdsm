package com.fidesmo.fdsm;

import javax.security.auth.callback.CallbackHandler;
import java.util.List;
import java.util.Map;

// Form handler should in the future be renamed to UI handler.
// It fetches input from the user, based on a list of required fields.
// It also implements javax.security callback handler to provide for input-output
// from the mini-library in a "standard" way.
public interface FormHandler extends CallbackHandler {
    Map<String, Field> processForm(List<Field> form);
}
