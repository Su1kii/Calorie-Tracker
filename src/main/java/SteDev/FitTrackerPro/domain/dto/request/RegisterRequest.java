package SteDev.FitTrackerPro.domain.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @NotBlank String firstName,
        @NotBlank String lastName
) {}
