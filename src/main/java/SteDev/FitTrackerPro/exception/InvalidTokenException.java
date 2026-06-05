package SteDev.FitTrackerPro.exception;

import lombok.Getter;

@Getter
public class InvalidTokenException extends RuntimeException {

    private final String reason;

    public InvalidTokenException(String reason) {
        super("Token is invalid: " + reason);
        this.reason = reason;
    }

}
