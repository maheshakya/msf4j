/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.msf4j.internal;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.CarbonMessageProcessor;
import org.wso2.carbon.messaging.TransportSender;
import org.wso2.msf4j.Request;
import org.wso2.msf4j.Response;
import org.wso2.msf4j.internal.router.HandlerException;
import org.wso2.msf4j.internal.router.HttpMethodInfo;
import org.wso2.msf4j.internal.router.HttpMethodInfoBuilder;
import org.wso2.msf4j.internal.router.HttpResourceModel;
import org.wso2.msf4j.internal.router.PatternPathRouter;
import org.wso2.msf4j.util.HttpUtil;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import javax.ws.rs.core.MediaType;

/**
 * Process carbon messages for MSF4J.
 */
@Component(
        name = "org.wso2.msf4j.internal.MSF4JMessageProcessor",
        immediate = true,
        service = CarbonMessageProcessor.class
)
public class MSF4JMessageProcessor implements CarbonMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MSF4JMessageProcessor.class);
    private MicroservicesRegistry microservicesRegistry;
    private static final String MSF4J_MSG_PROC_ID = "MSF4J-CM-PROCESSOR";

    public MSF4JMessageProcessor() {
        this.microservicesRegistry = MicroservicesRegistry.getInstance();
    }

    public MSF4JMessageProcessor(MicroservicesRegistry microservicesRegistry) {
        this.microservicesRegistry = microservicesRegistry;
    }

    /**
     * Carbon message handler.
     */
    @Override
    public boolean receive(CarbonMessage carbonMessage, CarbonCallback carbonCallback) {
        Request request = new Request(carbonMessage);
        Response response = new Response(carbonCallback);
        try {
            dispatchMethod(request, response);
        } catch (HandlerException e) {
            handleHandlerException(e, carbonCallback);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof HandlerException) {
                handleHandlerException((HandlerException) targetException, carbonCallback);
            } else {
                handleThrowable(targetException, carbonCallback);
            }
        } catch (InterceptorException e) {
            log.warn("Interceptors threw an exception", e);
            // TODO: improve the response
            carbonCallback.done(HttpUtil
                    .createTextResponse(javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                            HttpUtil.EMPTY_BODY));
        } catch (Throwable t) {
            handleThrowable(t, carbonCallback);
        }
        return true;
    }

    /**
     * Dispatch appropriate resource method.
     */
    private void dispatchMethod(Request request, Response response) throws Exception {
        HttpUtil.setConnectionHeader(request, response);
        PatternPathRouter.RoutableDestination<HttpResourceModel> destination = microservicesRegistry
                .getHttpResourceHandler()
                .getDestinationMethod(request.getUri(),
                        request.getHttpMethod(),
                        request.getContentType(),
                        request.getAcceptTypes());
        HttpResourceModel resourceModel = destination.getDestination();
        response.setMediaType(getResponseType(request.getAcceptTypes(),
                resourceModel.getProducesMediaTypes()));
        InterceptorExecutor interceptorExecutor = InterceptorExecutor
                .instance(resourceModel,
                        request,
                        response,
                        microservicesRegistry.getInterceptors());
        if (interceptorExecutor.execPreCalls()) { // preCalls can throw exceptions

            HttpMethodInfoBuilder httpMethodInfoBuilder = HttpMethodInfoBuilder
                    .getInstance()
                    .httpResourceModel(resourceModel)
                    .httpRequest(request)
                    .httpResponder(response)
                    .requestInfo(destination.getGroupNameValues());

            HttpMethodInfo httpMethodInfo = httpMethodInfoBuilder.build();
            if (httpMethodInfo.isStreamingSupported()) {
                // TODO: introduce a true async model
                for (ByteBuffer byteBuffer : request.getFullMessageBody()) {
                    httpMethodInfo.chunk(byteBuffer);
                }
                httpMethodInfo.end();
            } else {
                httpMethodInfo.invoke();
            }
            interceptorExecutor.execPostCalls(response.getStatusCode()); // postCalls can throw exceptions
        }
    }

    private void handleThrowable(Throwable t, CarbonCallback carbonCallback) {
        log.warn("Unmapped exception", t);
        // TODO: improve the response and add exception mapping
        carbonCallback.done(HttpUtil
                .createTextResponse(javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                        HttpUtil.EMPTY_BODY));
    }

    private void handleHandlerException(HandlerException e, CarbonCallback carbonCallback) {
        carbonCallback.done(e.getFailureResponse());
    }

    /**
     * Process accept type considering the produce type and the
     * accept types of the request header.
     *
     * @param acceptTypes accept types of the request.
     * @return processed accept type
     */
    private String getResponseType(List<String> acceptTypes, List<String> producesMediaTypes) {
        String responseType = MediaType.WILDCARD;
        if (!producesMediaTypes.contains(MediaType.WILDCARD) && acceptTypes != null) {
            responseType =
                    (acceptTypes.contains(MediaType.WILDCARD)) ? producesMediaTypes.get(0) :
                            producesMediaTypes.stream().filter(acceptTypes::contains).findFirst().get();
        }
        return responseType;
    }

    @Override
    public void setTransportSender(TransportSender transportSender) {
    }

    @Override
    public String getId() {
        return MSF4J_MSG_PROC_ID;
    }
}
