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

#
# This function may be called by sst_install_utility, so we need to be
# careful to only use utilities that are always available and run them
# with "command", and we need to explicitly call sst_err_trap on error
# to handle errexit suspension correctly. errexit suspension will occur
# when the user uses idioms such as "foo || s=$?" or "if foo; then" and
# foo triggers our automatic utility installation system. In this case,
# we want to maintain the behavior expected by the user but still barf
# if the installation of foo fails.
#

sst_push_var() {

  sst_expect_argument_count $# 1-2

  sst_expect_basic_identifier "$1"

  eval '
    # Bash >=4.2: declare -g sst_var_depth_'$1'
    local -r sst_d=${sst_var_depth_'$1'-0}
    sst_var_depth_'$1'=$((sst_d + 1))

    # Bash >=4.2: declare -g sst_var_unset_${sst_d}_'$1'
    # Bash >=4.2: declare -g sst_var_value_${sst_d}_'$1'

    if [[ "${'$1'+x}" ]]; then
      eval sst_var_unset_${sst_d}_'$1'=
      eval sst_var_value_${sst_d}_'$1'=\$'$1'
    else
      eval sst_var_unset_${sst_d}_'$1'=1
    fi

    if (($# == 1)); then
      unset '$1'
    else
      '$1'=$2
    fi
  '

}; readonly -f sst_push_var
