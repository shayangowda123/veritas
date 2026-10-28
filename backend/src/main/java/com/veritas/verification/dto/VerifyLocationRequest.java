package com.veritas.verification.dto;

import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.VerificationMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyLocationRequest {

    @NotNull(message = "Verification status is required")
    private LocationVerificationStatus status;

    @NotNull(message = "Verification method is required")
    private VerificationMethod verificationMethod;

    @Size(max = 1000, message = "Decision notes must not exceed 1000 characters")
    private String decisionNotes;
}
