package com.knowledgelink.common.error;

import com.knowledgelink.common.web.RequestIdFilter;
import java.util.List;

public record ErrorResponse(String code, String message, String requestId, boolean retryable, List<Detail> details) {

    public record Detail(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode, List<Detail> details) {
        return new ErrorResponse(
                errorCode.name(),
                errorCode.message(),
                RequestIdFilter.current(),
                errorCode.retryable(),
                List.copyOf(details));
    }
}
