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
import com.stealthsoftwareinc.sst.JsonException;
import java.util.HashMap;
import java.util.Map;

public final class ChannelOutputBufferLimit {
  private static final int DEFAULT_LIMIT = 64;
  private final Map<Party, Integer> limits_ =
      new HashMap<Party, Integer>();

  private ChannelOutputBufferLimit(
      final Object src,
      final CreateFromJson<ChannelOutputBufferLimit> createFromJsonTag,
      int copyTag) {
    if (Json.isObject(src)) {
      for (final Party party : Party.values()) {
        final int limit = Json.removeAs(src,
                                        party.toString(),
                                        (Integer)null,
                                        DEFAULT_LIMIT);
        try {
          if (limit < 1) {
            throw new JsonException("value must be a positive integer");
          }
          if (limit > Integer.MAX_VALUE / 1024) {
            throw new JsonException("value is too large");
          }
        } catch (final JsonException e) {
          throw e.addKey(party.toString());
        }
        limits_.put(party, limit * 1024);
      }
      Json.unknownKey(src);
    } else {
      final int limit =
          src != null ? Json.getAs(src, (Integer)null) : DEFAULT_LIMIT;
      if (limit < 1) {
        throw new JsonException("value must be a positive integer");
      }
      for (final Party party : Party.values()) {
        limits_.put(party, limit * 1024);
      }
    }
  }

  private ChannelOutputBufferLimit(
      final Object src,
      final CreateFromJson<ChannelOutputBufferLimit>
          createFromJsonTag) {
    this(Json.copy(src), createFromJsonTag, 0);
  }

  public static final CreateFromJson<ChannelOutputBufferLimit>
  fromJson() {
    return new CreateFromJson<ChannelOutputBufferLimit>() {
      @Override
      public final ChannelOutputBufferLimit createFromJson(
          final Object src) {
        return new ChannelOutputBufferLimit(src, this);
      }
    };
  }

  public final int get(final Party party) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(party != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return limits_.get(party);
  }
}
