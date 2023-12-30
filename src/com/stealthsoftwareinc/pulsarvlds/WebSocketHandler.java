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
import com.stealthsoftwareinc.sst.JsonException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class WebSocketHandler
    extends SimpleChannelInboundHandler<WebSocketFrame> {

  private static final Object TICK = new Object();
  private final Globals globals_;
  private final Config config_;
  private final Map<String, Object> message_ =
      new HashMap<String, Object>();

  private final AtomicBoolean fatal_ = new AtomicBoolean(false);

  private Guid queryId_;
  private SharedWebSocketData swd_;
  private int tupleIndex_ = 0;

  public WebSocketHandler(final Globals globals) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    globals_ = globals;
    config_ = globals_.config();
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
      //cleanup();
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

  private final void handle(final ChannelHandlerContext ctx,
                            final TextWebSocketFrame frame)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(frame != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (queryId_ != null) {
      throw new RuntimeException("Unexpected second frame.");
    }
    try {
      queryId_ = Json.getAs(new JSONObject(frame.text()).toMap(),
                            "query_id",
                            queryId_.fromJson());
    } catch (final JsonException e) {
      throw e.add("<TextWebSocketFrame>");
    }
    swd_ = globals_.getSharedWebSocketData(queryId_);
    if (swd_ == null) {
      message_.clear();
      message_.put("type", "unknown_query");
      ctx.writeAndFlush(new TextWebSocketFrame(Json.dump(message_)));
      ctx.close();
      return;
    }
    final ChannelPipeline pipeline = ctx.channel().pipeline();
    globals_.workerThreadGroup().schedule(new Runnable() {
      @Override
      public final void run() {
        try {
          pipeline.fireUserEventTriggered(TICK);
        } catch (final Throwable e) {
          // TODO: asyncFatal(e);
        }
      }
    }, config_.resultUpdateCooldown(), TimeUnit.MILLISECONDS);
  }

  private final void handle(final ChannelHandlerContext ctx,
                            final WebSocketFrame frame)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(frame != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!(frame instanceof TextWebSocketFrame)) {
      throw new RuntimeException("Unexpected WebSocketFrame type: "
                                 + frame.getClass().getCanonicalName()
                                 + ".");
    }
    handle(ctx, (TextWebSocketFrame)frame);
  }

  @Override
  protected final void channelRead0(final ChannelHandlerContext ctx,
                                    final WebSocketFrame frame)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(frame != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (fatal_.get()) {
      return;
    }
    try {
      handle(ctx, frame);
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
  }

  @Override
  public final void userEventTriggered(final ChannelHandlerContext ctx,
                                       final Object event)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(event != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (event == TICK) {
      final int tupleIndex = swd_.tupleIndex.get();
      for (; tupleIndex_ < tupleIndex; ++tupleIndex_) {
        message_.clear();
        message_.put("type", "tuple_done");
        message_.put("data",
                     Arrays.asList(swd_.result.get(tupleIndex_)));
        ctx.write(new TextWebSocketFrame(Json.dump(message_)));
      }
      if (tupleIndex_ < swd_.tupleCount) {
        final SharedWebSocketData.Progress progress =
            swd_.progress.get(tupleIndex_);
        final long rowCount1 = progress.rowCount1.get();
        final long rowCount2 = progress.rowCount2.get();
        final long rowIndex1 = progress.rowIndex1.get();
        final long rowIndex2 = progress.rowIndex2.get();
        final double d = rowCount1 < 0 || rowCount2 < 0 ?
                             0.0 :
                             ((double)rowIndex1 + rowIndex2)
                                 / ((double)rowCount1 + rowCount2);
        message_.clear();
        message_.put("type", "tuple_progress");
        message_.put("data", d);
        ctx.writeAndFlush(new TextWebSocketFrame(Json.dump(message_)));
        final ChannelPipeline pipeline = ctx.channel().pipeline();
        globals_.workerThreadGroup().schedule(new Runnable() {
          @Override
          public final void run() {
            try {
              pipeline.fireUserEventTriggered(TICK);
            } catch (final Throwable e) {
              // TODO: asyncFatal(e);
            }
          }
        }, config_.resultUpdateCooldown(), TimeUnit.MILLISECONDS);
      } else {
        message_.clear();
        message_.put("type", "all_done");
        ctx.write(new TextWebSocketFrame(Json.dump(message_)));
        ctx.writeAndFlush(new CloseWebSocketFrame())
            .addListener(ChannelFutureListener.CLOSE);
      }
    } else {
      ctx.fireUserEventTriggered(event);
    }
  }
}
