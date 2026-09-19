package com.knowledgelink.demo.infrastructure.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 데모 AI 공급자 설정. 유료 공급자는 명시적으로 선택하고 모델과 키도 직접 제공해야 한다. */
@ConfigurationProperties("kl.demo.ai")
public record DemoAiProperties(
        @DefaultValue("fake") String provider,
        @DefaultValue OpenAi openai,
        @DefaultValue Bedrock bedrock
) {
    public record OpenAi(
            @DefaultValue("") String apiKey,
            @DefaultValue("") String model,
            @DefaultValue("https://api.openai.com/v1/responses") URI endpoint,
            @DefaultValue("20s") Duration timeout,
            @DefaultValue("") String embeddingModel,
            @DefaultValue("https://api.openai.com/v1/embeddings") URI embeddingsEndpoint
    ) {
        public OpenAi {
            requireHttps(endpoint, "OpenAI endpoint");
            requireHttps(embeddingsEndpoint, "OpenAI embeddings endpoint");
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("OpenAI timeout은 0보다 커야 합니다.");
            }
        }

        void requireConfigured() {
            if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
                throw new IllegalStateException(
                        "OpenAI 공급자를 사용하려면 OPENAI_API_KEY와 OPENAI_MODEL을 설정해야 합니다.");
            }
        }

        void requireEmbeddingConfigured() {
            if (apiKey == null || apiKey.isBlank() || embeddingModel == null || embeddingModel.isBlank()) {
                throw new IllegalStateException(
                        "OpenAI 임베딩을 사용하려면 OPENAI_API_KEY와 OPENAI_EMBEDDING_MODEL을 설정해야 합니다.");
            }
        }

        private static void requireHttps(URI uri, String name) {
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(name + "는 HTTPS여야 합니다.");
            }
        }
    }

    public record Bedrock(
            @DefaultValue("") String region,
            @DefaultValue("") String model,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("") String embeddingModel
    ) {
        public Bedrock {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Bedrock timeout은 0보다 커야 합니다.");
            }
        }

        void requireConfigured() {
            if (region == null || region.isBlank() || model == null || model.isBlank()) {
                throw new IllegalStateException(
                        "Bedrock 공급자를 사용하려면 AWS_REGION과 BEDROCK_MODEL_ID를 설정해야 합니다.");
            }
        }

        void requireEmbeddingConfigured() {
            if (region == null || region.isBlank() || embeddingModel == null || embeddingModel.isBlank()) {
                throw new IllegalStateException(
                        "Bedrock 임베딩을 사용하려면 AWS_REGION과 BEDROCK_EMBEDDING_MODEL_ID를 설정해야 합니다.");
            }
        }
    }
}
