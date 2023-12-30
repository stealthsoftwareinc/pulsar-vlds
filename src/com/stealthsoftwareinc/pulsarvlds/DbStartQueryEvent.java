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
import io.netty.channel.ChannelFuture;

public final class DbStartQueryEvent {

  private final String queryString_;
  private final Guid queryId_;
  private final Query query_;
  private final PoolEntry<ChannelFuture> channel_;

  public DbStartQueryEvent(final String queryString,
                           final Guid queryId,
                           final Query query,
                           final PoolEntry<ChannelFuture> channel) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(queryString != null);
        SST_ASSERT(queryId != null);
        SST_ASSERT(query != null);
        SST_ASSERT(channel != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    queryString_ = queryString;
    queryId_ = queryId;
    query_ = query;
    channel_ = channel;
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

  public final PoolEntry<ChannelFuture> channel() {
    return channel_;
  }
}
