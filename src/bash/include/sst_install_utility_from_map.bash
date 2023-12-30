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

sst_install_utility_from_map() {

  # Bash >=4.2: declare -g sst_install_utility_from_map_initialized
  # Bash >=4.2: declare -g -A sst_utility_prefixes
  # Bash >=4.2: declare -g -A sst_utility_programs
  # Bash >=4.2: declare -g -A sst_utility_suffixes

  local info
  local map
  local -A packages
  local s
  local utility
  local x
  local y

  sst_expect_argument_count $# 1-

  map=$1
  readonly map
  sst_expect_basic_identifier "$map"

  shift

  sst_get_distro || sst_barf
  sst_get_distro_version || sst_barf

  packages=()

  for utility; do

    if [[ "${sst_utility_programs[$utility]-}" ]]; then
      continue
    fi

    info="$utility $sst_distro_version"
    eval 'info=${'$map'[$info]-}'

    if [[ ! "$info" ]]; then
      x=$(sst_smart_quote "$utility") || x=$utility
      sst_barf \
        "Missing installation information for" \
        "$x on $sst_distro $sst_distro_version." \
      ;
    fi

    if [[ "$info" == *' '* ]]; then
      x=${info%% *}
      sst_utility_prefixes[$utility]=
      sst_utility_programs[$utility]=$x
      sst_utility_suffixes[$utility]=
      type -f "$x" &>/dev/null && s=0 || s=$?
      if ((s == 1)); then
        sst_push_var IFS ' '
        for x in ${info#* }; do
          packages[$x]=1
        done
        sst_pop_var IFS
      elif ((s != 0)); then
        y=$(sst_smart_quote "$x") || y=$x
        sst_barf \
          "The following command failed with exit status $s:" \
          "type -f $y &>/dev/null" \
        ;
      fi
    else
      sst_expect_basic_identifier "$info"
      $info || sst_barf
    fi

  done

  sst_${sst_distro}_install_raw "${!packages[@]}" || sst_barf

}; readonly -f sst_install_utility_from_map
