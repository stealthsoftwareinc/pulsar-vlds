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

sst_ubuntu_install_raw() {

  # Bash >=4.2: declare -g -A sst_ubuntu_install_raw_seen
  # Bash >=4.2: declare -g    sst_ubuntu_install_raw_setup

  declare    apt_get
  declare    cmd
  declare    package
  declare    package_q
  declare -A packages
  declare    r
  declare    s
  declare    stderr_cache
  declare    x

  stderr_cache=$sst_root_tmpdir/$FUNCNAME.$BASHPID.stderr_cache
  readonly stderr_cache

  packages=()

  for package; do

    # Skip this package if we've already seen it.
    if [[ "${sst_ubuntu_install_raw_seen[$package]-}" ]]; then
      continue
    fi
    sst_ubuntu_install_raw_seen[$package]=1

    package_q=$(sst_smart_quote "$package") || sst_barf

    # Skip this package if it's already installed.
    cmd="dpkg-query -W -f '\${db:Status-Status}' -- $package_q"
    r=$(eval "$cmd" 2>"$stderr_cache") && s=0 || s=$?
    if [[ $s == 0 && "$r" == installed ]]; then
      continue
    fi
    if [[ $s != 0 && $s != 1 ]]; then
      cat <"$stderr_cache" >&2 || :
      sst_barf "Command failed with exit status $s: $cmd"
    fi

    packages[$package_q]=1

  done

  if ((${#packages[@]} == 0)); then
    return
  fi

  #
  # Note that DPkg::Lock::Timeout requires apt-get >=1.9.11, but it's
  # fine to set it in older versions too, as they just ignore it.
  #

  apt_get="apt-get"
  apt_get+=" -o DPkg::Lock::Timeout=-1"
  apt_get+=" -q"
  if [[ ! -t 0 ]]; then
    apt_get+=" -y"
    apt_get="DEBIAN_FRONTEND=noninteractive $apt_get"
  fi

  if [[ ! "${sst_ubuntu_install_raw_setup-}" ]]; then
    sst_ubuntu_install_raw_setup=1
    readonly sst_ubuntu_install_raw_setup
    x=$(sst_type -f -p sudo)
    if [[ "$x" ]]; then
      cmd="sudo $apt_get update"
    else
      cmd="su -c '$apt_get update && $apt_get install sudo'"
    fi
    sst_warn "Running command: ($cmd)."
    sst_dpkg_loop "$cmd >&2"
  fi

  unset 'packages[sudo]'

  readonly packages

  if ((${#packages[@]} == 0)); then
    return
  fi

  apt_get="sudo $apt_get"
  readonly apt_get

  cmd="$apt_get install ${!packages[@]}"
  sst_warn "Running command: ($cmd)."
  sst_dpkg_loop "$cmd >&2"

}; readonly -f sst_ubuntu_install_raw
