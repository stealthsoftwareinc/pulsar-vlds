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

FROM ubuntu:22.04

RUN : \
  && export DEBIAN_FRONTEND=noninteractive \
  && apt-get -q -y update \
  && apt-get -q -y install \
       autoconf \
       automake \
       g++ \
       gawk \
       gcc \
       git \
       jq \
       libtool \
       m4 \
       make \
       openjdk-8-jdk-headless \
       wget \
       zip \
;

COPY sst.tar.gz /x/
RUN : \
  && mkdir /x/sst \
  && cd /x/sst \
  && tar xzf ../sst.tar.gz \
  && mv sst-*/ sst \
  && cd * \
  && ./configure \
       --with-build-groups=java \
  && make install-java-jardeps \
  && make DESTDIR=/x/out install-java-jardeps \
  && ./configure \
       --with-build-groups=java \
       --with-netty \
  && make \
  && make install \
  && make DESTDIR=/x/out install \
;

COPY pulsar-vlds.tar.gz /x/
RUN : \
  && mkdir /x/pulsar-vlds \
  && cd /x/pulsar-vlds \
  && tar xzf ../pulsar-vlds.tar.gz \
  && cd * \
  && ./configure \
       ASCIIDOCTOR=build-aux/adock \
  && make install-java-jardeps \
  && make DESTDIR=/x/out install-java-jardeps \
  && ./configure \
       ASCIIDOCTOR=build-aux/adock \
  && make \
  && make install \
  && make DESTDIR=/x/out install \
;
