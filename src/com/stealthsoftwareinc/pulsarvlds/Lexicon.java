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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Lexicon implements ToJson {

  //--------------------------------------------------------------------
  // common
  //--------------------------------------------------------------------

  private static final String COMMON_KEY = "common";
  private LexiconCommon common_ = null;
  private boolean doneCommon_ = false;

  private LexiconCommon common(final Map<String, ?> src) {
    if (!doneCommon_) {
      common_ = Json.removeAs(src, COMMON_KEY, common_.fromJson());
      doneCommon_ = true;
    }
    return common_;
  }

  public final LexiconCommon common() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneCommon_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return common_;
  }

  //--------------------------------------------------------------------

  private final Map<Party, DbInfo> dbInfos_;

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  @Override
  public final Object toJson() {
    final Map<String, Object> dst = new HashMap<String, Object>();
    dst.put(COMMON_KEY, Json.getFrom(common()));
    for (final Map.Entry<Party, DbInfo> dbInfo : dbInfos_.entrySet()) {
      dst.put(dbInfo.getKey().toString(),
              Json.getFrom(dbInfo.getValue()));
    }
    return dst;
  }

  private Lexicon(final Map<String, ?> src,
                  final CreateFromJson<Lexicon> createFromJsonTag) {
    common(src);

    // TODO: convert this to the better form of dbInfos(src) and if
    // (!SST_NDEBUG) { dbInfos(); }.
    final Map<Party, DbInfo> dbInfos = new HashMap<Party, DbInfo>();
    for (final Party db : Party.dbValues()) {
      dbInfos.put(
          db,
          Json.removeAs(src, db.toString(), DbInfo.fromJson(db)));
    }
    {
      Party a = null;
      for (final Party b : Party.dbValues()) {
        if (a != null
            && !dbInfos.get(a).table().linkingColumn().linkingEquals(
                dbInfos.get(b).table().linkingColumn())) {
          throw new JsonException(
              "the linking column properties of ."
              + Json.smartQuoteKey(a.toString()) + " and ."
              + Json.smartQuoteKey(b.toString()) + " are different");
        }
        a = b;
      }
    }
    dbInfos_ = Collections.unmodifiableMap(dbInfos);

    if (!SST_NDEBUG) {
      common();
    }

    Json.unknownKey(src);
  }

  private Lexicon(final Object src,
                  final CreateFromJson<Lexicon> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), createFromJsonTag);
  }

  public static final CreateFromJson<Lexicon> fromJson() {
    return new CreateFromJson<Lexicon>() {
      @Override
      public final Lexicon createFromJson(final Object src) {
        return new Lexicon(src, this);
      }
    };
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final Map<Party, DbInfo> dbInfos() {
    return dbInfos_;
  }

  //--------------------------------------------------------------------
  // Table finding
  //--------------------------------------------------------------------

  public final Table findTable(final Collection<Party> dbs,
                               final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(dbs != null);
        for (final Party db : dbs) {
          SST_ASSERT(db.isDb());
        }
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final String nameString = name.toString();
    Table table = null;
    for (final Party db : dbs) {
      final Table x = dbInfos().get(db).table();
      if (nameString.equals(x.name())) {
        if (table != null) {
          throw new AmbiguousTableException(nameString);
        }
        table = x;
      }
    }
    if (table == null) {
      throw new UnknownTableException(nameString);
    }
    return table;
  }

  public final Table findTable(final Party db,
                               final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return findTable(Collections.singleton(db), name);
  }

  public final Table findTable(final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return findTable(Party.dbValues(), name);
  }

  //--------------------------------------------------------------------
  // Column finding
  //--------------------------------------------------------------------

  public final Column findColumn(final Collection<Party> dbs,
                                 final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(dbs != null);
        for (final Party db : dbs) {
          SST_ASSERT(db.isDb());
        }
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final String nameString = name.toString();
    final String[] pair = nameString.split("\\.", 2);
    if (pair.length == 2) {
      final Table table = findTable(dbs, pair[0]);
      final Column column = table.columns().get(pair[1]);
      if (column == null) {
        throw new UnknownColumnException(nameString);
      }
      return column;
    }
    Column column = null;
    for (final Party db : dbs) {
      final Column x =
          dbInfos().get(db).table().columns().get(nameString);
      if (x != null) {
        if (column != null) {
          throw new AmbiguousColumnException(nameString);
        }
        column = x;
      }
    }
    if (column == null) {
      throw new UnknownColumnException(nameString);
    }
    return column;
  }

  public final Column findColumn(final Party db,
                                 final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return findColumn(Collections.singleton(db), name);
  }

  public final Column findColumn(final CharSequence name) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(name != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return findColumn(Party.dbValues(), name);
  }

  //--------------------------------------------------------------------
  // Verifying that two lexicons match
  //--------------------------------------------------------------------

  public final boolean lexiconEquals(final Lexicon other) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(other != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!dbInfos().keySet().equals(other.dbInfos().keySet())) {
      return false;
    }
    for (final Map.Entry<Party, DbInfo> kv : dbInfos().entrySet()) {
      if (!kv.getValue().lexiconEquals(
              other.dbInfos().get(kv.getKey()))) {
        return false;
      }
    }
    return true;
  }

  //--------------------------------------------------------------------
}
