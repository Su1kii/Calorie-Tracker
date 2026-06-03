package SteDev.FitTrackerPro.exception;

import lombok.Getter;

@Getter
public class DuplicateEmailException extends RuntimeException {

    private final String email;

    public DuplicateEmailException(String email) {
        super("An account with email '" + email + "' already exists");
        this.email = email;
    }
}
