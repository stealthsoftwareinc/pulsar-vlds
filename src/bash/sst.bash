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

#-----------------------------------------------------------------------
# Guidelines
#-----------------------------------------------------------------------
#
# * Any functions defined directly in this file that should be included
#   in sst_barf stack traces should be named sst_prelude_*.
#

#-----------------------------------------------------------------------
# Bootstrapping
#-----------------------------------------------------------------------
#
# Bootstrap into the PATH-resolved bash. Unset SST_BASH_BOOTSTRAP so we
# don't trick other scripts into believing they've already bootstrapped.
#
# This section is written in portable shell to ensure it works properly
# in any shell.
#

case ${SST_BASH_BOOTSTRAP+x}y$# in
  y0) SST_BASH_BOOTSTRAP= exec bash - "$0" ;;
  y*) SST_BASH_BOOTSTRAP= exec bash - "$0" "$@" ;;
esac
unset SST_BASH_BOOTSTRAP

#-----------------------------------------------------------------------
# Locale
#-----------------------------------------------------------------------
#
# Use the C locale by default. This is the best approach, as most code
# is written with the C locale in mind, and other locales tend to break
# such code in strange, subtle ways. The locale affects the behavior of
# many fundamental programs, like awk, grep, sed, and the current shell
# instance itself. When the caller knows better, they can freely adjust
# LC_ALL and the other locale variables as they see fit.
#
# This section is written in portable shell to ensure it works properly
# in arbitrarily old versions of Bash.
#

case ${LC_ALL+x} in
  ?*)
    sst_old_LC_ALL=$LC_ALL
  ;;
esac
readonly sst_old_LC_ALL

LC_ALL=C
export LC_ALL

#-----------------------------------------------------------------------
# Outdated Bash detection
#-----------------------------------------------------------------------
#
# Check that we're running in a sufficiently new version of Bash.
#
# This section is written in portable shell to ensure it works properly
# in arbitrarily old versions of Bash.
#

case ${BASH_VERSION-} in
  4.[1-9]* | [5-9]* | [1-9][0-9]*)
    :
  ;;
  *)
    sst_x="$0: error: bash 4.1 or newer is required."
    sst_x=$sst_x' Your Bash version is too old'
    case ${BASH_VERSION+x} in
      ?*) sst_x=$sst_x' (Bash '$BASH_VERSION').' ;;
      '') sst_x=$sst_x' (the BASH_VERSION variable is not even set).' ;;
    esac
    if command -v sw_vers >/dev/null 2>&1; then
      sst_x=$sst_x' It looks like you'\''re on macOS, in which case the'
      sst_x=$sst_x' plain "bash" command may be mapped to the operating'
      sst_x=$sst_x' system copy of Bash (the /bin/bash file), which may'
      sst_x=$sst_x' be quite old.'
      sst_x=$sst_x' Please install a newer copy of Bash using Homebrew,'
      sst_x=$sst_x' MacPorts, or some other means.'
      sst_x=$sst_x' You can check which copy of Bash the plain "bash"'
      sst_x=$sst_x' command is mapped to by running "command -v bash",'
      sst_x=$sst_x' and you can check which version it is by running'
      sst_x=$sst_x' "bash --version".'
    fi
    printf '%s\n' "$sst_x" >&2
    exit 1
  ;;
esac

#-----------------------------------------------------------------------
# Global associative array declarations
#-----------------------------------------------------------------------
#
# We support Bash 4.1, which does not support declare -g, so ideally
# we'd like to use declare -A to declare any global associative arrays
# in any function file that needs them. However, our automatic function
# loading mechanism loads function files in function scope, not in the
# global scope, so these declarations wouldn't be in the global scope.
# As a workaround, we declare all global associative arrays here.
#

declare -A sst_ac_config_file_srcs
declare -A sst_ag_process_leaf_seen
declare -A sst_am_distribute_seen
declare -A sst_am_install_dirs
declare -A sst_am_targets
declare -A sst_am_var_const
declare -A sst_am_var_value
declare -A sst_am_var_value_files
declare -A sst_centos_install_raw_seen
declare -A sst_centos_install_utility_map
declare -A sst_cygwin_install_raw_seen
declare -A sst_cygwin_install_utility_map
declare -A sst_cygwin_install_utility_seen
declare -A sst_ubuntu_install_raw_seen
declare -A sst_ubuntu_install_utility_map
declare -A sst_utility_overrides
declare -A sst_utility_prefixes
declare -A sst_utility_programs
declare -A sst_utility_seen
declare -A sst_utility_suffixes

#-----------------------------------------------------------------------
# SST_PRELUDE_DEPTH
#-----------------------------------------------------------------------
#
# The number of scripts above us in the call stack that are also using
# the SST Bash library. This cannot be readonly because sst_exec needs
# to modify it.
#

SST_PRELUDE_DEPTH=$((${SST_PRELUDE_DEPTH:--1} + 1))
export SST_PRELUDE_DEPTH

#-----------------------------------------------------------------------
# Error handling
#-----------------------------------------------------------------------

set \
  -E \
  -T \
  -e \
  -o pipefail \
  -u \
|| exit $?

trap exit ERR

#-----------------------------------------------------------------------

#
# Ensure that Bash's POSIX compatibility mode is disabled. This mode has
# no purpose for us, as we're intentionally using Bash, not merely using
# Bash as a realization of the POSIX shell. Failing to ensure this mode
# is disabled can lead to inconvenient behavior, such as the ability to
# use "-" characters in function names being disabled.
#

set +o posix

shopt -s \
  dotglob \
  extglob \
  globstar \
  nullglob \
;

#-----------------------------------------------------------------------
# sst_libdir
#-----------------------------------------------------------------------

unset sst_libdir
if [[ "$BASH_SOURCE" != */* ]]; then
  sst_libdir=$PWD
elif [[ "${BASH_SOURCE:0:1}" == / ]]; then
  sst_libdir=${BASH_SOURCE%/*}/
elif [[ "${PWD: -1}" == / ]]; then
  sst_libdir=$PWD${BASH_SOURCE%/*}/
else
  sst_libdir=$PWD/${BASH_SOURCE%/*}/
fi
if [[ "${sst_libdir: -1}" == / ]]; then
  sst_libdir+=.
fi
readonly sst_libdir

#-----------------------------------------------------------------------
# Automatic function loading
#-----------------------------------------------------------------------

for sst_file in "$sst_libdir"/include/**/*.bash; do
  sst_name=${sst_file##*/}
  sst_name=${sst_name%.bash}
  sst_file=\'${sst_file//\'/\'\\\'\'}\'
  eval '
    '"$sst_name"'() {
      . '"$sst_file"'
      "$FUNCNAME" "$@"
    }
  '
done

unset sst_file
unset sst_name

#-----------------------------------------------------------------------
# SST_DEBUG
#-----------------------------------------------------------------------

if [[ "${SST_DEBUG=1}" != [01] ]]; then
  sst_x=$SST_DEBUG
  SST_DEBUG=1
  sst_x=$(sst_quote "$sst_x")
  sst_barf "Invalid SST_DEBUG value: $sst_x."
fi
readonly SST_DEBUG

SST_NDEBUG=$((!SST_DEBUG))
readonly SST_NDEBUG

#-----------------------------------------------------------------------
# Print the Docker version in CI jobs that have Docker
#-----------------------------------------------------------------------

if [[ "${GITLAB_CI-}" && $SST_PRELUDE_DEPTH == 0 ]]; then
  sst_x=$(sst_type -f -p docker)
  if [[ "$sst_x" ]]; then
    command docker --version >&2
  fi
fi

#-----------------------------------------------------------------------
# The DEBUG trap
#-----------------------------------------------------------------------

unset sst_last_command
unset sst_next_command

declare sst_last_command
declare sst_next_command

trap '
  sst_last_command=${sst_next_command-}
  sst_next_command=$BASH_COMMAND
' DEBUG

#-----------------------------------------------------------------------
# The ERR trap
#-----------------------------------------------------------------------
#
# The sst_err_trap function must live inside this file, not in its own
# file where it would be subject to automatic function loading. If it
# lived in its own file, it wouldn't be able to collect $? correctly
# from the previous command, as the automatic function loader would
# destroy it.
#

unset sst_bash_command
unset sst_err_trap_status

declare sst_bash_command
declare sst_err_trap_status

sst_err_trap() {

  declare entry_status=$?
  readonly entry_status

  # Bash >=4.2: declare -g sst_err_trap_status

  declare command
  declare status
  declare status_regex
  declare stderr
  declare x

  sst_expect_argument_count $# 1-3 || sst_err_trap "$sst_last_command"

  command=$1
  readonly command

  status=${2-$entry_status}
  readonly status

  if (($# == 3)); then
    stderr=$3
    readonly stderr
    printf '%s\n' "$stderr" >&2 || :
  fi

  status_regex='^(0|[1-9][0-9]{0,2})$'
  readonly status_regex

  sst_err_trap_status=1
  if [[ "$status" =~ $status_regex ]] && ((status <= 255)); then
    sst_err_trap_status=$status
  else
    x=$(sst_smart_quote "$status") || x=$status
    sst_warn "$FUNCNAME: Ignoring invalid <status>: $x" || :
  fi
  readonly sst_err_trap_status

  sst_barf \
    "Command failed with exit status $sst_err_trap_status: $command" \
  ;

}; readonly -f sst_err_trap

trap 'sst_err_trap "${sst_bash_command-$BASH_COMMAND}" $?' ERR

#-----------------------------------------------------------------------
# Automatic utility installation
#-----------------------------------------------------------------------

sst_automatic_utilities=(

  awk
  c89
  c99
  cat
  cc
  docker-compose
  gawk
  git
  gpg1
  gpg2
  jq
  make
  mv
  sed
  sort
  ssh
  ssh-keygen
  sshpass
  sudo
  tar
  wget

)
readonly sst_automatic_utilities

for sst_utility in "${sst_automatic_utilities[@]}"; do
  eval '
    '"$sst_utility"'() {
      local i
      for ((i = 1; i < ${#FUNCNAME[@]}; ++i)); do
        if [[ "${FUNCNAME[i]}" == sst_install_utility ]]; then
          command "$FUNCNAME" "$@"
          return
        fi
      done
      sst_install_utility "$FUNCNAME" || sst_barf
      "$FUNCNAME" "$@"
    }
  '
done

#-----------------------------------------------------------------------
# sst_rundir
#-----------------------------------------------------------------------
#
# Set sst_rundir to an absolute path to the directory from which the
# script was run.
#

readonly sst_rundir="$PWD"

# DEPRECATED
readonly rundir="$sst_rundir"

#-----------------------------------------------------------------------

#
# If we're running in a disposable GitLab CI environment and
# $CI_BUILDS_DIR is writable, set TMPDIR to $CI_BUILDS_DIR/tmp. This
# increases the probability of being able to mount TMPDIR-based paths
# into docker containers, as the job itself may be running in a docker
# container with both $CI_BUILDS_DIR and the host docker daemon socket
# identity-mounted into the container.
#

if [[ x \
  && "${CI_DISPOSABLE_ENVIRONMENT+x}" \
  && "${CI_BUILDS_DIR+x}" \
  && -w "$CI_BUILDS_DIR" \
]]; then
  if [[ "$CI_BUILDS_DIR" == [!/]* ]]; then
    TMPDIR=$PWD/$CI_BUILDS_DIR/tmp
  else
    TMPDIR=$CI_BUILDS_DIR/tmp
  fi
  export TMPDIR
  mkdir -p "$TMPDIR"
fi

#
# We want to provide the calling script with an absolute path to an
# empty directory that it can use for temporary files. However, this
# prelude and other preludes that wrap this prelude also need to use
# temporary files, so name collisions are a problem. To fix this, each
# prelude uses its temporary directory as needed, and before returning
# to the calling script (which may be a wrapping prelude), creates an
# empty temporary subdirectory for the calling script to use.
#

if sst_initial_tmpdir=$(mktemp -d 2>/dev/null); then
  if [[ "${sst_initial_tmpdir:0:1}" != / ]]; then
    if [[ "${PWD: -1:1}" == / ]]; then
      sst_initial_tmpdir=$PWD$sst_initial_tmpdir
    else
      sst_initial_tmpdir=$PWD/$sst_initial_tmpdir
    fi
  fi
  if [[ "${sst_initial_tmpdir: -1:1}" != / ]]; then
    sst_initial_tmpdir+=/
  fi
else
  sst_initial_tmpdir=${TMPDIR:-/tmp}
  if [[ "${sst_initial_tmpdir:0:1}" != / ]]; then
    if [[ "${PWD: -1:1}" == / ]]; then
      sst_initial_tmpdir=$PWD$sst_initial_tmpdir
    else
      sst_initial_tmpdir=$PWD/$sst_initial_tmpdir
    fi
  fi
  if [[ "${sst_initial_tmpdir: -1:1}" != / ]]; then
    sst_initial_tmpdir+=/
  fi
  mkdir -p "$sst_initial_tmpdir"
  sst_n=10
  while ((sst_n-- > 0)); do
    sst_d=000000000$(od -A n -N 4 -t u4 /dev/urandom 2>/dev/null) && {
      sst_d=${sst_d//[[:blank:]]/}
      sst_d=${sst_d: -10}
    } || {
      sst_x=0000$RANDOM
      sst_y=0000$RANDOM
      sst_d=${sst_x: -5}${sst_y: -5}
    }
    sst_d=${sst_initial_tmpdir}tmp.$sst_d/
    mkdir "$sst_d" || continue
    sst_initial_tmpdir=$sst_d
    break
  done
  if ((sst_n < 0)); then
    sst_barf "Unable to create a temporary directory."
  fi
fi
chmod 700 "$sst_initial_tmpdir"
readonly sst_initial_tmpdir

sst_root_tmpdir=${sst_initial_tmpdir}x
readonly sst_root_tmpdir

sst_root_stmpdir=${sst_initial_tmpdir}s
readonly sst_root_stmpdir

sst_tmpdir=$sst_root_tmpdir

sst_stmpdir=$sst_root_stmpdir

mkdir "$sst_tmpdir" "$sst_stmpdir"

#-----------------------------------------------------------------------
# EXIT trap
#-----------------------------------------------------------------------

sst_exit_trap() {

  sst_trap_entry_status=$?

  # Bash >=4.2: declare -g    sst_root_stmpdir
  # Bash >=4.2: declare -g    sst_root_tmpdir
  # Bash >=4.2: declare -g    sst_trap_entry_status

  declare    n
  declare    x

  #---------------------------------------------------------------------
  # Postmortem job container pushing
  #---------------------------------------------------------------------
  #
  # The if condition here should be kept in sync with the one in
  # sst_push_postmortem_job_container.
  #
  # The reason for repeating it here is for performance. If we didn't,
  # we'd incur a load and call of sst_push_postmortem_job_container at
  # every exit, not just at every exit in CI.
  #

  if [[ x \
    && ! "${GITLAB_CI-}" \
  ]]; then
    :
  else
    sst_push_postmortem_job_container || :
  fi

  #---------------------------------------------------------------------

  for x in "$sst_root_stmpdir"/**/*; do
    if [[ -f "$x" ]] && n=$(du -k "$x" 2>/dev/null); then
      n=${n%%[!0-9]*}
      n=$((n / 64 + (n % 64 > 0)))
      dd if=/dev/zero of="$x" bs=64k count=$n &>/dev/null || :
    fi
  done

  rm -f -r "$sst_initial_tmpdir" || :

  #---------------------------------------------------------------------

}; readonly -f sst_exit_trap

trap 'sst_exit_trap || :;' EXIT

#-----------------------------------------------------------------------
# sst_is0atty
#-----------------------------------------------------------------------

if test -t 0; then
  sst_is0atty=1
else
  sst_is0atty=
fi
readonly sst_is0atty

#-----------------------------------------------------------------------

#
# This section is DEPRECATED. Archivist runners will eventually be
# completely replaced by decentralized keys.
#

#
# Determine whether we're running on an archivist runner.
#

if test -f /archivist.gitlab-username; then
  archivist=true
else
  archivist=false
fi
readonly archivist

case $archivist in
  true)
    u=$(cat /archivist.gitlab-username)
    docker login \
      --username "$u" \
      --password-stdin \
      registry.stealthsoftwareinc.com \
      </archivist.gitlab-password \
    ;
    unset u
  ;;
esac

#-----------------------------------------------------------------------

#
# Make sure "apt-get -y" is fully noninteractive when we're running
# noninteractively on Debian. See "man 7 debconf" (after running
# "apt-get install debconf-doc") or view it online at
# <https://manpages.debian.org/debconf.7>.
#

if ((!sst_is0atty)); then
  export DEBIAN_FRONTEND=noninteractive
fi

#
# Log in to the GitLab Container Registry, if possible.
#

if [[ "${CI_REGISTRY+x}" != "" ]]; then
  if command -v docker >/dev/null; then
    docker login \
      --username "$CI_REGISTRY_USER" \
      --password-stdin \
      "$CI_REGISTRY" \
      <<<"$CI_REGISTRY_PASSWORD" \
      >/dev/null \
    ;
  fi
fi

#-----------------------------------------------------------------------
# SSH key
#-----------------------------------------------------------------------
#
# Set up our SSH credentials as specified by the SSH_SECRET_KEY and
# SSH_PASSPHRASE environment variables.
#
# If SSH_SECRET_KEY is unset or empty, no setup is performed. Otherwise,
# SSH_SECRET_KEY should be either the text of a secret key or a path to
# a secret key file, and SSH_PASSPHRASE should be the passphrase of the
# key. If the key has no passphrase, SSH_PASSPHRASE should be unset or
# empty.
#
# SSH_SECRET_KEY and SSH_PASSPHRASE can also be overridden by setting
# SSH_SECRET_KEY_VAR and SSH_PASSPHRASE_VAR to the names of different
# environment variables to use. For example, if your secret key is in
# MY_KEY, you can set SSH_SECRET_KEY_VAR=MY_KEY to use it. It may be
# unclear why you'd want to do this instead of just directly setting
# SSH_SECRET_KEY=$MY_KEY. Either approach will work, but the indirect
# approach is sometimes convenient for certain environments that may
# have challenging overriding behavior, such as GitLab CI.
#

if [[ "${SSH_SECRET_KEY_VAR-}" ]]; then
  sst_expect_basic_identifier "$SSH_SECRET_KEY_VAR"
  eval '
    if [[ "${'$SSH_SECRET_KEY_VAR'-}" ]]; then
      SSH_SECRET_KEY=$'$SSH_SECRET_KEY_VAR'
    fi
  '
fi

if [[ "${SSH_PASSPHRASE_VAR-}" ]]; then
  sst_expect_basic_identifier "$SSH_PASSPHRASE_VAR"
  eval '
    if [[ "${'$SSH_PASSPHRASE_VAR'-}" ]]; then
      SSH_PASSPHRASE=$'$SSH_PASSPHRASE_VAR'
    fi
  '
fi

if [[ "${SSH_SECRET_KEY-}" == "" ]]; then

  if [[ "${SSH_PASSPHRASE-}" != "" ]]; then
    sst_warn 'SSH_PASSPHRASE is set without SSH_SECRET_KEY'
  fi

else

  cat <<'EOF' >"$sst_tmpdir"/ssh_config
IdentitiesOnly yes
PasswordAuthentication no
PreferredAuthentications publickey
StrictHostKeyChecking no
UserKnownHostsFile /dev/null
EOF
  chmod 400 "$sst_tmpdir"/ssh_config

  if [[ "$SSH_SECRET_KEY" == ----* ]]; then
    cat <<<"$SSH_SECRET_KEY" >"$sst_tmpdir"/ssh_secret_key
  else
    cat <"$SSH_SECRET_KEY" >"$sst_tmpdir"/ssh_secret_key
  fi
  chmod 400 "$sst_tmpdir"/ssh_secret_key

  if [[ "${SSH_PASSPHRASE-}" == "" ]]; then

    sst_install_utility ssh ssh-keygen

    if ! ssh-keygen -y -f "$sst_tmpdir"/ssh_secret_key >/dev/null; then
      sst_barf 'invalid SSH_SECRET_KEY'
    fi

  else

    cat <<<"$SSH_PASSPHRASE" >"$sst_tmpdir"/ssh_passphrase
    chmod 400 "$sst_tmpdir"/ssh_passphrase

    sst_install_utility ssh ssh-keygen sshpass

    x=$(sst_quote "$sst_tmpdir"/ssh_passphrase)
    sst_utility_suffixes[sshpass]+=' -f '$x
    sst_utility_suffixes[sshpass]+=' -P assphrase'

    if ! sshpass \
         ssh-keygen -y -f "$sst_tmpdir"/ssh_secret_key >/dev/null; then
      sst_barf 'invalid SSH_SECRET_KEY or SSH_PASSPHRASE'
    fi

  fi

  x1=$(sst_quote "$sst_tmpdir"/ssh_config)
  x2=$(sst_quote "$sst_tmpdir"/ssh_secret_key)
  sst_utility_suffixes[ssh]+=' -F '$x1
  sst_utility_suffixes[ssh]+=' -o IdentityFile='$x2

  if [[ "${SSH_PASSPHRASE-}" != "" ]]; then
    sst_utility_suffixes[ssh]=" \
      ${sst_utility_suffixes[sshpass]} \
      ${sst_utility_programs[ssh]} \
      ${sst_utility_suffixes[ssh]} \
    "
    sst_utility_programs[ssh]=${sst_utility_programs[sshpass]}
    sst_utility_prefixes[ssh]+=${sst_utility_prefixes[sshpass]}
  fi

  #
  # Set and export GIT_SSH_COMMAND instead of prepending it to
  # ${sst_utility_prefixes[git]} so that git commands run by other
  # scripts will also use our SSH credentials. Note that git does not
  # necessarily need to be installed here, as we're simply setting an
  # environment variable that git will use if it is in fact installed.
  #

  export GIT_SSH_COMMAND=" \
    ${sst_utility_prefixes[ssh]} \
    command \
    ${sst_utility_programs[ssh]} \
    ${sst_utility_suffixes[ssh]} \
  "

fi

#-----------------------------------------------------------------------
# GPG key
#-----------------------------------------------------------------------
#
# Set up our GPG credentials as specified by the GPG_SECRET_KEY and
# GPG_PASSPHRASE environment variables.
#
# If GPG_SECRET_KEY is unset or empty, no setup is performed. Otherwise,
# GPG_SECRET_KEY should be either the text of a secret key or a path to
# a secret key file, and GPG_PASSPHRASE should be the passphrase of the
# key. If the key has no passphrase, GPG_PASSPHRASE should be unset or
# empty.
#
# GPG_SECRET_KEY and GPG_PASSPHRASE can also be overridden by setting
# GPG_SECRET_KEY_VAR and GPG_PASSPHRASE_VAR to the names of different
# environment variables to use. The behavior and rationale for these
# overrides are the same as for the analogous SSH_* overrides.
#

if [[ "${GPG_SECRET_KEY_VAR-}" ]]; then
  sst_expect_basic_identifier "$GPG_SECRET_KEY_VAR"
  eval '
    if [[ "${'$GPG_SECRET_KEY_VAR'-}" ]]; then
      GPG_SECRET_KEY=$'$GPG_SECRET_KEY_VAR'
    fi
  '
fi

if [[ "${GPG_PASSPHRASE_VAR-}" ]]; then
  sst_expect_basic_identifier "$GPG_PASSPHRASE_VAR"
  eval '
    if [[ "${'$GPG_PASSPHRASE_VAR'-}" ]]; then
      GPG_PASSPHRASE=$'$GPG_PASSPHRASE_VAR'
    fi
  '
fi

if [[ "${GPG_SECRET_KEY-}" == "" ]]; then

  if [[ "${GPG_PASSPHRASE-}" != "" ]]; then
    sst_warn 'GPG_PASSPHRASE is set without GPG_SECRET_KEY'
  fi

else

  sst_install_utility git gpg2

  mkdir "$sst_tmpdir"/gpg_home
  chmod 700 "$sst_tmpdir"/gpg_home

  x=$(sst_quote "$sst_tmpdir"/gpg_home)
  sst_utility_suffixes[gpg2]+=' --batch'
  sst_utility_suffixes[gpg2]+=' --homedir '$x
  sst_utility_suffixes[gpg2]+=' --no-tty'
  sst_utility_suffixes[gpg2]+=' --quiet'

  #
  # The --pinentry-mode option was added in GnuPG 2.1, so we can't use
  # it in GnuPG 2.0.x. The exact commit in the GnuPG Git repository is
  # b786f0e12b93d8d61eea18c934f5731fe86402d3.
  #

  x=$(gpg2 --version | sed -n '1s/^[^0-9]*//p')
  if [[ "$x" != 2.0* ]]; then
    sst_utility_suffixes[gpg2]+=' --pinentry-mode loopback'
  fi

  if [[ "$GPG_SECRET_KEY" == ----* ]]; then
    cat <<<"$GPG_SECRET_KEY" >"$sst_tmpdir"/gpg_secret_key
  else
    cat <"$GPG_SECRET_KEY" >"$sst_tmpdir"/gpg_secret_key
  fi
  chmod 400 "$sst_tmpdir"/gpg_secret_key
  gpg2 --import "$sst_tmpdir"/gpg_secret_key

  if [[ "${GPG_PASSPHRASE-}" != "" ]]; then
    cat <<<"$GPG_PASSPHRASE" >"$sst_tmpdir"/gpg_passphrase
    chmod 400 "$sst_tmpdir"/gpg_passphrase
    x=$(sst_quote "$sst_tmpdir"/gpg_passphrase)
    sst_utility_suffixes[gpg2]+=' --passphrase-file='$x
  fi

  cat <<EOF >"$sst_tmpdir"/gpg_program
#! /bin/sh -
case \$# in
  0) ${sst_utility_prefixes[gpg2]} \
     ${sst_utility_programs[gpg2]} \
     ${sst_utility_suffixes[gpg2]}      ; exit \$? ;;
  *) ${sst_utility_prefixes[gpg2]} \
     ${sst_utility_programs[gpg2]} \
     ${sst_utility_suffixes[gpg2]} "\$@"; exit \$? ;;
esac
EOF
  chmod +x "$sst_tmpdir"/gpg_program
  x=$(sst_quote "$sst_tmpdir"/gpg_program)
  sst_utility_suffixes[git]+=' -c gpg.program='$x

  r='[0-9A-Fa-f]'
  r="[ 	]*$r$r$r$r"
  r="$r$r$r$r$r$r$r$r$r$r"
  x=$(gpg2 --fingerprint | sed -n '
    /'"$r"'/ {
      s/.*\('"$r"'\).*/\1/
      s/[ 	]//g
      p
      q
    }
  ')
  sst_utility_suffixes[git]+=' -c user.signingKey=0x'$x
  sst_utility_suffixes[git]+=' -c commit.gpgSign=true'
  sst_utility_suffixes[git]+=' -c tag.gpgSign=true'

fi

#
# Set up our Git name and email.
#
# These variables can be overridden by using the standard GIT_AUTHOR_*
# and GIT_COMMITTER_* environment variables. For more information, see
# "man git-commit" and "man git-commit-tree", or view them online at
# <https://git-scm.com/docs/git-commit> and
# <https://git-scm.com/docs/git-commit-tree>.
#
# GIT_AUTHOR_* and GIT_COMMITTER_* can be further overridden by setting
# GIT_AUTHOR_*_VAR and GIT_COMMITTER_*_VAR to the names of different
# environment variables to use. The behavior and rationale for these
# overrides are the same as for the analogous SSH_* overrides.
#

for x in \
  GIT_AUTHOR_DATE \
  GIT_AUTHOR_EMAIL \
  GIT_AUTHOR_NAME \
  GIT_COMMITTER_DATE \
  GIT_COMMITTER_EMAIL \
  GIT_COMMITTER_NAME \
; do

  #
  # Override $x with ${x}_VAR if it's set.
  #

  eval y=\${${x}_VAR+x}
  if [[ "$y" != "" ]]; then
    eval y=\${${x}_VAR}
    sst_expect_basic_identifier "$y"
    eval $x=\$$y
  fi

  #
  # Ensure that $x is exported if it's set.
  #

  eval y=\${$x+x}
  if [[ "$y" != "" ]]; then
    export $x
  fi

done

#
# If we're in a GitLab CI job, fill in various unset GIT_* environment
# variables using the job information.
#

if [[ "${CI_JOB_URL-}" != "" ]]; then
  if [[ "${GIT_AUTHOR_EMAIL+x}" == "" ]]; then
    export GIT_AUTHOR_EMAIL="$GITLAB_USER_EMAIL"
  fi
  if [[ "${GIT_AUTHOR_NAME+x}" == "" ]]; then
    export GIT_AUTHOR_NAME="$GITLAB_USER_NAME"
  fi
  if [[ "${GIT_COMMITTER_EMAIL+x}" == "" ]]; then
    export GIT_COMMITTER_EMAIL=""
  fi
  if [[ "${GIT_COMMITTER_NAME+x}" == "" ]]; then
    export GIT_COMMITTER_NAME="$CI_JOB_URL"
  fi
fi

#-----------------------------------------------------------------------

sst_tmpdir+=/x
readonly sst_tmpdir

sst_stmpdir+=/x
readonly sst_stmpdir

mkdir "$sst_tmpdir" "$sst_stmpdir"

# DEPRECATED
readonly tmpdir="$sst_tmpdir"

#-----------------------------------------------------------------------
