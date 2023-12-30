#
# Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
#
# Permission is hereby granted, free of charge, to any person
# obtaining a copy of this software and associated documentation
# files (the "Software"), to deal in the Software without
# restriction, including without limitation the rights to use,
# copy, modify, merge, publish, distribute, sublicense, and/or
# sell copies of the Software, and to permit persons to whom the
# Software is furnished to do so, subject to the following
# conditions:
#
# The above copyright notice and this permission notice (including
# the next paragraph) shall be included in all copies or
# substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
# OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
# NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
# HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
# WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
# FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
# OTHER DEALINGS IN THE SOFTWARE.
#
# SPDX-License-Identifier: MIT
#

sst_include() {

  # Bash >=4.2: declare -g    SST_NDEBUG

  declare    array_flag
  declare    declarations
  declare    definitions
  declare    file
  declare    includes
  declare    variable

  includes=
  declarations=
  definitions=
  for file; do
    includes+=" . $(sst_quote "$file");"
    file=${file##*/}
    if [[ "$file" == define_* ]]; then
      variable=${file#define_}
      variable=${variable%%.*}
      if [[ "$variable" == *-[Aa] ]]; then
        array_flag=" -${variable##*-}"
        variable=${variable%-*}
      else
        array_flag=
      fi
      if ((!SST_NDEBUG)); then
        sst_expect_basic_identifier "$variable"
      fi
      declarations+=" declare$array_flag $variable;"
      declarations+=" declare ${variable}_is_set;"
      definitions+=" define_$variable;"
    fi
  done
  readonly includes
  readonly declarations
  readonly definitions

  printf '%s\n' "$includes$declarations$definitions"

}; readonly -f sst_include
