package SteDev.FitTrackerPro.exception.handler;

import SteDev.FitTrackerPro.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.net.URI;

@ControllerAdvice
public class GlobalExceptionHandler {

    // ─── 404 NOT FOUND ────────────────────────────────────────────────────────

    // @ExceptionHandler tells Spring which exception class triggers this method
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        // ex.getMessage() returns the string we passed to super() in the exception
        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        // title = the category label, same every time this exception fires
        problem.setTitle("Resource Not Found");

        // type = a URI that identifies this error type — doesn't have to resolve,
        // it's just a unique identifier for this category of error
        problem.setType(URI.create("https://fittrack.com/errors/not-found"));

        // instance = which endpoint caused this specific occurrence
        // request.getRequestURI() gives us e.g. /api/users/42
        problem.setInstance(URI.create(request.getRequestURI()));

        // setProperty() adds extra fields beyond the RFC 7807 standard fields
        // these come from the fields we stored on the exception class itself
        // ex.getResourceName() and ex.getResourceId() work because of @Getter
        problem.setProperty("resourceName", ex.getResourceName());
        problem.setProperty("resourceId", ex.getResourceId());

        // ResponseEntity wraps the ProblemDetail with the correct HTTP status code
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    // ─── 409 CONFLICT ─────────────────────────────────────────────────────────

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEmail(
            DuplicateEmailException ex,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        problem.setTitle("Duplicate Email");
        problem.setType(URI.create("https://fittrack.com/errors/duplicate-email"));
        problem.setInstance(URI.create(request.getRequestURI()));

        // attaching the email so the client knows exactly which value conflicted
        // without having to parse the message string
        problem.setProperty("email", ex.getEmail());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    // ─── 401 UNAUTHORIZED (bad login) ─────────────────────────────────────────

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(
            InvalidCredentialsException ex,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        problem.setTitle("Invalid Credentials");
        problem.setType(URI.create("https://fittrack.com/errors/invalid-credentials"));
        problem.setInstance(URI.create(request.getRequestURI()));

        // no setProperty() here — intentionally vague for security

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    // ─── 401 UNAUTHORIZED (bad/expired token) ─────────────────────────────────

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(
            InvalidTokenException ex,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        problem.setTitle("Invalid Token");
        problem.setType(URI.create("https://fittrack.com/errors/invalid-token"));
        problem.setInstance(URI.create(request.getRequestURI()));

        // reason IS safe to expose here — "expired" vs "revoked" lets the
        // frontend decide: expired = try refresh token, revoked = force re-login
        problem.setProperty("reason", ex.getReason());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    // ─── 403 FORBIDDEN ────────────────────────────────────────────────────────

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(
            ForbiddenException ex,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");

        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://fittrack.com/errors/forbidden"));
        problem.setInstance(URI.create(request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
