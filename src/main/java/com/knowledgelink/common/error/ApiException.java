package com.knowledgelink.common.error;

import java.util.List;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.Detail> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public ApiException(ErrorCode errorCode, List<ErrorResponse.Detail> details) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public static ApiException invalidField(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_REQUEST, List.of(new ErrorResponse.Detail(field, reason)));
    }

    public static ApiException invalidField(String field, List<String> reasons) {
        return new ApiException(ErrorCode.INVALID_REQUEST,
                reasons.stream().map(reason -> new ErrorResponse.Detail(field, reason)).toList());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ErrorResponse.Detail> getDetails() {
        return details;
    }
}
