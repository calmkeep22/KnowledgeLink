package com.knowledgelink.auth.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotNull @Size(max = 200) String currentPassword,
        @NotNull @Size(max = 200) String newPassword) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=****, newPassword=****]";
    }
}
