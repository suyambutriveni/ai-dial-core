package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The container keeps data associated with API key.
 * <p>
 *     There are two types of API keys:
 *     <ul>
 *         <li>Project key is supplied from the Core config</li>
 *         <li>Per request key is generated in runtime and valid during the request</li>
 *     </ul>
 * </p>
 */
@Data
public class ApiKeyData {
    // per request key is available with during the request lifetime. It's generated in runtime
    private String perRequestKey;
    // the key of root request initiator
    private Key originalKey;
    // user claims extracted from JWT
    private ExtractedClaims extractedClaims;
    // OpenTelemetry trace ID
    private String traceId;
    // OpenTelemetry span ID created by the Core
    private String spanId;
    // list of attached file URLs collected from conversation history of the current request
    private Map<String, AutoSharedData> attachedFiles = new HashMap<>();
    private Map<String, AutoSharedData> attachedFolders = new HashMap<>();
    // deployment name of the source(application/assistant/model/interceptor) associated with the current request
    private String sourceDeployment;
    // Execution path of the root request
    private List<String> executionPath;
    // List of interceptors copied from the deployment config
    private List<String> interceptors;
    // Index to track which interceptor is called next
    private int interceptorIndex = -1;
    // deployment triggers interceptors
    private String initialDeployment;
    private String initialDeploymentApi;

    public ApiKeyData() {
    }

    public static void initFromContext(ApiKeyData proxyApiKeyData, ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        List<String> currentPath;
        proxyApiKeyData.setInterceptors(context.getInterceptors());
        proxyApiKeyData.setInitialDeployment(context.getInitialDeployment());
        proxyApiKeyData.setInitialDeploymentApi(context.getInitialDeploymentApi());

        if (apiKeyData.getPerRequestKey() == null) {
            proxyApiKeyData.setOriginalKey(context.getKey());
            proxyApiKeyData.setExtractedClaims(context.getExtractedClaims());
            proxyApiKeyData.setTraceId(context.getTraceId());
            currentPath = new ArrayList<>();
            currentPath.add(context.getProject() == null ? context.getUserHash() : context.getProject());
        } else {
            proxyApiKeyData.setOriginalKey(apiKeyData.getOriginalKey());
            proxyApiKeyData.setExtractedClaims(apiKeyData.getExtractedClaims());
            proxyApiKeyData.setTraceId(apiKeyData.getTraceId());
            currentPath = new ArrayList<>(context.getApiKeyData().getExecutionPath());
        }
        currentPath.add(context.getDeployment().getName());
        proxyApiKeyData.setExecutionPath(currentPath);
        proxyApiKeyData.setSpanId(context.getSpanId());
        proxyApiKeyData.setSourceDeployment(context.getDeployment().getName());
    }

    @JsonIgnore
    public boolean isInterceptor() {
        return perRequestKey != null && interceptors != null && interceptorIndex >= 0 && interceptorIndex < interceptors.size();
    }
}