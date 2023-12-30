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
import com.stealthsoftwareinc.sst.PoolEntry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PhStartQueryEvent {

  private final StateStream stateStream_;
  private final String queryString_;
  private final Guid queryId_;
  private final Query query_;
  private final Party remoteParty_;
  private final PoolEntry<ChannelFuture> rawChannel_;
  private final ChannelPipeline httpPipeline_;

  public PhStartQueryEvent(final StateStream stateStream,
                           final String queryString,
                           final Guid queryId,
                           final Query query,
                           final Party remoteParty,
                           final PoolEntry<ChannelFuture> rawChannel,
                           final ChannelPipeline httpPipeline) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(stateStream != null);
        SST_ASSERT(queryString != null);
        SST_ASSERT(queryId != null);
        SST_ASSERT(query != null);
        SST_ASSERT(remoteParty != null);
        SST_ASSERT(rawChannel != null);
        SST_ASSERT(httpPipeline != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    stateStream_ = stateStream;
    queryString_ = queryString;
    queryId_ = queryId;
    query_ = query;
    remoteParty_ = remoteParty;
    rawChannel_ = rawChannel;
    httpPipeline_ = httpPipeline;
  }

  public final StateStream stateStream() {
    return stateStream_;
  }

  public final String queryString() {
    return queryString_;
  }

  public final Guid queryId() {
    return queryId_;
  }

  public final Query query() {
    return query_;
  }

  public final Party remoteParty() {
    return remoteParty_;
  }

  public final PoolEntry<ChannelFuture> rawChannel() {
    return rawChannel_;
  }

  public final ChannelPipeline httpPipeline() {
    return httpPipeline_;
  }
}
