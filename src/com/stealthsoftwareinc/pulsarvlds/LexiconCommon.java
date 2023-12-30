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
import com.stealthsoftwareinc.sst.ToJson;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public final class LexiconCommon implements ToJson {

  //--------------------------------------------------------------------
  // guid_size
  //--------------------------------------------------------------------

  private static final String GUID_SIZE_KEY = "guid_size";
  private static final int DEFAULT_GUID_SIZE = 16;
  private int guidSize_;
  private boolean doneGuidSize_ = false;

  private int guidSize(final Map<String, ?> src) {
    if (!doneGuidSize_) {
      guidSize_ = Json.removeAs(src,
                                GUID_SIZE_KEY,
                                guidSize_,
                                DEFAULT_GUID_SIZE);
      try {
        if (guidSize_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(GUID_SIZE_KEY);
      }
      doneGuidSize_ = true;
    }
    return guidSize_;
  }

  public final int guidSize() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneGuidSize_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return guidSize_;
  }

  //--------------------------------------------------------------------
  // linking_column_force_string
  //--------------------------------------------------------------------

  private static final String linkingColumnForceStringKey_ =
      "linking_column_force_string";
  private boolean linkingColumnForceString_ = false;
  private boolean doneLinkingColumnForceString_ = false;

  private final boolean
  linkingColumnForceString(final Map<String, ?> src) {
    if (!doneLinkingColumnForceString_) {
      linkingColumnForceString_ =
          Json.removeAs(src,
                        linkingColumnForceStringKey_,
                        linkingColumnForceString_,
                        linkingColumnForceString_);
      doneLinkingColumnForceString_ = true;
    }
    return linkingColumnForceString_;
  }

  public final boolean linkingColumnForceString() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneLinkingColumnForceString_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return linkingColumnForceString_;
  }

  private final void
  linkingColumnForceStringToJson(final Map<String, Object> dst) {
    dst.put(linkingColumnForceStringKey_, linkingColumnForceString_);
  }

  //--------------------------------------------------------------------
  // linking_column_size
  //--------------------------------------------------------------------

  private static final String linkingColumnSizeKey_ =
      "linking_column_size";
  private int linkingColumnSize_ = 8;
  private boolean doneLinkingColumnSize_ = false;

  private final int linkingColumnSize(final Map<String, ?> src) {
    if (!doneLinkingColumnSize_) {
      linkingColumnSize_ = Json.removeAs(src,
                                         linkingColumnSizeKey_,
                                         linkingColumnSize_,
                                         linkingColumnSize_);
      try {
        if (linkingColumnSize_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(linkingColumnSizeKey_);
      }
      doneLinkingColumnSize_ = true;
    }
    return linkingColumnSize_;
  }

  public final int linkingColumnSize() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneLinkingColumnSize_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return linkingColumnSize_;
  }

  private final void
  linkingColumnSizeToJson(final Map<String, Object> dst) {
    dst.put(linkingColumnSizeKey_, linkingColumnSize_);
  }

  //--------------------------------------------------------------------
  // linking_column_unicode
  //--------------------------------------------------------------------

  private static final String linkingColumnUnicodeKey_ =
      "linking_column_unicode";
  private boolean linkingColumnUnicode_ = false;
  private boolean doneLinkingColumnUnicode_ = false;

  private final boolean linkingColumnUnicode(final Map<String, ?> src) {
    if (!doneLinkingColumnUnicode_) {
      linkingColumnUnicode_ = Json.removeAs(src,
                                            linkingColumnUnicodeKey_,
                                            linkingColumnUnicode_,
                                            linkingColumnUnicode_);
      doneLinkingColumnUnicode_ = true;
    }
    return linkingColumnUnicode_;
  }

  public final boolean linkingColumnUnicode() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneLinkingColumnUnicode_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return linkingColumnUnicode_;
  }

  private final void
  linkingColumnUnicodeToJson(final Map<String, Object> dst) {
    dst.put(linkingColumnUnicodeKey_, linkingColumnUnicode_);
  }

  //--------------------------------------------------------------------
  // modulus
  //--------------------------------------------------------------------

  private static final String MODULUS_KEY = "modulus";
  private BigInteger modulus_ = null;
  private boolean doneModulus_ = false;

  private BigInteger modulus(final Map<String, ?> src) {
    if (!doneModulus_) {
      modulus_ = Json.removeAs(src, MODULUS_KEY, modulus_);
      try {
        if (modulus_.compareTo(BigInteger.ONE) < 0) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(MODULUS_KEY);
      }
      doneModulus_ = true;
    }
    return modulus_;
  }

  public final BigInteger modulus() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneModulus_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return modulus_;
  }

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  @Override
  public final Object toJson() {
    final Map<String, Object> dst = new HashMap<String, Object>();
    dst.put(GUID_SIZE_KEY, guidSize_);
    dst.put(MODULUS_KEY, modulus_.toString());
    linkingColumnForceStringToJson(dst);
    linkingColumnSizeToJson(dst);
    linkingColumnUnicodeToJson(dst);
    return dst;
  }

  private LexiconCommon(
      final Map<String, ?> src,
      final CreateFromJson<LexiconCommon> createFromJsonTag) {
    guidSize(src);
    linkingColumnForceString(src);
    linkingColumnSize(src);
    linkingColumnUnicode(src);
    modulus(src);

    if (!SST_NDEBUG) {
      guidSize();
      linkingColumnForceString();
      linkingColumnSize();
      linkingColumnUnicode();
      modulus();
    }

    Json.unknownKey(src);
  }

  private LexiconCommon(
      final Object src,
      final CreateFromJson<LexiconCommon> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), createFromJsonTag);
  }

  public static final CreateFromJson<LexiconCommon> fromJson() {
    return new CreateFromJson<LexiconCommon>() {
      @Override
      public final LexiconCommon createFromJson(final Object src) {
        return new LexiconCommon(src, this);
      }
    };
  }

  //--------------------------------------------------------------------
}
