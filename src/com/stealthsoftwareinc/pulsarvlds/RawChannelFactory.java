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

import com.stealthsoftwareinc.sst.Supplier;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;

public final class RawChannelFactory
    implements Supplier<ChannelFuture> {
  private final Bootstrap bootstrap_;
  private final String host_;
  private final int port_;

  public RawChannelFactory(final Bootstrap bootstrap,
                           final CharSequence host,
                           final int port) {
    if (!SST_NDEBUG) {
      SST_ASSERT(bootstrap != null);
      SST_ASSERT(host != null);
      SST_ASSERT(port >= 1);
      SST_ASSERT(port <= 65535);
    }
    bootstrap_ = bootstrap;
    host_ = host.toString();
    port_ = port;
  }

  @Override
  public ChannelFuture get() {
    return bootstrap_.connect(host_, port_);
  }
}
