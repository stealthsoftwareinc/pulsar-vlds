//
// Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
//
// Permission is hereby granted, free of charge, to any person
// obtaining a copy of this software and associated documentation
// files (the "Software"), to deal in the Software without
// restriction, including without limitation the rights to use,
// copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following
// conditions:
//
// The above copyright notice and this permission notice (including
// the next paragraph) shall be included in all copies or
// substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
// HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
// WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
// OTHER DEALINGS IN THE SOFTWARE.
//
// SPDX-License-Identifier: MIT
//

package com.stealthsoftwareinc.pulsarvlds;

import static com.stealthsoftwareinc.sst.Assert.SST_ASSERT;
import static com.stealthsoftwareinc.sst.Assert.SST_NDEBUG;

import com.stealthsoftwareinc.sst.Guid;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.PoolEntry;
import com.stealthsoftwareinc.sst.QueryStringException;
import com.stealthsoftwareinc.sst.Uris;
import com.stealthsoftwareinc.sst.netty.HttpDirectoryServerCore;
import com.stealthsoftwareinc.sst.netty.HttpResponseStatusException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HttpChannelHandler
    extends SimpleChannelInboundHandler<HttpObject> {

  private final Globals globals_;
  private final Config config_;
  private final HttpDirectoryServerCore directoryServer_;

  private final AtomicBoolean fatal_ = new AtomicBoolean(false);

  //--------------------------------------------------------------------

  private static final String CONTENT_TYPE_PLAIN =
      "text/plain; charset=UTF-8";
  private static final String CONTENT_TYPE_HTML =
      "text/html; charset=UTF-8";
  private static final String CONTENT_TYPE_JSON =
      "application/json; charset=UTF-8";

  //--------------------------------------------------------------------

  private HttpRequest request_;
  private boolean skipToNextRequest_;
  private URI requestUri_;
  private String requestPath_;
  private String requestQuery_;
  private String requestContentType_;
  private String requestMediaType_;
  private Charset requestCharset_;
  private ByteBuf requestContent_;
  private boolean usingWebSocket_;

  private HttpResponse response_;
  private final StringBuilder responseBody_ = new StringBuilder();
  private final CharsetEncoder responseEncoder_ =
      CharsetUtil.encoder(CharsetUtil.UTF_8,
                          CodingErrorAction.REPORT,
                          CodingErrorAction.REPORT);

  //--------------------------------------------------------------------

  public HttpChannelHandler(final Globals globals) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    globals_ = globals;
    config_ = globals.config();
    if ("1".equals(System.getenv("USE_BUILD_TREE"))) {
      directoryServer_ = new HttpDirectoryServerCore(
          ConfigH.ABS_BUILDDIR + "/src/html");
    } else {
      directoryServer_ = new HttpDirectoryServerCore(
          config_.prefix() + ConfigH.PKGDATADIR + "/html");
    }
  }

  private void cleanup() {
    if (requestContent_ != null) {
      requestContent_.release();
      requestContent_ = null;
    }
  }

  private void printException(final Throwable e) {
    try {
      synchronized (globals_.stderr()) {
        e.printStackTrace(globals_.stderr());
      }
    } catch (final Throwable e2) {
    }
  }

  @Override
  public final void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(cause != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    fatal_.set(true);
    try {
      cleanup();
    } catch (final Throwable e) {
      printException(e);
    }
    try {
      ctx.close();
    } catch (final Throwable e) {
      printException(e);
    }
    printException(cause);
  }

  @Override
  public final void channelInactive(final ChannelHandlerContext ctx)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (fatal_.get()) {
      return;
    }
    try {
      cleanup();
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
  }

  //--------------------------------------------------------------------

  private void sendFullResponse(final ChannelHandlerContext ctx,
                                final CharSequence contentType)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(contentType != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    response_.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    final ByteBuf buffer = ctx.alloc().buffer();
    final ChannelFuture future;
    try {
      buffer.writeBytes(responseEncoder_.reset().encode(
          CharBuffer.wrap(responseBody_)));
      response_.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                              buffer.readableBytes());
      ctx.write(response_);
      future = ctx.writeAndFlush(new DefaultLastHttpContent(buffer));
    } catch (final Throwable e) {
      buffer.release();
      throw e;
    }
    future.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(final ChannelFuture future) {
        if (!SST_NDEBUG) {
          try {
            SST_ASSERT(future != null);
            SST_ASSERT(future.isDone());
          } catch (final Throwable e) {
            SST_ASSERT(e);
          }
        }
        try {
          future.sync();
        } catch (final Throwable e) {
          fatal_.set(true);
          try {
            future.channel().pipeline().fireExceptionCaught(e);
          } catch (final Throwable e2) {
          }
        }
      }
    });
    skipToNextRequest_ = true;
  }

  private void sendErrorResponse(final ChannelHandlerContext ctx,
                                 final HttpResponseStatus status,
                                 final Map<String, ?> json)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(status != null);
        SST_ASSERT(json != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    response_.setStatus(status);
    responseBody_.setLength(0);
    Json.dump(responseBody_, json, Json.DumpOptions.INDENT_2);
    sendFullResponse(ctx, CONTENT_TYPE_JSON);
  }

  private void sendErrorResponse(final ChannelHandlerContext ctx,
                                 final HttpResponseStatus status,
                                 final CharSequence error)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(status != null);
        SST_ASSERT(error != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    response_.setStatus(status);
    responseBody_.setLength(0);
    responseBody_.append("{\n");
    responseBody_.append("  \"error\": ");
    responseBody_.append(Json.quote(error));
    responseBody_.append("\n}\n");
    sendFullResponse(ctx, CONTENT_TYPE_JSON);
  }

  private void sendErrorResponse(final ChannelHandlerContext ctx,
                                 final HttpResponseStatus status)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(status != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    sendErrorResponse(ctx, status, "unknown");
  }

  //--------------------------------------------------------------------
  // /cancel
  //--------------------------------------------------------------------

  private void handleCancel(final ChannelHandlerContext ctx,
                            final String queryString) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (queryString != null) {
      for (final String term : queryString.split("&", -1)) {
        final String[] pair = term.split("=", 2);
        if (pair[0].equals("query")) {
          final String rhs = Uris.decode(pair[1]);
          final Guid id = Guid.fromString(rhs);
          final ActiveQuery query = globals_.getActiveQuery(id);
          if (query != null) {
            for (final RawChannelHandler handler :
                 new HashSet<RawChannelHandler>(query.rawHandlers())) {
              handler.asyncFatal(new RuntimeException("canceled"));
              query.rawHandlers().remove(handler);
            }
          }
        }
      }
    }
    response_.setStatus(HttpResponseStatus.OK);
    responseBody_.setLength(0);
    sendFullResponse(ctx, CONTENT_TYPE_PLAIN);
  }

  private void handleCancel(final ChannelHandlerContext ctx,
                            final HttpRequest request)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(ctx != null);
        SST_ASSERT(request == request_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() != HttpMethod.GET) {
      sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }
  }

  private void handleCancel(final ChannelHandlerContext ctx,
                            final HttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(request_.method() == HttpMethod.GET);
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
  }

  private void handleCancel(final ChannelHandlerContext ctx,
                            final LastHttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(request_.method() == HttpMethod.GET);
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    handleCancel(ctx, requestQuery_);
  }

  private void handleCancel(final ChannelHandlerContext ctx,
                            final HttpObject obj) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (obj instanceof HttpRequest) {
      handleCancel(ctx, (HttpRequest)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof HttpContent) {
      handleCancel(ctx, (HttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof LastHttpContent) {
      handleCancel(ctx, (LastHttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    ctx.read();
  }

  //--------------------------------------------------------------------
  // /lexicon
  //--------------------------------------------------------------------

  private void handleLexicon(final ChannelHandlerContext ctx,
                             final HttpRequest request)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(request == request_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() != HttpMethod.GET) {
      sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }
  }

  private void handleLexicon(final ChannelHandlerContext ctx,
                             final HttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
        SST_ASSERT(request_.method() == HttpMethod.GET);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
  }

  private void handleLexicon(final ChannelHandlerContext ctx,
                             final LastHttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
        SST_ASSERT(request_.method() == HttpMethod.GET);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    responseBody_.setLength(0);
    responseBody_.append(globals_.lexiconString());
    sendFullResponse(ctx, CONTENT_TYPE_JSON);
  }

  private void handleLexicon(final ChannelHandlerContext ctx,
                             final HttpObject obj) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (obj instanceof HttpRequest) {
      handleLexicon(ctx, (HttpRequest)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof HttpContent) {
      handleLexicon(ctx, (HttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof LastHttpContent) {
      handleLexicon(ctx, (LastHttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    ctx.read();
  }

  //--------------------------------------------------------------------
  // /query
  //--------------------------------------------------------------------

  private void handleQuery(final ChannelHandlerContext ctx,
                           final String queryString) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(queryString != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    final Query query;
    try {
      query = Query.fromQueryString(queryString,
                                    globals_.config().lexicon());
    } catch (final QueryStringException e) {
      sendErrorResponse(ctx,
                        HttpResponseStatus.BAD_REQUEST,
                        e.toJson());
      return;
    }

    final Guid queryId = new Guid();

    final SharedWebSocketData swd =
        globals_.createSharedWebSocketData(globals_, queryId, query);

    for (final Party party : Party.dbValues()) {
      for (final StateStream stateStream :
           Arrays.asList(StateStream.S1,
                         StateStream.S2,
                         StateStream.S3)) {
        final PoolEntry<ChannelFuture> channel =
            globals_.rawChannels(party).acquire();
        final PhStartQueryEvent event =
            new PhStartQueryEvent(stateStream,
                                  queryString,
                                  queryId,
                                  query,
                                  party,
                                  channel,
                                  ctx.pipeline());
        channel.object().addListener(new ChannelFutureListener() {
          @Override
          public final void operationComplete(
              final ChannelFuture future) throws Exception {
            if (!SST_NDEBUG) {
              try {
                try {
                  SST_ASSERT(future != null);
                } catch (final Throwable e) {
                  SST_ASSERT(e);
                }
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            if (future.isSuccess()) {
              channel.object()
                  .channel()
                  .pipeline()
                  .fireUserEventTriggered(event);
            } else {
              fatal_.set(true);
              try {
                ctx.fireExceptionCaught(future.cause());
              } catch (final Throwable e2) {
              }
            }
          }
        });
      }
    }

    final Map<String, Object> json = new HashMap<String, Object>();
    json.put("query_id", queryId.toJson());

    response_.setStatus(HttpResponseStatus.OK);
    responseBody_.setLength(0);
    Json.dump(responseBody_, json, Json.DumpOptions.INDENT_2);
    sendFullResponse(ctx, CONTENT_TYPE_JSON);
  }

  private void handleQuery(final ChannelHandlerContext ctx,
                           final HttpRequest request) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(request == request_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() == HttpMethod.POST) {
      if (requestMediaType_ != null
          && !requestMediaType_.equalsIgnoreCase(
              "application/x-www-form-urlencoded")) {
        sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
      }
    } else if (request_.method() != HttpMethod.GET) {
      sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }
  }

  private void handleQuery(final ChannelHandlerContext ctx,
                           final HttpContent content) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
        SST_ASSERT(request_.method() == HttpMethod.GET
                   || request_.method() == HttpMethod.POST);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() == HttpMethod.POST) {
      requestContent_.writeBytes(content.content());
    }
  }

  private void handleQuery(final ChannelHandlerContext ctx,
                           final LastHttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
        SST_ASSERT(request_.method() == HttpMethod.GET
                   || request_.method() == HttpMethod.POST);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() == HttpMethod.GET) {
      handleQuery(ctx, requestQuery_ == null ? "" : requestQuery_);
    } else {
      handleQuery(ctx, requestContent_.toString(requestCharset_));
    }
  }

  private void handleQuery(final ChannelHandlerContext ctx,
                           final HttpObject obj) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (obj instanceof HttpRequest) {
      handleQuery(ctx, (HttpRequest)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof HttpContent) {
      handleQuery(ctx, (HttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof LastHttpContent) {
      handleQuery(ctx, (LastHttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    ctx.read();
  }

  //--------------------------------------------------------------------
  // /result
  //--------------------------------------------------------------------

  private void handleResult(final ChannelHandlerContext ctx,
                            final HttpRequest request)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(requestPath_.equals("/result"));
        SST_ASSERT(ctx != null);
        SST_ASSERT(request == request_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (request_.method() != HttpMethod.GET) {
      sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
      return;
    }
    usingWebSocket_ =
        request_.headers().contains(HttpHeaderNames.UPGRADE,
                                    "websocket",
                                    true);
    if (usingWebSocket_) {
      ctx.fireChannelRead(request_);
      return;
    }
    // TODO: Serve a JSON response with information.
    sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
  }

  private void handleResult(final ChannelHandlerContext ctx,
                            final HttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(requestPath_.equals("/result"));
        SST_ASSERT(request_.method() == HttpMethod.GET);
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (usingWebSocket_) {
      ctx.fireChannelRead(content);
      return;
    }
  }

  private void handleResult(final ChannelHandlerContext ctx,
                            final LastHttpContent content)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(request_ != null);
        SST_ASSERT(requestPath_.equals("/result"));
        SST_ASSERT(request_.method() == HttpMethod.GET);
        SST_ASSERT(ctx != null);
        SST_ASSERT(content != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (usingWebSocket_) {
      ctx.fireChannelRead(content);
      return;
    }
  }

  private void handleResult(final ChannelHandlerContext ctx,
                            final HttpObject obj) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (obj instanceof HttpRequest) {
      handleResult(ctx, (HttpRequest)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof HttpContent) {
      handleResult(ctx, (HttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    if (obj instanceof LastHttpContent) {
      handleResult(ctx, (LastHttpContent)obj);
      if (skipToNextRequest_) {
        return;
      }
    }
    ctx.read();
  }

  //--------------------------------------------------------------------

  private final void handle(final ChannelHandlerContext ctx,
                            final HttpObject obj) throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (obj instanceof HttpRequest) {
      skipToNextRequest_ = false;
      request_ = (HttpRequest)obj;
      requestUri_ = new URI(request_.uri());
      globals_.log(request_.method() + " " + requestUri_);
      response_ = new DefaultHttpResponse(request_.protocolVersion(),
                                          HttpResponseStatus.OK);
      HttpUtil.setKeepAlive(response_, HttpUtil.isKeepAlive(request_));
      response_.headers().set(
          HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN,
          "*");
      requestPath_ = requestUri_.getPath();
      if (requestPath_ == null) {
        sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
        return;
      }
      requestQuery_ = requestUri_.getRawQuery();
      if (request_.method() == HttpMethod.POST
          || request_.method() == HttpMethod.PUT) {
        requestContentType_ = request_.headers().get("Content-Type");
        final CharSequence mediaType = HttpUtil.getMimeType(request_);
        if (mediaType == null) {
          requestMediaType_ = null;
        } else {
          requestMediaType_ = mediaType.toString();
        }
        requestCharset_ =
            HttpUtil.getCharset(request_, StandardCharsets.UTF_8);
        if (requestContent_ == null) {
          requestContent_ = Unpooled.buffer();
        } else {
          requestContent_.clear();
        }
      } else {
        requestContentType_ = null;
        requestMediaType_ = null;
        requestCharset_ = null;
      }
    }
    if (!skipToNextRequest_) {
      switch (requestPath_) {
        case "/cancel": {
          handleCancel(ctx, obj);
        } break;
        case "/lexicon": {
          handleLexicon(ctx, obj);
        } break;
        case "/query": {
          handleQuery(ctx, obj);
        } break;
        case "/result": {
          handleResult(ctx, obj);
        } break;
        default: {
          try {
            if (directoryServer_.handle(ctx, obj)) {
              ctx.read();
            } else {
              sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
            }
          } catch (final HttpResponseStatusException e) {
            sendErrorResponse(ctx, e.status());
          }
        } break;
      }
    }
    if (skipToNextRequest_) {
      ctx.read();
    }
  }

  //--------------------------------------------------------------------
  // Incoming message dispatching
  //--------------------------------------------------------------------

  @Override
  protected final void channelRead0(final ChannelHandlerContext ctx,
                                    final HttpObject obj)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(obj != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (fatal_.get()) {
      return;
    }
    try {
      handle(ctx, obj);
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
  }

  @Override
  public final void channelActive(final ChannelHandlerContext ctx)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (fatal_.get()) {
      return;
    }
    try {
      ctx.read();
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
  }

  //--------------------------------------------------------------------
}
