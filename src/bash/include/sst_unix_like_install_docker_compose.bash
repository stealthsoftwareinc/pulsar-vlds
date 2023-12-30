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

sst_unix_like_install_docker_compose() {

  # Bash >=4.2: declare -g sst_distro
  # Bash >=4.2: declare -g -A sst_utility_prefixes
  # Bash >=4.2: declare -g -A sst_utility_programs
  # Bash >=4.2: declare -g -A sst_utility_suffixes

  declare already_installed
  declare bin
  declare tmp
  declare tmp_q
  declare uname_m
  declare uname_s
  declare url

  already_installed=$(sst_type -f -p docker-compose) || sst_barf
  readonly already_installed

  if [[ ! "$already_installed" ]]; then

    sst_get_distro || sst_barf

    sst_${sst_distro}_install_raw "$@" || sst_barf

    uname_s=$(uname -s | tr A-Z a-z) || sst_barf
    readonly uname_s

    uname_m=$(uname -m | tr A-Z a-z) || sst_barf
    readonly uname_m

    url='https://api.github.com/repos/docker/compose/releases'
    url+='?per_page=100'
    url=$(
      curl -s -S -L --connect-timeout 10 -- "$url" | jq -r \
        --arg uname_s "$uname_s" \
        --arg uname_m "$uname_m" \
        '
          def barf($m): .
          | error("No download for docker-compose found. " + $m)
          ;
          .
          | "(0|[1-9][0-9]*)" as $n
          | "^v?\($n)\\.\($n)\\.\($n)$" as $tag
          | (now - 7 * 24 * 60 * 60) as $date
          | "docker-compose-\($uname_s)-\($uname_m)" as $asset
          | map(select(.tag_name | test($tag; "")))
          | if length == 0 then .
            | barf("No candidate .tag_name value matches /" + $tag
                   + "/.")
            else .
            end
          | map(select(.published_at | fromdate < $date))
          | if length == 0 then .
            | barf("No candidate .published_at value is older than "
                   + ($date | todate) + ".")
            else .
            end
          | sort_by(.published_at)
          | .[-1].assets
          | map(select(.name == $asset))
          | if length == 0 then .
            | barf("No candidate .name value matches "
                   + ($asset | tojson) + ".")
            else .
            end
          | .[0].browser_download_url
        ' \
      | sst_csf
    ) || sst_barf
    sst_csf url || sst_barf
    readonly url

    tmp=$sst_root_tmpdir/docker-compose
    readonly tmp

    tmp_q=$(sst_smart_quote "$tmp")
    readonly tmp_q

    curl -s -S -L --connect-timeout 10 -- "$url" >"$tmp" || sst_barf

    bin=/usr/local/bin
    readonly bin

    sst_echo_eval "sudo mkdir -p $bin" >&2 || sst_barf
    sst_echo_eval "sudo cp $tmp_q $bin" >&2 || sst_barf
    sst_echo_eval "sudo chmod a+x $bin/docker-compose" >&2 || sst_barf

  fi

  sst_utility_prefixes[docker-compose]=
  sst_utility_programs[docker-compose]=docker-compose
  sst_utility_suffixes[docker-compose]=

}; readonly -f sst_unix_like_install_docker_compose
