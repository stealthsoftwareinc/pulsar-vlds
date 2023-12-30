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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DomainIterator {
  private final List<Column> groupBys_;
  private final List<Integer> myGroupBys_;
  private final int[] positions_;
  private final int count_;
  private boolean done_ = false;
  private int index_ = -1;

  public DomainIterator(final Config config, final Query query) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(config != null);
        SST_ASSERT(query != null);
        SST_ASSERT(!query.groupBys().isEmpty());
        for (int i = 0; i < query.groupBys().size(); ++i) {
          final Column groupBy = query.groupBys().get(i);
          SST_ASSERT(groupBy.domain() != null);
          SST_ASSERT(groupBy.domain().size() > 0);
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    int count = 1;
    groupBys_ = query.groupBys();
    {
      final List<Integer> myGroupBys = new ArrayList<Integer>();
      for (int i = 0; i != groupBys_.size(); ++i) {
        if (groupBys_.get(i).db() == config.localParty()) {
          myGroupBys.add(i);
        }
        final int n = groupBys_.get(i).domain().size();
        if (count > Integer.MAX_VALUE / n) {
          throw new RuntimeException();
        }
        count *= n;
      }
      myGroupBys_ = Collections.unmodifiableList(myGroupBys);
    }
    positions_ = new int[groupBys_.size()];
    positions_[groupBys_.size() - 1] = -1;
    count_ = count;
  }

  public final void toSql(final StringBuilder sql,
                          final StringBuilder format) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(sql != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!myGroupBys_.isEmpty()) {
      for (final int i : myGroupBys_) {
        sql.append(" AND ");
        sql.append(groupBys_.get(i).underlyingName());
        sql.append(" = ?");
        if (format != null) {
          format.append(" AND ");
          format.append(
              groupBys_.get(i).underlyingName().replace("%", "%%"));
          format.append(" = %s");
        }
      }
    }
  }

  public final int count() {
    return count_;
  }

  public final boolean next() {
    if (!SST_NDEBUG) {
      try {
        for (final Column column : groupBys_) {
          SST_ASSERT(column.domain() != null);
          SST_ASSERT(column.domain().size() > 0);
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (done_) {
      return false;
    }
    for (int i = positions_.length - 1; i >= 0; --i) {
      if (++positions_[i] < groupBys_.get(i).domain().size()) {
        break;
      }
      positions_[i] = 0;
      if (i == 0) {
        done_ = true;
        return false;
      }
    }
    ++index_;
    return true;
  }

  public final boolean next(final List<Object> parameters) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(parameters != null);
        SST_ASSERT(parameters.isEmpty()
                   || parameters.size() == myGroupBys_.size());
        for (final Column column : groupBys_) {
          SST_ASSERT(column.domain() != null);
          SST_ASSERT(column.domain().size() > 0);
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!next()) {
      return false;
    }
    final boolean add = parameters.isEmpty();
    for (int i = 0; i != myGroupBys_.size(); ++i) {
      final int j = myGroupBys_.get(i);
      final Object parameter =
          groupBys_.get(j).domain().get(positions_[j]);
      if (add) {
        parameters.add(parameter);
      } else {
        parameters.set(i, parameter);
      }
    }
    return true;
  }

  public final boolean nextAll(final List<Object> parameters) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(parameters != null);
        SST_ASSERT(parameters.isEmpty()
                   || parameters.size() == groupBys_.size());
        for (final Column column : groupBys_) {
          SST_ASSERT(column.domain() != null);
          SST_ASSERT(column.domain().size() > 0);
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!next()) {
      return false;
    }
    final boolean add = parameters.isEmpty();
    for (int i = 0; i != groupBys_.size(); ++i) {
      final Object parameter =
          groupBys_.get(i).domain().get(positions_[i]);
      if (add) {
        parameters.add(parameter);
      } else {
        parameters.set(i, parameter);
      }
    }
    return true;
  }

  public final int index() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(index_ >= 0);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return index_;
  }
}
