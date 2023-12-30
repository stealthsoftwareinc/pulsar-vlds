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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public final class RawChannelInitializer
    extends ChannelInitializer<SocketChannel> {

  private final Globals globals_;
  private final Party remoteParty_;
  private final SslContext sslCtx_;

  public RawChannelInitializer(final Globals globals,
                               final Party remoteParty)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    globals_ = globals;
    remoteParty_ = remoteParty;
    if (remoteParty_ == null) {
      sslCtx_ =
          SslContextBuilder
              .forServer(globals_.selfSignedCertificate().certificate(),
                         globals_.selfSignedCertificate().privateKey())
              .build();
    } else {
      sslCtx_ = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
    }
  }

  @Override
  protected final void initChannel(final SocketChannel channel)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(channel != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    channel.pipeline().addLast(sslCtx_.newHandler(channel.alloc()));
    channel.pipeline().addLast(new RawChannelHandler(globals_,
                                                     channel.pipeline(),
                                                     remoteParty_));
  }
}
