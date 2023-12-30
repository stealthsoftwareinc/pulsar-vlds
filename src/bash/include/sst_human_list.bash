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

sst_human_list() {

  # Bash >=4.2: declare -g    SST_NDEBUG

  declare -a adjust
  declare    output
  declare    x

  if ((!SST_NDEBUG)); then
    if (($# == 0)); then
      sst_expect_argument_count $# 1-
    fi
  fi

  adjust=()
  while (($# > 0)) && [[ "$1" != : ]]; do
    adjust+=("$1")
    shift
  done
  readonly adjust

  if ((!SST_NDEBUG)); then
    if (($# == 0)); then
      sst_barf "The : argument must always be given."
    fi
  fi

  shift

  if (($# == 0)); then
    output="none"
  elif (($# == 1)); then
    if ((${#adjust[@]} > 0)); then
      output=$("${adjust[@]}" "$1" | sst_csf)
      sst_csf output
    else
      output=$1
    fi
  elif (($# == 2)); then
    if ((${#adjust[@]} > 0)); then
      output=$("${adjust[@]}" "$1" | sst_csf)
      sst_csf output
      output+=' and '
      x=$("${adjust[@]}" "$2" | sst_csf)
      sst_csf x
      output+=$x
    else
      output="$1 and $2"
    fi
  else
    if ((${#adjust[@]} > 0)); then
      output=$("${adjust[@]}" "$1" | sst_csf)
      sst_csf output
    else
      output=$1
    fi
    shift
    while (($# > 1)); do
      output+=', '
      if ((${#adjust[@]} > 0)); then
        x=$("${adjust[@]}" "$1" | sst_csf)
        sst_csf x
        output+=$x
      else
        output+=$1
      fi
      shift
    done
    output+=', and '
    if ((${#adjust[@]} > 0)); then
      x=$("${adjust[@]}" "$1" | sst_csf)
      sst_csf x
      output+=$x
    else
      output+=$1
    fi
  fi
  readonly output

  printf '%s\n' "$output"

}; readonly -f sst_human_list
