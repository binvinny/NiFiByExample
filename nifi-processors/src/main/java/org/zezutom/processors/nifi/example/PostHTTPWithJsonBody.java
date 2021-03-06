/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zezutom.processors.nifi.example;

import com.jayway.jsonpath.JsonPath;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

@Tags({"http", "https", "remote", "json"})
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Performs an HTTP Post with the specified JSON content.")
public class PostHTTPWithJsonBody extends AbstractProcessor {

    /** Processor Properties and Relationships **/

    public static final PropertyDescriptor PROP_URL = new PropertyDescriptor
            .Builder().name("URL")
            .description("The URL to post to, including scheme, host, port and path.")
            .expressionLanguageSupported(true)
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_BODY = new PropertyDescriptor
            .Builder().name("Request Body")
            .description("Must be a valid JSON.")
            .expressionLanguageSupported(true)
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("The SSL Context Service used to provide client certificate information for TLS/SSL (https) connections.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("Success")
            .description("Status code 2xx.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("Failure")
            .description("Status codes 4xx and 5xx.")
            .build();

    public static final String ATT_ERROR_MESSAGE = "error.message";

    private ComponentLog logger;

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    private HTTPClient httpClient;

    public PostHTTPWithJsonBody() {}

    // Allows for DI (only use in tests!)
    protected PostHTTPWithJsonBody(HTTPClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(PROP_URL);
        descriptors.add(PROP_BODY);
        descriptors.add(PROP_SSL_CONTEXT_SERVICE);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);

        logger = context.getLogger();
        if (logger == null) {
            throw new IllegalStateException("Logger can't be null!");
        }
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }


    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        // Get or create a request flow file
        FlowFile flowFile = session.get();
        if ( flowFile == null ) flowFile = session.create();

        try {
            if (httpClient == null) {
                SSLContextService sslContextService = context.getProperty(PROP_SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
                httpClient = new HTTPClient(sslContextService, logger);
            }

            URL url = httpClient.parseURL(context.getProperty(PROP_URL), flowFile);
            String json = parseJsonBody(context, flowFile);

            HTTPResponse httpResponse = httpClient.post(url, json);

            // Capture response properties and headers as attributes
            flowFile = session.putAllAttributes(flowFile, httpResponse.getAttributes());

            // Capture response body
            String resBody = httpResponse.getAttribute(HTTPClient.ATT_RES_BODY);
            if (resBody != null) {
                flowFile = session.write(flowFile, outputStream -> outputStream.write(resBody.getBytes()));
            }

            // Direct the flow file based on the response code
            int status = httpResponse.getStatus();
            if (status == HttpURLConnection.HTTP_CREATED || status == HttpURLConnection.HTTP_OK) {
                session.transfer(flowFile, REL_SUCCESS);
            }
            else {
                logger.error(String.format("There was an error with POST request, status: %s, response message: '%s'",
                        status, httpResponse.getMessage()));
                flowFile = session.putAttribute(flowFile, ATT_ERROR_MESSAGE, httpResponse.getMessage());
                session.transfer(flowFile, REL_FAILURE);
            }
        } catch (IOException e) {
            session.transfer(flowFile, REL_FAILURE);
            throw new ProcessException("Error when processing request", e);
        }
    }

    private String parseJsonBody(ProcessContext context, FlowFile flowFile) {
        String body = context.getProperty(PROP_BODY).evaluateAttributeExpressions(flowFile).getValue().trim();
        return JsonPath.parse(body).jsonString();
    }
}
