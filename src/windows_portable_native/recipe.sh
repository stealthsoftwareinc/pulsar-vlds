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

eval AM_V_P='${AM_V_P:?}'
readonly AM_V_P

eval NATIVE_IMAGE='${NATIVE_IMAGE:?}'
readonly NATIVE_IMAGE

eval SED='${SED:?}'
readonly SED

eval TSUF=${TSUF:?}
readonly TSUF

eval UNZIP='${UNZIP:?}'

eval VCVARS='${VCVARS:?}'
readonly VCVARS

eval d=${d:?}
readonly d

eval dst=${dst:?}
readonly dst

eval src=${src:?}
readonly src

tmp=${dst?}${TSUF?}
readonly tmp

mkdir ${tmp?} || exit $?

cp ${src?} ${tmp?}/x.zip || exit $?

(
  cd ${tmp?} || exit $?
  UNZIP= eval " ${UNZIP?}"' -qq x.zip' || exit $?
) || exit $?

rm ${tmp?}/x.zip || exit $?

mv -f ${tmp?}/* ${tmp?}/x || exit $?

cmd='CMD "/C"'

eval set x "${VCVARS?}"
shift
case ${1?} in */*)
  vcvars_1=${1?}
;; *)
  vcvars_1=`command -v "${1?}"` || exit $?
esac
vcvars_1=`cygpath -a -l -w "${vcvars_1?}"` || exit $?
readonly vcvars_1
cmd=${cmd?}' "${vcvars_1?}"'
i=1
until shift && (exit ${1+1}0); do
  i=`expr ${i?} + 1` || exit $?
  eval vcvars_${i?}='${1?}'
  readonly vcvars_${i?}
  cmd=${cmd?}' "${vcvars_'${i?}'?}"'
done

cmd=${cmd?}' "&&"'

eval set x "${NATIVE_IMAGE?}"
shift
case ${1?} in */*)
  native_image_1=${1?}
;; *)
  native_image_1=`command -v "${1?}"` || exit $?
esac
native_image_1=`cygpath -a -l -w "${native_image_1?}"` || exit $?
readonly native_image_1
cmd=${cmd?}' "${native_image_1?}"'
i=1
until shift && (exit ${1+1}0); do
  i=`expr ${i?} + 1` || exit $?
  eval native_image_${i?}='${1?}'
  readonly native_image_${i?}
  cmd=${cmd?}' "${native_image_'${i?}'?}"'
done

readonly cmd

x='/^ *\/\//d'
for y in \
  jni-config.json \
  predefined-classes-config.json \
  proxy-config.json \
  reflect-config.json \
  resource-config.json \
  serialization-config.json \
; do
  eval " ${SED?}"' \
    "${x?}" \
    <${d?}${y?} \
    >${tmp?}/x/usr/local/share/java/${y?} \
  ' || exit $?
done

(

  cd ${tmp?}/x/usr/local/share/java || exit $?

  cp=
  for x in *.jar; do
    cp=${cp:+${cp?};}${x?}
  done
  readonly cp

  iabt=--initialize-at-build-time
  x=
  x=${x:+${x?},}com.microsoft.sqlserver.jdbc
  x=${x:+${x?},}java.sql.DriverManager
  x=${x:+${x?},}java.util.logging.Logger
  x=${x:+${x?},}mssql.googlecode
  x=${x:+${x?},}org.sqlite
  iabt=${iabt?}=${x?}
  readonly iabt

  iart=--initialize-at-run-time
  x=
  x=${x:+${x?},}com.microsoft.sqlserver.jdbc.AuthenticationJNI
  x=${x:+${x?},}io.netty.handler.ssl
  x=${x:+${x?},}org.sqlite.SQLiteJDBCLoader
  iart=${iart?}=${x?}
  readonly iart

  eval " ${cmd?}"' \
    "${iabt?}" \
    "${iart?}" \
    --allow-incomplete-classpath \
    --install-exit-handlers \
    --no-fallback \
    --report-unsupported-elements-at-runtime \
    -H:+AddAllCharsets \
    -H:+IncludeAllLocales \
    -H:DynamicProxyConfigurationFiles=proxy-config.json \
    -H:JNIConfigurationFiles=jni-config.json \
    -H:Name=x.exe \
    -H:PredefinedClassesConfigurationFiles=predefined-classes-config.json \
    -H:ReflectionConfigurationFiles=reflect-config.json \
    -H:ResourceConfigurationFiles=resource-config.json \
    -H:SerializationConfigurationFiles=serialization-config.json \
    -cp "${cp?}" \
    com.stealthsoftwareinc.pulsarvlds.Server \
  ' || exit $?

  mv -f *.exe ../../bin/pulsar-vlds-server.exe || exit $?
  for x in *.dll; do
    case ${x?} in [!*]*)
      mv -f ${x?} ../../lib || exit $?
    esac
  done

) || exit $?

for x in \
  db1.cmd \
  db2.cmd \
  ph.cmd \
; do
  cp \
    ${d?}server.cmd \
    ${tmp?}/x/${x?} \
  || exit $?
done

UNZIP= eval " ${UNZIP?}"' \
  -d ${tmp?}/x/usr/local/lib \
  -j \
  -qq \
  ${tmp?}/x/usr/local/share/java/sqlite-jdbc-*.jar \
  org/sqlite/native/Windows/x86_64/sqlitejdbc.dll \
' || exit $?

rm -f -r \
  ${tmp?}/x/usr/local/bin/pulsar-vlds-server \
  ${tmp?}/x/usr/local/share/java \
  ${tmp?}/x/usr/local/share/pulsar-vlds/cmd \
|| exit $?

touch ${tmp?}/x || exit $?
mv -f ${tmp?}/x ${dst?} || exit $?
