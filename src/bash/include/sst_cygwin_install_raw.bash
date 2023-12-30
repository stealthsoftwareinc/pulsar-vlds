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

sst_cygwin_install_raw() {

  # Bash >=4.2: declare -g -A sst_cygwin_install_raw_seen

  declare -a missing
  declare    package
  declare    x

  missing=()

  for package; do

    # Skip this package if we've already seen it.
    if [[ "${sst_cygwin_install_raw_seen[$package]-}" ]]; then
      continue
    fi
    sst_cygwin_install_raw_seen[$package]=x

    # Skip this package if it's already installed.
    x=$(
      cygcheck -c "$package" | sed -n '/OK$/ p'
    ) || sst_err_trap "$sst_last_command"
    if [[ "$x" ]]; then
      continue
    fi

    missing+=("$package")

  done

  readonly missing

  if ((${#missing[@]} > 0)); then
    x=$(
      sst_human_list : "${missing[@]}"
    ) || sst_err_trap "$sst_last_command"
    if ((${#missing[@]} > 1)); then
      x="Missing Cygwin packages: $x."
      x="$x Please install them using the Cygwin setup program."
    else
      x="Missing Cygwin package: $x."
      x="$x Please install it using the Cygwin setup program."
    fi
    sst_barf "$x"
  fi

}; readonly -f sst_cygwin_install_raw
