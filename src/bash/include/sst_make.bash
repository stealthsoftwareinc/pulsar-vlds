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

sst_make() {

  # Bash >=4.2: declare -g    sst_root_tmpdir

  declare    s
  declare    stderr_cache
  declare    x

  stderr_cache=$sst_root_tmpdir/$FUNCNAME.$BASHPID.stderr_cache
  readonly stderr_cache

  while :; do

    make "$@" 2>"$stderr_cache" && :
    s=$?

    cat "$stderr_cache" >&2 || sst_err_trap "$sst_last_command"

    if ((s != 0)); then

      # Retry if GNU Make fails with the "jobserver tokens" error. At
      # the time of writing this comment, 2022-11-23, it is not known
      # what causes this error.
      x=$(
        sed -n '
          /INTERNAL: Exiting with .* jobserver token.* available; should be .*!/ {
            p
            q
          }
        ' "$stderr_cache"
      ) || sst_err_trap "$sst_last_command"
      if [[ "$x" ]]; then
        sst_warn \
          "GNU Make failed with the \"jobserver tokens\" error." \
          "Retrying." \
        || :
        continue
      fi

    fi

    return $s

  done

}; readonly -f sst_make
