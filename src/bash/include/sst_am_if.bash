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

sst_am_if() {

  # Bash >=4.2: declare -g -a sst_am_if_vars
  # Bash >=4.2: declare -g    sst_am_suspend_if_depth

  declare    n

  if ((SST_DEBUG)); then

    if (($# != 1)); then
      sst_expect_argument_count $# 1
    fi
    sst_expect_basic_identifier "${1#!}"

    if ((${sst_am_suspend_if_depth=0} > 0)); then
      sst_barf \
        "$FUNCNAME must not be called" \
        "while sst_am_suspend_if is active." \
      ;
    fi

  fi

  if [[ "${sst_am_if_vars[@]+x}" ]]; then
    n=${#sst_am_if_vars[@]}
  else
    n=0
  fi
  readonly n

  sst_am_if_vars[n]=" $1"

  sst_am_append <<<"if $1"

}; readonly -f sst_am_if
