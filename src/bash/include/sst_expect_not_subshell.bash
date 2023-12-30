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

sst_expect_not_subshell() {

  # Bash >=4.2: declare -g    SST_DEBUG

  declare    f

  if ((SST_DEBUG)); then
    if (($# == 1)); then
      sst_expect_basic_identifier "$1"
    elif (($# != 0)); then
      sst_expect_argument_count $# 0-1
    fi
  fi

  if ((BASH_SUBSHELL > 0)); then

    if (($# == 1)); then
      f=$1
    elif [[ "${FUNCNAME[1]}" == "${FUNCNAME[0]}" ]]; then
      f=${FUNCNAME[2]}
    else
      f=${FUNCNAME[1]}
    fi
    readonly f

    if [[ "$f" == - ]]; then
      sst_barf "This code must not be run in a subshell."
    else
      sst_barf "The $f function must not be called in a subshell."
    fi

  fi

}; readonly -f sst_expect_not_subshell
