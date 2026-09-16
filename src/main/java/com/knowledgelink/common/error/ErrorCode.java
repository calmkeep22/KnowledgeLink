package com.knowledgelink.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. 코드 이름이 응답의 {@code code} 값이며 message는 사용자에게 보여 줄 기본 문구다.
 * 내부 예외 메시지·원문은 응답에 싣지 않는다.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.", false),
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "허용되지 않은 필드가 포함되어 있습니다.", false),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.", false),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.", false),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다.", false),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 없거나 만료되었습니다. 새로 고친 뒤 다시 시도해 주세요.", false),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "초기 비밀번호를 먼저 변경해 주세요.", false),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자료를 찾을 수 없습니다.", false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.", false),
    INVALID_STATE(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 최신 상태를 확인해 주세요.", false),
    ACTIVE_SYNC_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 동기화 작업이 있습니다.", false),
    NO_ACCESSIBLE_SCOPE(HttpStatus.UNPROCESSABLE_ENTITY, "검색 가능한 프로젝트가 없습니다. 관리자에게 요청해 주세요.", false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 콘텐츠 형식입니다.", false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.", true),
    TEMPORARY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요.", true);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }
}
