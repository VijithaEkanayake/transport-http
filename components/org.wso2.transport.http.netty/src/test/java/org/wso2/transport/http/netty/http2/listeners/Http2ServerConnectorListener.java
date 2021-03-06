/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.http2.listeners;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.Http2PushPromise;
import org.wso2.transport.http.netty.message.HttpCarbonResponse;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code Http2ServerConnectorListener} is a HttpConnectorListener which receives messages and respond back with
 * different types of HTTP/2 messages depending on the request.
 */
public class Http2ServerConnectorListener implements HttpConnectorListener {

    private static final Logger logger = LoggerFactory.getLogger(Http2ServerConnectorListener.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private String expectedResource;
    private String[] promisedResources;

    public void setPromisedResources(String... promisedResources) {
        this.promisedResources = promisedResources;
    }

    public Http2ServerConnectorListener setExpectedResource(String requestedResource) {
        this.expectedResource = requestedResource;
        return this;
    }

    @Override
    public void onMessage(HTTPCarbonMessage httpRequest) {
        executor.execute(() -> {
            try {
                // Send Push Promise messages
                List<Http2PushPromise> promises = new ArrayList<>();
                if (promisedResources != null) {
                    for (String promisedResource : promisedResources) {
                        Http2PushPromise promise =
                                new Http2PushPromise(Constants.HTTP_GET_METHOD, promisedResource);
                        HttpResponseFuture responseFuture = httpRequest.pushPromise(promise);
                        responseFuture.sync();
                        Throwable error = responseFuture.getStatus().getCause();
                        if (error != null) {
                            responseFuture.resetStatus();
                            logger.error("Error occurred while sending push promises " + error.getMessage());
                        } else {
                            promises.add(promise);
                        }
                    }
                }

                // Send the intended response message
                HttpResponseFuture responseFuture = httpRequest.respond(generateResponseMessage(expectedResource));
                responseFuture.sync();
                Throwable error = responseFuture.getStatus().getCause();
                if (error != null) {
                    responseFuture.resetStatus();
                    logger.error("Error occurred while sending the response " + error.getMessage());
                }

                // Send Promised response message
                for (Http2PushPromise promise : promises) {
                    responseFuture = httpRequest.
                            pushResponse(generateResponseMessage("Resource for " + promise.getPath()), promise);
                    responseFuture.sync();
                    error = responseFuture.getStatus().getCause();
                    if (error != null) {
                        responseFuture.resetStatus();
                        logger.error("Error occurred while sending promised response " + error.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.error("Error occurred while processing message: " + e.getMessage());
            }
        });
    }

    private HTTPCarbonMessage generateResponseMessage(String response) throws UnsupportedEncodingException {
        HTTPCarbonMessage httpResponse =
                new HttpCarbonResponse(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        httpResponse.setHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.KEEP_ALIVE.toString());
        httpResponse.setHeader(HttpHeaderNames.CONTENT_TYPE.toString(), Constants.TEXT_PLAIN);
        httpResponse.setProperty(Constants.HTTP_STATUS_CODE, HttpResponseStatus.OK.code());

        byte[] responseByteValues = response.getBytes("UTF-8");
        ByteBuffer responseValueByteBuffer = ByteBuffer.wrap(responseByteValues);
        httpResponse.
                addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(responseValueByteBuffer)));
        return httpResponse;
    }

    @Override
    public void onError(Throwable throwable) {
    }
}
