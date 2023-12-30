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

sst_dpkg_loop() {

  # Bash >=4.2: declare -g    sst_root_tmpdir

  declare    n
  declare    s
  declare    stderr_cache
  declare    x

  stderr_cache=$sst_root_tmpdir/$FUNCNAME.$BASHPID.stderr_cache
  readonly stderr_cache

  n=2
  while :; do
    # Note that putting 2>"$stderr_cache" inside the eval means that,
    # e.g., sst_dpkg_loop 'sudo apt-get update >&2' will forward its
    # original stdout to stderr and its original stderr to the cache
    # file. This is useful for running incidental dpkg commands that
    # shouldn't interfere with stdout.
    eval "$@" '2>"$stderr_cache"' && :
    s=$?
    if ((s == 0)); then
      break
    fi
    x=$(
      sed '
        /is another process using it/ {
          p
          q
        }
      ' "$stderr_cache"
    ) || sst_err_trap "$sst_last_command"
    if [[ ! "$x" ]]; then
      cat "$stderr_cache" >&2
      exit $s
    fi
    sst_warn \
      "Unable to acquire the dpkg lock." \
      "Retrying in $n seconds." \
    ;
    sleep $n || sst_err_trap "$sst_last_command"
    n=$((n + (n < 10)))
  done

}; readonly -f sst_dpkg_loop
