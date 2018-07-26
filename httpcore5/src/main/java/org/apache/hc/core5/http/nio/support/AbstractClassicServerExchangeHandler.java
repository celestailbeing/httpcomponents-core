/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http.nio.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.message.HttpResponseWrapper;
import org.apache.hc.core5.http.nio.AsyncServerExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.entity.ContentInputStream;
import org.apache.hc.core5.http.nio.entity.ContentOutputStream;
import org.apache.hc.core5.http.nio.entity.SharedInputBuffer;
import org.apache.hc.core5.http.nio.entity.SharedOutputBuffer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Asserts;

/**
 * @since 5.0
 */
public abstract class AbstractClassicServerExchangeHandler implements AsyncServerExchangeHandler {

    private enum State { IDLE, ACTIVE, COMPLETED }

    /**
     * A HttpResponseWrapper that checks that the Response is already committed on some of its mutating API calls.
     */
    private static final class UncommittedHttpResponseWrapper
                    extends HttpResponseWrapper<UncommittedHttpResponseWrapper> {
        private final AtomicBoolean responseCommitted;

        private UncommittedHttpResponseWrapper(final HttpResponse<UncommittedHttpResponseWrapper> message,
                        final AtomicBoolean responseCommitted) {
            super(message);
            this.responseCommitted = responseCommitted;
        }

        private void checkUncommitted() {
            Asserts.check(!responseCommitted.get(), "Response already committed");
        }

        @Override
        public void addHeader(final String name, final Object value) {
            checkUncommitted();
            super.addHeader(name, value);
        }

        @Override
        public UncommittedHttpResponseWrapper setHeader(final String name, final Object value) {
            checkUncommitted();
            return super.setHeader(name, value);
        }

        @Override
        public UncommittedHttpResponseWrapper setVersion(final ProtocolVersion version) {
            checkUncommitted();
            return super.setVersion(version);
        }

        @Override
        public void setCode(final int code) {
            checkUncommitted();
            super.setCode(code);
        }

        @Override
        public void setReasonPhrase(final String reason) {
            checkUncommitted();
            super.setReasonPhrase(reason);
        }

        @Override
        public void setLocale(final Locale locale) {
            checkUncommitted();
            super.setLocale(locale);
        }
    }

    private final int initialBufferSize;
    private final Executor executor;
    private final AtomicReference<State> state;
    private final AtomicReference<Exception> exception;

    private volatile SharedInputBuffer inputBuffer;
    private volatile SharedOutputBuffer outputBuffer;

    public AbstractClassicServerExchangeHandler(final int initialBufferSize, final Executor executor) {
        this.initialBufferSize = Args.positive(initialBufferSize, "Initial buffer size");
        this.executor = Args.notNull(executor, "Executor");
        this.exception = new AtomicReference<>(null);
        this.state = new AtomicReference<>(State.IDLE);
    }

    public Exception getException() {
        return exception.get();
    }

    protected abstract void handle(
            HttpRequest request, InputStream requestStream,
            HttpResponse response, OutputStream responseStream,
            HttpContext context) throws IOException, HttpException;

    @Override
    public final void handleRequest(
            final HttpRequest request,
            final EntityDetails entityDetails,
            final ResponseChannel responseChannel,
            final HttpContext context) throws HttpException, IOException {
        final AtomicBoolean responseCommitted = new AtomicBoolean(false);

        final HttpResponse response = new BasicHttpResponse(HttpStatus.SC_OK);
        final HttpResponse responseWrapper = new UncommittedHttpResponseWrapper(response, responseCommitted);

        final InputStream inputStream;
        if (entityDetails != null) {
            inputBuffer = new SharedInputBuffer(initialBufferSize);
            inputStream = new ContentInputStream(inputBuffer);
        } else {
            inputStream = null;
        }
        outputBuffer = new SharedOutputBuffer(initialBufferSize);

        final OutputStream outputStream = new ContentOutputStream(outputBuffer) {

            private void triggerResponse() throws IOException {
                try {
                    if (responseCommitted.compareAndSet(false, true)) {
                        responseChannel.sendResponse(response, new EntityDetails() {

                            @Override
                            public long getContentLength() {
                                return -1;
                            }

                            @Override
                            public String getContentType() {
                                final Header h = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
                                return h != null ? h.getValue() : null;
                            }

                            @Override
                            public String getContentEncoding() {
                                final Header h = response.getFirstHeader(HttpHeaders.CONTENT_ENCODING);
                                return h != null ? h.getValue() : null;
                            }

                            @Override
                            public boolean isChunked() {
                                return false;
                            }

                            @Override
                            public Set<String> getTrailerNames() {
                                return null;
                            }

                        });
                    }
                } catch (final HttpException ex) {
                    throw new IOException(ex.getMessage(), ex);
                }
            }

            @Override
            public void close() throws IOException {
                triggerResponse();
                super.close();
            }

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {
                triggerResponse();
                super.write(b, off, len);
            }

            @Override
            public void write(final byte[] b) throws IOException {
                triggerResponse();
                super.write(b);
            }

            @Override
            public void write(final int b) throws IOException {
                triggerResponse();
                super.write(b);
            }

        };

        if (state.compareAndSet(State.IDLE, State.ACTIVE)) {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        handle(request, inputStream, responseWrapper, outputStream, context);
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        outputStream.close();
                    } catch (final Exception ex) {
                        exception.compareAndSet(null, ex);
                        if (inputBuffer != null) {
                            inputBuffer.abort();
                        }
                        outputBuffer.abort();
                    } finally {
                        state.set(State.COMPLETED);
                    }
                }

            });
        }
    }

    @Override
    public final void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
        if (inputBuffer != null) {
            inputBuffer.updateCapacity(capacityChannel);
        }
    }

    @Override
    public final int consume(final ByteBuffer src) throws IOException {
        Asserts.notNull(inputBuffer, "Input buffer");
        return inputBuffer.fill(src);
    }

    @Override
    public final void streamEnd(final List<? extends Header> trailers) throws HttpException, IOException {
        Asserts.notNull(inputBuffer, "Input buffer");
        inputBuffer.markEndStream();
    }

    @Override
    public final int available() {
        Asserts.notNull(outputBuffer, "Output buffer");
        return outputBuffer.length();
    }

    @Override
    public final void produce(final DataStreamChannel channel) throws IOException {
        Asserts.notNull(outputBuffer, "Output buffer");
        outputBuffer.flush(channel);
    }

    @Override
    public final void failed(final Exception cause) {
        exception.compareAndSet(null, cause);
        releaseResources();
    }

    @Override
    public void releaseResources() {
    }

}
