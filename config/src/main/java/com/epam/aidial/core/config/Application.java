package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Collection;
import java.util.Map;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Application extends Deployment {

    private Function function;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Function {

        private String id;
        private String runtime;
        private String authorBucket;
        private String sourceFolder;
        private String targetFolder;
        private Status status;
        private String error;
        private Mapping mapping;
        private Map<String, String> env;

        public enum Status {
            @JsonAlias("STARTING")
            DEPLOYING,
            @JsonAlias("STOPPING")
            UNDEPLOYING,
            @JsonAlias("STARTED")
            DEPLOYED,
            @JsonAlias({"CREATED", "STOPPED"})
            UNDEPLOYED,
            FAILED;

            public boolean isPending() {
                return switch (this) {
                    case DEPLOYED, FAILED, UNDEPLOYED -> false;
                    case DEPLOYING, UNDEPLOYING -> true;
                };
            }

            public boolean isActive() {
                return switch (this) {
                    case FAILED, UNDEPLOYED -> false;
                    case DEPLOYING, DEPLOYED, UNDEPLOYING -> true;
                };
            }
        }

        @Data
        @Accessors(chain = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public static class Mapping {
            @JsonAlias("completion")
            private String chatCompletion;
            private String rate;
            private String tokenize;
            private String truncatePrompt;
            private String configuration;
        }
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Logs {
        private Collection<Log> logs;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Log {
        private String instance;
        private String content;
    }
}