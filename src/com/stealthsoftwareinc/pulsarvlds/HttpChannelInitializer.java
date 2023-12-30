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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

public final class HttpChannelInitializer
    extends ChannelInitializer<SocketChannel> {

  private final Globals globals_;

  public HttpChannelInitializer(final Globals globals) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    globals_ = globals;
  }

  private static void addLast(final SocketChannel channel,
                              final ChannelHandler handler) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(channel != null);
        SST_ASSERT(handler != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    channel.pipeline().addLast(handler.getClass().getSimpleName(),
                               handler);
  }

  @Override
  protected final void initChannel(final SocketChannel channel) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(channel != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    addLast(channel, new HttpServerCodec());
    addLast(channel, new HttpChannelHandler(globals_));
    addLast(channel, new HttpObjectAggregator(65536, true));
    addLast(channel, new WebSocketServerCompressionHandler());
    addLast(channel,
            new WebSocketServerProtocolHandler("/result", null, true));
    addLast(channel, new WebSocketHandler(globals_));

    /*
    // Check the csv folder everyday. Delete all files whose last-modified date is 24 hours
    channel.eventLoop().scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        final Path csvDir =
            Paths.get(globals_.config.get("vlds_home"), "csv");
        final long HOUR_IN_MS = 1000 * 60 * 60;
        try {
          Stream<Path> csvFiles = Files.list(csvDir);
          csvFiles
              .filter(path -> {
                try {
                  return Files
                             .readAttributes(path,
                                             BasicFileAttributes.class)
                             .lastModifiedTime()
                             .compareTo(FileTime.fromMillis(
                                 System.currentTimeMillis()
                                 - (24 * HOUR_IN_MS)))
                      < 0;
                } catch (Exception e) {
                  return false;
                }
              })
              .forEach(path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception e) {
                }
              });
        } catch (Exception e) {
        }
      }
    }, 0, 5, java.util.concurrent.TimeUnit.MINUTES);
*/
  }
}
