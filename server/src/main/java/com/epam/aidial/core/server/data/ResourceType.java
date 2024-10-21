package com.epam.aidial.core.server.data;

import lombok.Getter;

@Getter
public enum ResourceType {
    FILE("files"), CONVERSATION("conversations"), PROMPT("prompts"), LIMIT("limits"),
    SHARED_WITH_ME("shared_with_me"), SHARED_BY_ME("shared_by_me"), INVITATION("invitations"),
    PUBLICATION("publications"), RULES("rules"), API_KEY_DATA("api_key_data"), NOTIFICATION("notifications"),
    APPLICATION("applications"), DEPLOYMENT_COST_STATS("deployment_cost_stats");

    private final String group;

    ResourceType(String group) {
        this.group = group;
    }

    public static ResourceType of(String group) {
        return switch (group) {
            case "files" -> FILE;
            case "conversations" -> CONVERSATION;
            case "prompts" -> PROMPT;
            case "invitations" -> INVITATION;
            case "publications" -> PUBLICATION;
            case "applications" -> APPLICATION;
            default -> throw new IllegalArgumentException("Unsupported resource type: " + group);
        };
    }
}