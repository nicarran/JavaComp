package org.javacomp.server;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.Nullable;
import org.javacomp.protocol.NullParams;
import org.javacomp.protocol.RequestParams;
import org.javacomp.server.handler.RequestHandler;
import org.javacomp.server.io.ResponseWriter;
import org.javacomp.server.io.StreamClosedException;

/**
 * Logic for dispatching requests to registered {@link RequestHandler} instances based on method
 * name.
 */
public class RequestDispatcher {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int MAX_REQUESTS_IN_QUEUE = 50;

  private final RequestParser requestParser;
  private final ExecutorService executor;
  private final BlockingQueue<RawRequest> requestQueue;
  private Future<?> dispatchFuture;

  private RequestDispatcher(Builder builder, ImmutableMap<String, RequestHandler> handlerRegistry) {
    this.requestParser = checkNotNull(builder.requestParser, "requestParser is not set");
    this.executor = checkNotNull(builder.executor, "executor is not set");
    this.requestQueue = new ArrayBlockingQueue<RawRequest>(MAX_REQUESTS_IN_QUEUE);
    this.dispatchFuture =
        executor.submit(
            new HandleRequestRunnable(
                checkNotNull(builder.gson, "gson"),
                checkNotNull(handlerRegistry, "handlerRegistry is not set"),
                checkNotNull(builder.responseWriter, "responseWriter is not set"),
                requestQueue));
  }

  /**
   * Reads a requset, dispatches it to the registered handler and writes response to client.
   *
   * @return whether the dispatcher can dispatch more requests. If it's false, the server should be
   *     shutdown and exit
   */
  public boolean dispatchRequest() {
    if (dispatchFuture.isCancelled() || dispatchFuture.isDone()) {
      logger.atSevere().log(
          "The dispatch thread exits. Stop parsing and dispatching new requests.");
    }

    RawRequest rawRequest;
    try {
      rawRequest = requestParser.parse();
    } catch (RequestException e) {
      logger.atSevere().withCause(e).log(
          "Malformed request received and unable to recover. Shutting down.");
      return false;
    } catch (StreamClosedException e) {
      logger.atSevere().withCause(e).log("Input stream closed, shutting down server.");
      return false;
    }

    while (!requestQueue.offer(rawRequest)) {
      RawRequest firstInQueue = requestQueue.poll();
      if (firstInQueue == null) {
        continue;
      }

      logger.atWarning().log(
          "Request queue is full. Dropping early request (%s) %s",
          firstInQueue.getContent().getId(), firstInQueue.getContent().getMethod());
      return true;
    }

    return true;
  }

  private static class HandleRequestRunnable implements Runnable {
    private final Gson gson;
    private final ImmutableMap<String, RequestHandler> handlerRegistry;
    private final ResponseWriter responseWriter;
    private final BlockingQueue<RawRequest> requestQueue;

    private HandleRequestRunnable(
        Gson gson,
        ImmutableMap<String, RequestHandler> handlerRegistry,
        ResponseWriter responseWriter,
        BlockingQueue<RawRequest> requestQueue) {
      this.gson = gson;
      this.handlerRegistry = handlerRegistry;
      this.responseWriter = responseWriter;
      this.requestQueue = requestQueue;
    }

    @Override
    public void run() {
      while (true) {
        try {
          RawRequest rawRequest = requestQueue.take();

          Object result = null;
          Response.ResponseError error = null;

          JsonElement requestIdElem = rawRequest.getContent().getId();
          JsonPrimitive requestId = null;
          if (requestIdElem != null && !requestIdElem.isJsonNull()) {
            if (requestIdElem.isJsonPrimitive()) {
              requestId = requestIdElem.getAsJsonPrimitive();
            } else {
              logger.atWarning().log("Invalid type of request ID: %s", requestIdElem);
            }
          }

          try {
            result = dispatchRequestInternal(rawRequest, requestId);
          } catch (RequestException e) {
            logger.atSevere().withCause(e).log("Failed to process request.");
            error = new Response.ResponseError(e.getErrorCode(), e.getMessage());
          } catch (Throwable e) {
            logger.atSevere().withCause(e).log("Failed to process request.");
            error = new Response.ResponseError(ErrorCode.INTERNAL_ERROR, e.getMessage());
          }

          if (requestId == null) {
            // No ID provided. The request is a notification and the client doesn't expect any
            // response.
            continue;
          }
          if (!requestId.isNumber() && !requestId.isString()) {
            logger.atWarning().log("Invalid type of request ID: %s", requestId);
            continue;
          }

          Response response;
          if (error != null) {
            response = Response.createError(requestId, error);
          } else {
            response = Response.createResponse(requestId, result);
          }
          try {
            responseWriter.writeResponse(response);
          } catch (Throwable e) {
            logger.atSevere().withCause(e).log("Failed to write response, shutting down server.");
            return;
          }
        } catch (InterruptedException e) {
          logger.atInfo().log("Request dispatching thread is interrupted, shutting down.");
          return;
        }
      }
    }

    /**
     * Dispatches a {@link RawRequest} to the {@link RequestHandler} registered for the method of
     * the request.
     *
     * @return the result of processing the request
     */
    private Object dispatchRequestInternal(RawRequest rawRequest, @Nullable JsonPrimitive requestId)
        throws RequestException, Exception {
      RawRequest.Content requestContent = rawRequest.getContent();

      if (Strings.isNullOrEmpty(requestContent.getMethod())) {
        throw new RequestException(ErrorCode.INVALID_REQUEST, "Missing request method.");
      }

      if (!handlerRegistry.containsKey(requestContent.getMethod())) {
        throw new RequestException(
            ErrorCode.METHOD_NOT_FOUND, "Cannot find method %s", requestContent.getMethod());
      }

      RequestHandler handler = handlerRegistry.get(requestContent.getMethod());

      Request typedRequest = convertRawToRequest(rawRequest, requestId, handler);
      logger.atInfo().log("Handling request %s", requestContent.getMethod());
      @SuppressWarnings("unchecked")
      Object result = handler.handleRequest(typedRequest);
      return result;
    }

    @SuppressWarnings("unchecked")
    private Request convertRawToRequest(
        RawRequest rawRequest, @Nullable JsonPrimitive requestId, RequestHandler handler)
        throws RequestException {
      RequestParams requestParams;
      RawRequest.Content requestContent = rawRequest.getContent();
      Class<?> parameterType = handler.getParamsType();
      if (parameterType == NullParams.class) {
        requestParams = null;
      } else {
        if (requestContent.getParams() == null) {
          throw new RequestException(ErrorCode.INVALID_REQUEST, "Missing request params.");
        }
        // We don't know the type here, but it's OK since GSON has the type.
        requestParams = (RequestParams) gson.fromJson(requestContent.getParams(), parameterType);
      }

      return Request.create(
          rawRequest.getHeader(), requestContent.getMethod(), requestId, requestParams);
    }
  }

  /** Builder for {@link RequestDispatcher}. */
  public static class Builder {
    private Gson gson;
    private ImmutableMap.Builder<String, RequestHandler> registryBuilder;
    private RequestParser requestParser;
    private ResponseWriter responseWriter;
    private ExecutorService executor;

    public Builder() {
      this.registryBuilder = new ImmutableMap.Builder<>();
    }

    public Builder setGson(Gson gson) {
      this.gson = gson;
      return this;
    }

    public Builder setRequestParser(RequestParser requestParser) {
      this.requestParser = requestParser;
      return this;
    }

    public Builder setResponseWriter(ResponseWriter responseWriter) {
      this.responseWriter = responseWriter;
      return this;
    }

    /** Registers a {@link RequestHandler} to the {@link RequestDispatcher} to be built. */
    public Builder registerHandler(RequestHandler handler) {
      registryBuilder.put(handler.getMethod(), handler);
      return this;
    }

    public Builder setExecutor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public RequestDispatcher build() {
      return new RequestDispatcher(this, registryBuilder.build());
    }
  }
}
