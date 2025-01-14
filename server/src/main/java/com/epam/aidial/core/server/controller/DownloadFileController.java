package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DownloadFileController extends AccessControlBaseController {

    public DownloadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't download a folder");
        }
        EtagHeader etagHeader = ProxyUtil.etag(context.getRequest());
        proxy.getVertx().executeBlocking(() -> proxy.getResourceService().getResourceStream(resource, etagHeader), false)
                .compose(resourceStream -> {
                    if (resourceStream == null) {
                        return context.respond(HttpStatus.NOT_FOUND);
                    }

                    HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, resourceStream.contentType())
                            // content-length removed by vertx
                            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(resourceStream.contentLength()))
                            .putHeader(HttpHeaders.ETAG, resourceStream.etag())
                            .exposeHeaders()
                            .getResponse();

                    InputStreamReader stream = new InputStreamReader(proxy.getVertx(), resourceStream.inputStream());
                    stream.pipeTo(response)
                            .onFailure(error -> {
                                stream.close();
                                response.reset();
                            });
                    return Future.succeededFuture();
                }).onFailure(error -> {
                    log.warn("Failed to download file: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to download file: " + resource.getUrl());
                });

        return Future.succeededFuture();
    }
}
