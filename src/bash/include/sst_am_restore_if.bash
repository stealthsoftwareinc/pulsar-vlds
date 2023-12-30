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

sst_am_restore_if() {

  # Bash >=4.2: declare -g    sst_am_suspend_if_depth
  # Bash >=4.2: declare -g -a sst_am_suspend_if_vars

  declare    a
  declare    b
  declare    i
  declare    n

  if ((SST_DEBUG)); then
    if (($# > 0)); then
      sst_expect_argument_count $# 0
    fi
    if ((${sst_am_suspend_if_depth=0} == 0)); then
      sst_barf "Orphan sst_am_restore_if."
    fi
  fi

  if ((--sst_am_suspend_if_depth == 0)); then
    n=${#sst_am_suspend_if_vars[@]}
    readonly n
    for ((i = 0; i < n; ++i)); do
      a=${sst_am_suspend_if_vars[i]}
      a="${a# } "
      b=${a#* }
      a=${a%% *}
      if [[ ${a:0:1} != - ]]; then
        sst_am_if $a
      else
        a=!${a#-}
        a=${a#!!}
        sst_am_if $a
        if [[ ! "$b" ]]; then
          sst_am_else
        else
          a=${b%% *}
          b=${b#* }
          while [[ "$b" ]]; do
            a=!${a#-}
            a=${a#!!}
            sst_am_elif $a
            a=${b%% *}
            b=${b#* }
          done
          if [[ ${a:0:1} != - ]]; then
            sst_am_elif $a
          else
            a=!${a#-}
            a=${a#!!}
            sst_am_elif $a
            sst_am_else
          fi
        fi
      fi
    done
  fi

}; readonly -f sst_am_restore_if
