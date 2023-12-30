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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class SharedWebSocketData {
  public static final class Progress {
    public final AtomicLong rowCount1 = new AtomicLong(-1);
    public final AtomicLong rowCount2 = new AtomicLong(-1);
    public final AtomicLong rowIndex1 = new AtomicLong();
    public final AtomicLong rowIndex2 = new AtomicLong();
  }

  private final Globals globals_;
  private final Guid queryId_;
  private final Query query_;
  private final DomainIterator domainIterator_;
  public final int tupleCount;
  private final int columnCount_;
  public final AtomicInteger tupleIndex = new AtomicInteger(0);
  public final List<Progress> progress;
  public final List<BigDecimal[]> result;

  public SharedWebSocketData(final Globals globals,
                             final Guid queryId,
                             final Query query) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
        SST_ASSERT(queryId != null);
        SST_ASSERT(query != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    globals_ = globals;
    queryId_ = queryId;
    query_ = query;
    domainIterator_ = new DomainIterator(globals_.config(), query_);
    tupleCount = domainIterator_.count();
    columnCount_ = query_.aggregates().size();
    progress = new ArrayList<Progress>(tupleCount);
    result = new ArrayList<BigDecimal[]>(tupleCount);
    for (int i = 0; i < tupleCount; ++i) {
      progress.add(new Progress());
      result.add(new BigDecimal[columnCount_]);
    }
  }
}
