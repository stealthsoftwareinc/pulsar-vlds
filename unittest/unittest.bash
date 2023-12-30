#! /bin/sh -
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
# This script uses the SST Bash library to bootstrap from /bin/sh into
# bash. See the Bash library > Bootstrapping section of the SST manual
# for more information.
#

#-----------------------------------------------------------------------
# Load the prelude
#-----------------------------------------------------------------------

case $0 in /*) x=$0 ;; *) x=./$0 ;; esac
r='\(.*/\)'
x=`expr "$x" : "$r"`. || exit $?
set -e || exit $?
. "$x/../src/bash/sst.bash"

#-----------------------------------------------------------------------

if command -v cygpath >/dev/null; then
  system=cygwin
else
  system=linux
fi
readonly system

engines=()
engines+=(mysql)
engines+=(sqlite)
engines+=(sqlserver)
readonly engines

if [[ "${ENGINE:+x}" ]]; then
  while :; do
    for x in "${engines[@]}"; do
      if [[ "$ENGINE" == "$x" ]]; then
        break 2
      fi
    done
    sst_barf "unknown ENGINE: $ENGINE"
  done
else
  ENGINE=mysql
fi
readonly ENGINE

#-----------------------------------------------------------------------

if [[ "${STAGE-}" != '' ]]; then
  STAGE=$(sst_dot_slash "$STAGE" | sst_csf)
  sst_csf STAGE
  STAGE=$( { cd "$STAGE"; pwd; } | sst_csf)
  sst_csf STAGE
  readonly STAGE
  if [[ "$system" == cygwin ]]; then
    STAGE_NATIVE=$(cygpath -w -l "$STAGE")
  else
    STAGE_NATIVE=$STAGE
  fi
  readonly STAGE_NATIVE
fi

#-----------------------------------------------------------------------
# Construct the pulsar-vlds-server command
#-----------------------------------------------------------------------

if [[ "${STAGE-}" != '' ]]; then
  pulsar_vlds_server=
  pulsar_vlds_server+=" JAVAFLAGS='"
  pulsar_vlds_server+='   -Dfile.encoding=UTF-8'
  pulsar_vlds_server+=" '"
  pulsar_vlds_server+=' CLASSPATH="$STAGE_NATIVE"/usr/local/share/java/\*'
  pulsar_vlds_server+=' "$STAGE"/usr/local/bin/pulsar-vlds-server'
  pulsar_vlds_server+=' --prefix="$STAGE_NATIVE"'
  pulsar_vlds_server+=' --home="$STAGE_NATIVE/home"'
else
  pulsar_vlds_server='pulsar-vlds-server'
fi
readonly pulsar_vlds_server

#-----------------------------------------------------------------------

db_start_cygwin_sqlserver() {
  local x
  for x in *.sql; do
    '/cygdrive/c/Program Files/Microsoft SQL Server/Client SDK/ODBC/170/Tools/Binn/SQLCMD.EXE' -i "$x"
  done
}

db_stop_cygwin_sqlserver() {
  :
}

#-----------------------------------------------------------------------

db_start_linux_mysql() {

  sst_ihs <<<'
    FROM mysql
    RUN printf '[mysqld]\nsecure_file_priv=\n' >>/etc/mysql/my.cnf
    COPY *.sql /docker-entrypoint-initdb.d/
    ENV MYSQL_ROOT_PASSWORD=root
  ' | docker build -f - --tag=pulsar-vlds-unittest .

  local port=11111

  docker rm -f pulsar-vlds-unittest &>/dev/null || :

  docker run --name=pulsar-vlds-unittest --publish=127.0.0.1:$port:3306/tcp -e MYSQL_ROOT_PASSWORD=root -d pulsar-vlds-unittest

  #wait for mysql server to be running
  local ctrlc=0
  trap ctrlc=1 SIGINT
  while ! docker run -it --rm --network=host mysql mysql --host=127.0.0.1 --port=$port -uroot -proot -e exit >/dev/null; do
    sleep 1
    case $ctrlc in 1) exit 1 ;; esac
  done
  trap - SIGINT

  echo "MySQL Server for pulsar-vlds-unittest is running on port $port"
}

db_stop_linux_mysql() {
  docker rm -f pulsar-vlds-unittest
}

#-----------------------------------------------------------------------
# linux sqlite
#-----------------------------------------------------------------------

function db_start_linux_sqlite {

  local x

  rm -f db.sqlite
  for x in *.sql; do
    sqlite3 db.sqlite <"$x"
  done

}; readonly -f db_start_linux_sqlite

function db_stop_linux_sqlite {

  :

}; readonly -f db_stop_linux_sqlite

#-----------------------------------------------------------------------

for f in db_{start,stop}; do
  eval '
    '$f'() {
      local f=${FUNCNAME}_${system}_${ENGINE}
      if [[ "$(type -t $f)" == function ]]; then
        $f "$@"
      else
        echo $f is missing >&2
        exit 1
      fi
    }
  '
done

#-----------------------------------------------------------------------

trap '
  s=$?
  if command -v docker >/dev/null; then
    docker rm -f pulsar-vlds-unittest &>/dev/null || :
  fi
  jobs -p | xargs -r kill || :
  exit $s
' EXIT

num_tests=0
num_success=0
num_fail=0
exit_status=0

for i; do

  sst_pushd $i

  #---------------------------------------------------------------------

  #
  # For each group of $x.sql.* files, construct $x.sql as the
  # concatenation of all $x.sql?* files in lexicographic order except
  # for those of the form *.$e for engine names $e other than $ENGINE.
  #

  unset xs
  declare -A xs
  for x in *.sql.*; do
    xs[${x%%.sql.*}]=x
  done
  for x in "${!xs[@]}"; do
    first=1
    for y in "$x".sql.*; do
      if ((first)); then
        >"$x".sql
        first=0
      fi
      for e in "${engines[@]}"; do
        if [[ "$e" != "$ENGINE" && "$y" == *."$e" ]]; then
          continue 2
        fi
      done
      cat ./"$y" >>"$x".sql
    done
  done
  unset xs

  #---------------------------------------------------------------------
  #
  # For each party x, apply the following config files in order, but
  # excluding any whose names match *.$e for any database engine other
  # than $ENGINE:
  #
  #    1. common.cfg* in lexicographic order.
  #    2. $x.cfg* in lexicographic order.
  #

  for x in db1 db2 ph; do
    eval "${x}_configs=()"
    for y in common.cfg* "$x".cfg*; do
      for e in "${engines[@]}"; do
        if [[ "$e" != "$ENGINE" && "$y" == *."$e" ]]; then
          continue 2
        fi
      done
      eval "${x}_configs+=(--config \"\$y\")"
    done
  done

  #---------------------------------------------------------------------

  db_start

  #---------------------------------------------------------------------

  pids=()

  eval " $pulsar_vlds_server ${db1_configs[@]} </dev/null &"
  pids+=($!)

  eval " $pulsar_vlds_server ${db2_configs[@]} </dev/null &"
  pids+=($!)

  eval " $pulsar_vlds_server ${ph_configs[@]} </dev/null &"
  pids+=($!)

  sleep 6
  for j in *.rest; do
    url=http://127.0.0.1:8099$(cat $j)
    bn=$(echo $j | sed 's/\.rest$//')
    curl -sS "$url" >$bn.ans2
    jq -S '.data |= sort' $bn.ans >$bn.want
    jq -S 'del(.queryID)|.data |= sort' $bn.ans2 >$bn.have
    eq=$(jq -s '.[0]==.[1]' $bn.want $bn.have)
    case $eq in
      true)
        echo pass: $i/$j
        num_success=$(($num_success + 1))
      ;;
      false)
        echo fail: $i/$j
        git diff --no-index $bn.want $bn.have || :
        num_fail=$(($num_fail + 1))
        exit_status=1
      ;;
    esac
    num_tests=$(($num_tests + 1))
  done

  for pid in "${pids[@]}"; do
    kill $pid
  done
  for pid in "${pids[@]}"; do
    wait $pid || :
  done

  sst_popd

  db_stop

done

echo
printf 'total:  %3d\n' $num_tests
printf 'passed: %3d\n' $num_success
printf 'failed: %3d\n' $num_fail

exit $exit_status
