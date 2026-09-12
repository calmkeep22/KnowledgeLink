package com.knowledgelink.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 100) String loginId,
        @NotNull @Size(max = 200) String password) {

    /** 비밀번호가 로그·예외 메시지에 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + ", password=****]";
    }
}
