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
import com.stealthsoftwareinc.sst.ImpossibleException;
import com.stealthsoftwareinc.sst.JdbcName;
import com.stealthsoftwareinc.sst.JdbcType;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.JsonException;
import com.stealthsoftwareinc.sst.ToJson;
import com.stealthsoftwareinc.sst.Types;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Column implements ToJson {

  //--------------------------------------------------------------------
  // scale
  //--------------------------------------------------------------------

  private static final String SCALE_KEY = "scale";
  private static final int DEFAULT_SCALE = 0;
  private int scale_;
  private boolean doneScale_ = false;

  private int scale(final Map<String, ?> src) {
    if (!doneScale_) {
      scale_ = Json.removeAs(src, SCALE_KEY, scale_, DEFAULT_SCALE);
      try {
        if (scale_ < 0) {
          throw new JsonException(
              "value must be a nonnegative integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(SCALE_KEY);
      }
      doneScale_ = true;
    }
    return scale_;
  }

  public final int scale() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneScale_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return scale_;
  }

  //--------------------------------------------------------------------

  private final Table table_;
  private JdbcName name_;
  private ColumnType type_;
  private List<Object> domain_;

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  private static final String NAME_KEY = "name";
  private static final String TYPE_KEY = "type";
  private static final String DOMAIN_KEY = "domain";

  @Override
  public final Map<String, Object> toJson() {
    final Map<String, Object> dst = new HashMap<String, Object>();
    // Strip the underlying name.
    dst.put(NAME_KEY, Json.getFrom(name_.name()));
    dst.put(SCALE_KEY, Json.getFrom(scale_));
    dst.put(TYPE_KEY, Json.getFrom(type_));
    if (domain_ != null) {
      final List<String> xs = new ArrayList<String>();
      for (final Object x : domain_) {
        xs.add(x == null ? null : x.toString());
      }
      dst.put(DOMAIN_KEY, xs);
    }
    return dst;
  }

  private Column(final Map<String, ?> src,
                 final Table table,
                 final CreateFromJson<Column> createFromJsonTag) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(table != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    table_ = table;

    name_ = Json.removeAs(src, NAME_KEY, name_.fromJson());

    scale(src);

    type_ = Json.removeAs(src,
                          TYPE_KEY,
                          type_.fromJson(),
                          ColumnType.STRING);

    final Object domainJson = Json.remove(src, DOMAIN_KEY);
    if (domainJson != null) {
      try {
        final List<Object> domain = new ArrayList<Object>();
        final Iterable<?> xs = Json.expectArray(domainJson);
        int i = 0;
        for (final Object x : xs) {
          try {
            final Object y;
            if (x == null) {
              y = null;
            } else {
              switch (type_) {
                case STRING: {
                  y = Json.getAs(x, (String)null);
                } break;
                default:
                  throw new ImpossibleException();
              }
            }
            domain.add(y);
          } catch (final JsonException e) {
            throw e.addIndex(i);
          }
          ++i;
        }
        if (domain.isEmpty()) {
          throw new JsonException("array must not be empty");
        }
        domain_ = Collections.unmodifiableList(domain);
      } catch (final JsonException e) {
        throw e.addKey(DOMAIN_KEY);
      }
    } else {
      domain_ = null;
    }

    Json.unknownKey(src);
  }

  private Column(final Object src,
                 final Table table,
                 final CreateFromJson<Column> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), table, createFromJsonTag);
  }

  public static final CreateFromJson<Column>
  fromJson(final Table table) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(table != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return new CreateFromJson<Column>() {
      @Override
      public final Column createFromJson(final Object src) {
        return new Column(src, table, this);
      }
    };
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final Table table() {
    return table_;
  }

  public final DbInfo dbInfo() {
    return table().dbInfo();
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

  public final ColumnType type() {
    return type_;
  }

  public final List<Object> domain() {
    return domain_;
  }

  //--------------------------------------------------------------------

  private JdbcType jdbcType_ = null;

  public final void jdbcType(final JdbcType jdbcType) {
    if (!SST_NDEBUG) {
      SST_ASSERT(jdbcType_ == null);
      SST_ASSERT(jdbcType != null);
    }
    boolean mismatch = true;
    switch (type_) {
      case STRING: {
        mismatch = !jdbcType.canSetFrom((String)null);
      } break;
    }
    if (mismatch) {
      throw new RuntimeException("column type mismatch");
    }
    jdbcType_ = jdbcType;
  }

  public final JdbcType jdbcType() {
    if (!SST_NDEBUG) {
      SST_ASSERT(jdbcType_ != null);
    }
    return jdbcType_;
  }

  //--------------------------------------------------------------------
  // Verifying that two linking columns match
  //--------------------------------------------------------------------

  public final boolean linkingEquals(final Column other) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(other != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    boolean b = true;
    b = b && type() == other.type();
    if (domain() == null) {
      b = b && other.domain() == null;
    } else {
      b = b && domain().equals(other.domain());
    }
    return b;
  }

  //--------------------------------------------------------------------
  // Verifying that two lexicons match
  //--------------------------------------------------------------------

  public final boolean lexiconEquals(final Column other) {
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
    if (!linkingEquals(other)) {
      return false;
    }
    return true;
  }

  //--------------------------------------------------------------------
}
