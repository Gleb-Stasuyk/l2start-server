package org.l2start.platform.loader;

import java.util.List;

public final class ManifestException extends Exception {
    private static final long serialVersionUID = 1L;
    private final List<String> errors;

    public ManifestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
