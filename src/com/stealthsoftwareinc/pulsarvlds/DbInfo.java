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

import com.stealthsoftwareinc.sst.CreateFromJson;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.ToJson;
import java.util.HashMap;
import java.util.Map;

public final class DbInfo implements ToJson {

  private final Party db_;
  private final Table table_;

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  private static final String TABLE_KEY = "table";

  @Override
  public final Map<String, ?> toJson() {
    final Map<String, Object> dst = new HashMap<String, Object>();
    dst.put(TABLE_KEY, Json.getFrom(table_));
    return dst;
  }

  private DbInfo(final Map<String, ?> src,
                 final Party db,
                 final CreateFromJson<DbInfo> createFromJsonTag) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    db_ = db;

    table_ = Json.removeAs(src, TABLE_KEY, Table.fromJson(this));

    Json.unknownKey(src);
  }

  private DbInfo(final Object src,
                 final Party db,
                 final CreateFromJson<DbInfo> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), db, createFromJsonTag);
  }

  public static final CreateFromJson<DbInfo> fromJson(final Party db) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return new CreateFromJson<DbInfo>() {
      @Override
      public final DbInfo createFromJson(final Object src) {
        return new DbInfo(src, db, this);
      }
    };
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final Party db() {
    return db_;
  }

  public final Table table() {
    return table_;
  }

  //--------------------------------------------------------------------
  // Verifying that two lexicons match
  //--------------------------------------------------------------------

  public final boolean lexiconEquals(final DbInfo other) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(other != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!table().lexiconEquals(other.table())) {
      return false;
    }
    return true;
  }

  //--------------------------------------------------------------------
}
