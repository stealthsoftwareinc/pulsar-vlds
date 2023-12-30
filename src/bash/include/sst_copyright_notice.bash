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

sst_copyright_notice() {

  declare    comment1
  declare    comment2
  declare    comment3
  declare    default_prose
  declare    notice
  declare    prose

  default_prose='
Copyright (C) 2018-2023 Stealth Software Technologies, Inc.

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or
sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice (including
the next paragraph) shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

SPDX-License-Identifier: MIT
'
  default_prose=${default_prose#?}
  readonly default_prose

  case $# in 1)
    comment1=$1
    comment2=$1
    comment3=$1
    prose=$default_prose
  ;; 2)
    comment1=$1
    comment2=$1
    comment3=$1
    prose=$2
  ;; 3)
    comment1=$1
    comment2=$2
    comment3=$3
    prose=$default_prose
  ;; 4)
    comment1=$1
    comment2=$2
    comment3=$3
    prose=$4
  ;; *)
    sst_expect_argument_count $# 1-4
  esac

  readonly comment1
  readonly comment2
  readonly comment3

  if [[ "$prose" == - ]]; then
    prose=$(cat | sst_csf) || sst_err_trap "$sst_last_command"
    sst_csf prose || sst_err_trap "$sst_last_command"
  elif [[ "$prose" == /* || "$prose" == ./* ]]; then
    prose=$(cat "$prose" | sst_csf) || sst_err_trap "$sst_last_command"
    sst_csf prose || sst_err_trap "$sst_last_command"
  else
    prose=${prose%$'\n'}
  fi

  readonly prose

  notice=$comment1
  notice+=$'\n'
  notice+="$comment2 "
  notice+=${prose//$'\n'/$'\n'"$comment2 "}
  notice+=$'\n'
  notice+=$comment3

  readonly notice

  printf '%s\n' "$notice"

}; readonly -f sst_copyright_notice
