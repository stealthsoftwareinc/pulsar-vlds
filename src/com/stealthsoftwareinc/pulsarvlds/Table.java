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
import com.stealthsoftwareinc.sst.JdbcName;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.JsonException;
import com.stealthsoftwareinc.sst.ToJson;
import com.stealthsoftwareinc.sst.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Table implements ToJson {

  private final DbInfo dbInfo_;
  private final JdbcName name_;
  private final Map<String, Column> columns_;
  private final Column linkingColumn_;

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  private static final String NAME_KEY = "name";
  private static final String COLUMNS_KEY = "columns";
  private static final String LINKING_COLUMN_KEY = "linking_column";

  private Table(final Map<String, ?> src,
                final DbInfo dbInfo,
                final CreateFromJson<Table> createFromJsonTag) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(dbInfo != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    dbInfo_ = dbInfo;

    name_ = Json.removeAs(src, NAME_KEY, JdbcName.fromJson());

    try {
      final List<Column> xs = new ArrayList<Column>();
      final Map<String, Column> ys = new HashMap<String, Column>();
      Json.getTo(Json.expectPresent(Json.remove(src, COLUMNS_KEY)),
                 xs,
                 Column.fromJson(this));
      for (final Column x : xs) {
        if (ys.put(x.name(), x) != null) {
          throw new JsonException("duplicate column name: "
                                  + Json.smartQuote(x.name()));
        }
      }
      columns_ = Collections.unmodifiableMap(ys);
    } catch (final JsonException e) {
      throw e.addKey(COLUMNS_KEY);
    }

    {
      final String x =
          Json.removeAs(src, LINKING_COLUMN_KEY, (String)null);
      linkingColumn_ = columns_.get(x);
      if (linkingColumn_ == null) {
        throw new JsonException(
            "." + Json.smartQuoteKey(LINKING_COLUMN_KEY)
            + " not present in . " + Json.smartQuoteKey(COLUMNS_KEY));
      }
    }

    Json.unknownKey(src);
  }

  private Table(final Object src,
                final DbInfo dbInfo,
                final CreateFromJson<Table> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), dbInfo, createFromJsonTag);
  }

  public static final CreateFromJson<Table>
  fromJson(final DbInfo dbInfo) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(dbInfo != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return new CreateFromJson<Table>() {
      @Override
      public final Table createFromJson(final Object src) {
        return new Table(src, dbInfo, this);
      }
    };
  }

  @Override
  public final Map<String, ?> toJson() {
    final Map<String, Object> dst = new HashMap<String, Object>();
    // Strip the underlying name.
    dst.put(NAME_KEY, Json.getFrom(name_.name()));
    dst.put(COLUMNS_KEY,
            Json.getFrom(columns_.values(),
                         Types.valueType(columns_.values())));
    dst.put(LINKING_COLUMN_KEY, Json.getFrom(linkingColumn_.name()));
    return dst;
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final DbInfo dbInfo() {
    return dbInfo_;
  }

  public final Party db() {
    return dbInfo().db();
  }

  public final String name() {
    return name_.name();
  }

  public final String underlyingName() {
    return name_.underlyingName();
  }

  public final Map<String, Column> columns() {
    return columns_;
  }

  public final Column linkingColumn() {
    return linkingColumn_;
  }

  //--------------------------------------------------------------------
  // Verifying that two lexicons match
  //--------------------------------------------------------------------

  public final boolean lexiconEquals(final Table other) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(other != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!name().equals(other.name())) {
      return false;
    }
    if (!columns().keySet().equals(other.columns().keySet())) {
      return false;
    }
    for (final Map.Entry<String, Column> kv : columns().entrySet()) {
      if (!kv.getValue().lexiconEquals(
              other.columns().get(kv.getKey()))) {
        return false;
      }
    }
    if (!linkingColumn().lexiconEquals(other.linkingColumn())) {
      return false;
    }
    return true;
  }

  //--------------------------------------------------------------------
}
