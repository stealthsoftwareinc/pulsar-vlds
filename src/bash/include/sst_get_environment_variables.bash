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

sst_get_environment_variables() {

  declare    count
  declare    name
  declare -a names
  declare    regex

  names=$(declare -x)
  names=${names//declare -x/}
  names=${names//[] ()[]/}
  eval names="($names)"
  readonly names

  count=0
  for name in ${names[@]+"${names[@]}"}; do
    name=${name%%=*}
    if [[ "$name" == *$'\n'* ]]; then
      continue
    fi
    for regex; do
      if [[ "$name" =~ $regex ]]; then
        printf '%s\n' "$name"
        ((++count))
        continue 2
      fi
    done
  done
  readonly count

  if ((count == 0)); then
    echo
  fi

}; readonly -f sst_get_environment_variables
