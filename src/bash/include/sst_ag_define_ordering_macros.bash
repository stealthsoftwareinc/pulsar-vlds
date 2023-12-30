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

sst_ag_define_ordering_macros() {

  declare    x
  declare    x_file
  declare    y
  declare    y_file
  declare -A y_to_xs
  declare    ys

  if (($# != 0)); then
    sst_expect_argument_count $# 0
  fi

  y_to_xs=()

  for x_file in m4/*.m4; do
    x=$x_file
    x=${x##*/}
    x=${x%.*}
    ys=$(
      sed -n '
        s/.*GATBPS_BEFORE(\[\$0\], \[\([^]]*\)\]).*/\1/p
      ' $x_file
    )
    for y in $ys; do
      y_to_xs[$y]+=" $x"
    done
  done

  for y in ${!y_to_xs[@]}; do
    y_file=m4/$y.m4
    if [[ ! -e $y_file ]]; then

      sst_info "Generating: $y_file"

      >$y_file

      sst_ihs <<<'
        dnl
        dnl Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
        dnl
        dnl Permission is hereby granted, free of charge, to any person
        dnl obtaining a copy of this software and associated documentation
        dnl files (the "Software"), to deal in the Software without
        dnl restriction, including without limitation the rights to use,
        dnl copy, modify, merge, publish, distribute, sublicense, and/or
        dnl sell copies of the Software, and to permit persons to whom the
        dnl Software is furnished to do so, subject to the following
        dnl conditions:
        dnl
        dnl The above copyright notice and this permission notice (including
        dnl the next paragraph) shall be included in all copies or
        dnl substantial portions of the Software.
        dnl
        dnl THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
        dnl EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
        dnl OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
        dnl NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
        dnl HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
        dnl WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
        dnl FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
        dnl OTHER DEALINGS IN THE SOFTWARE.
        dnl
        dnl SPDX-License-Identifier: MIT
        dnl

        AC_DEFUN_ONCE(['$y'], [
        GATBPS_CALL_COMMENT([$0]m4_if(m4_eval([$# > 0]), [1], [, $@]))
        { :
      ' >>$y_file

      for x in ${y_to_xs[$y]}; do
        sst_ihs -2 <<<'
          GATBPS_REQUIRE(['$x'])
        ' >>$y_file
      done

      sst_ihs <<<'
        }])
      ' >>$y_file

    fi
  done

}; readonly -f sst_ag_define_ordering_macros