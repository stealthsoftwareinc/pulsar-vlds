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

#
# This function has gone through many revisions regarding how to handle
# being called by several subshells, possibly concurrently. The current
# approach is simple and works well enough: every subshell collects its
# entire error message into a buffer before outputting it with a single
# command, and no attempt is made to determine or indicate any causal
# order between the messages. This means the messages will still look
# reasonable if concurrency affects their order, and any risk of
# concurrency causing message interleaving is reasonably low.
#

sst_barf() {

  # Bash >=4.2: declare -g    CLICOLOR
  # Bash >=4.2: declare -g    CLICOLOR_FORCE
  # Bash >=4.2: declare -g    SST_BARF_STATUS
  # Bash >=4.2: declare -g    sst_err_trap_status

  declare    buffer
  declare    color_start
  declare    color_stop
  declare    file
  declare    func
  declare    i
  declare    line
  declare    status
  declare    x
  declare    y

  if [[ "${CLICOLOR_FORCE-0}" != 0 || \
        ( "${CLICOLOR-1}" != 0 && -t 2 ) ]]; then
    color_start=$'\x1B[31m'
    color_stop=$'\x1B[0m'
  else
    color_start=
    color_stop=
  fi
  readonly color_start
  readonly color_stop

  status=1
  if [[ "${SST_BARF_STATUS+x}" ]]; then
    x='^(0|[1-9][0-9]{0,2})$'
    if [[ "$SST_BARF_STATUS" =~ $x ]] \
       && ((SST_BARF_STATUS <= 255)); then
      status=$SST_BARF_STATUS
    else
      x=\"
      for ((i = 0; i < ${#SST_BARF_STATUS}; ++i)); do
        y=${SST_BARF_STATUS:i:1}
        if [[ "$y" == [\"\\] ]]; then
          y=\\$y
        elif [[ "$y" < ' ' || "$y" > '~' ]]; then
          y=$(printf '\\x%02X' "'$y" 2>/dev/null) || y=?
        fi
        x+=$y
      done
      x+=\"
      sst_warn "Ignoring invalid SST_BARF_STATUS: $x."
    fi
  elif [[ "${sst_err_trap_status+x}" ]]; then
    status=$sst_err_trap_status
  fi
  readonly status

  if (($# == 0)); then
    set 'Unknown error.'
  fi

  buffer="$color_start$0: Error: $@.$color_stop"

  i=0
  while x=$(caller $i); do
    ((++i))

    x=${x##+([[:blank:]])}
    line=${x%%[[:blank:]]*}
    x=${x#"$line"}

    x=${x##+([[:blank:]])}
    func=${x%%[[:blank:]]*}
    x=${x#"$func"}

    x=${x##+([[:blank:]])}
    file=${x%%[[:blank:]]*}
    x=${x#"$file"}

    if [[ "${file##*/}" == sst.bash ]]; then
      if [[ "$func" != @(source|sst_prelude_*) ]]; then
        continue
      fi
    fi

    if [[ "${file##*/}" == sst_install_utility.bash ]]; then
      if [[ "$func" != sst_install_utility ]]; then
        continue
      fi
    fi

    if ((i == ${#FUNCNAME[@]} - 1)); then
      buffer+=$'\n'"$color_start    at $file:$line$color_stop"
    else
      buffer+=$'\n'"$color_start    at $func($file:$line)$color_stop"
    fi

  done

  readonly buffer

  printf '%s\n' "$buffer" >&2 || :

  exit $status

}; readonly -f sst_barf
