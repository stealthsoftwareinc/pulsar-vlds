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

sst_ajh_asciidoctor_document() {

  declare adoc
  declare ag_json
  declare child
  declare clean_rule
  declare distribute
  declare html
  declare html_recipe_sh
  declare imagesdir
  declare prefix
  declare s
  declare slug
  # Bash >=4.2: declare -g sst_ajh_asciidoctor_document_first_call
  declare tar_file
  declare tar_file_slug
  declare tarname
  declare x
  declare y

  html_recipe_sh=build-aux/sst_ajh_asciidoctor_document_html_recipe.sh
  readonly html_recipe_sh

  for ag_json; do

    if [[ ! "${sst_ajh_asciidoctor_document_first_call-}" ]]; then
      sst_ajh_asciidoctor_document_first_call=1

      mkdir -p ${html_recipe_sh%/*}
      sst_ihd <<'EOF' >$html_recipe_sh
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

        eval ASCIIDOCTOR_FLAGS='${ASCIIDOCTOR_FLAGS?}'
        eval MAKE='${MAKE:?}'
        eval SED='${SED:?}'
        eval TSUF=${TSUF:?}
        eval dst=${dst:?}
        eval imagesdir=${imagesdir:?}
        eval prefix=${prefix:?}
        eval slug=${slug:?}
        eval srcdir=${srcdir:?}

        readonly ASCIIDOCTOR_FLAGS
        readonly MAKE
        readonly SED
        readonly TSUF
        readonly dst
        readonly imagesdir
        readonly prefix
        readonly slug
        readonly srcdir

        x=
        x="${x?} -a imagesdir=${imagesdir?}"
        x="${x?} ${ASCIIDOCTOR_FLAGS?}"
        eval " ${MAKE?}"' \
          ${slug?}_disable_wrapper_recipe=/x \
          ASCIIDOCTOR_FLAGS="${x?}" \
          ${dst?} \
        ' || exit $?

        #---------------------------------------------------------------
        # KaTeX installation
        #---------------------------------------------------------------

        if test -d ${prefix?}katex; then

          mv -f ${dst?} ${dst?}${TSUF?}1 || exit $?

          x='
            /<script.*[Mm]ath[Jj]ax.*\.js/ d
          '
          eval " ${SED?}"' \
            "${x?}" \
            <${dst?}${TSUF?}1 \
            >${dst?}${TSUF?}2 \
          ' || exit $?

          mv -f ${dst?}${TSUF?}2 ${dst?} || exit $?

        fi

        #---------------------------------------------------------------
        # Fonts installation
        #---------------------------------------------------------------

        if test -d ${prefix?}fonts; then

          mv -f ${dst?} ${dst?}${TSUF?}1 || exit $?

          x='
            /<link.*fonts\.googleapis\.com/ d
          '
          eval " ${SED?}"' \
            "${x?}" \
            <${dst?}${TSUF?}1 \
            >${dst?}${TSUF?}2 \
          ' || exit $?

          mv -f ${dst?}${TSUF?}2 ${dst?} || exit $?

        fi

        #---------------------------------------------------------------
EOF
      sst_am_distribute $html_recipe_sh

    fi

    sst_expect_ag_json html "$ag_json"
    sst_expect_extension $ag_json .html.ag.json

    slug=$(sst_underscore_slug $html)

    adoc=${html%.html}.adoc
    sst_expect_any_file $adoc{,.ag.json,.ag,.ac,.am,.im.in,.in,.im}

    prefix=$(sst_get_prefix $ag_json)
    if [[ "$prefix" == "" ]]; then
      sst_barf 'document must have its own subdirectory: %s' $ag_json
    fi

    sst_jq_get_string "$ag_json" .tarname tarname
    if [[ ! "$tarname" ]]; then
      tarname=${html%/*}
      tarname=${tarname##*/}
    fi

    tar_file=$(sst_get_prefix ${prefix%/})$tarname.tar
    tar_file_slug=$(sst_underscore_slug $tar_file)

    sst_jq_get_string_or_null .clean_rule $ag_json clean_rule
    case $clean_rule in
      '' | mostlyclean | clean | distclean | maintainer-clean)
        :
      ;;
      *)
        sst_barf '%s: .clean_rule: invalid value' $ag_json
      ;;
    esac

    sst_jq_get_boolean_or_null .distribute $ag_json distribute
    if [[ "$distribute" == true ]]; then
      distribute=1
    else
      distribute=
    fi

    sst_jq_get_string_or_null .imagesdir $ag_json imagesdir
    if [[ "$imagesdir" == "" ]]; then
      imagesdir=images
    else
      sst_expect_source_path "$imagesdir"
    fi

    sst_am_var_add_unique_word ${slug}_children $prefix$imagesdir

    sst_am_append <<EOF

#-----------------------------------------------------------------------
# $html
#-----------------------------------------------------------------------

$prefix$imagesdir:
	\$(AM_V_at)\$(MKDIR_P) \$@

EOF

    if ((distribute)); then
      sst_am_distribute $html
      if [[ "$clean_rule" != "" ]]; then
        sst_warn '%s: ignoring clean_rule because distribute is true' $ag_json
      fi
      clean_rule=maintainer-clean
    elif [[ "$clean_rule" == "" ]]; then
      clean_rule=mostlyclean
    fi

    for x in $prefix**.im.in $prefix**; do
      x=${x%/}
      sst_expect_source_path "$x"
      if [[ ! -f $x ]]; then
        continue
      fi
      if [[ $x == $ag_json || $x == $html ]]; then
        continue
      fi
      sst_ag_process_leaf $html $x child
    done

    if ((distribute)); then
      sst_ihs <<<"
        $html\$(${slug}_disable_wrapper_recipe): \\
          $html_recipe_sh \\
          \$(${slug}_leaves) \\
        \$(empty)
        	\$(GATBPS_at)\$(MAKE) \\
        	  \$(${slug}_children) \\
        	  \$(${slug}_children_nodist) \\
        	;
      " | sst_am_append
    else
      sst_ihs <<<"
        $html\$(${slug}_disable_wrapper_recipe): \\
          $html_recipe_sh \\
          \$(${slug}_children) \\
          \$(${slug}_children_nodist) \\
        \$(empty)
      " | sst_am_append

      #
      # If we were to inline the list of children into a makefile recipe
      # using the makefile variables, we'd eventually run into execve()
      # limits once the list becomes large enough, as that tends to be
      # how make executes recipe lines. Writing the list out to files
      # and reading them in during the recipe prevents this problem,
      # instead leveraging the shell to handle the large list.
      #

      x=${sst_am_var_value[${slug}_children]-}
      printf '%s\n' "${x// /$'\n'}" >$html.children
      sst_am_distribute $html.children

      x=${sst_am_var_value[${slug}_children_nodist]-}
      printf '%s\n' "${x// /$'\n'}" >$html.children_nodist
      sst_am_distribute $html.children_nodist

    fi

    sst_am_append <<EOF
	\$(AM_V_at)rm -f -r \\
	  \$@ \\
	  \$@\$(TSUF)* \\
	  $prefix$imagesdir/diag-* \\
	;
	\$(AM_V_at)( \\
	  x=ASCIIDOCTOR_FLAGS; set x \$(ASCIIDOCTOR_FLAGS); \$(GATBPS_EXPORT); \\
	  x=MAKE; set x \$(MAKE); \$(GATBPS_EXPORT); \\
	  x=SED; set x \$(SED); \$(GATBPS_EXPORT); \\
	  x=TSUF; set x \$(TSUF); \$(GATBPS_EXPORT); \\
	  x=dst; set x \$@; \$(GATBPS_EXPORT); \\
	  x=imagesdir; set x $imagesdir; \$(GATBPS_EXPORT); \\
	  x=prefix; set x $prefix; \$(GATBPS_EXPORT); \\
	  x=slug; set x $slug; \$(GATBPS_EXPORT); \\
	  x=srcdir; set x \$(srcdir); \$(GATBPS_EXPORT); \\
	  sh \$(srcdir)/$html_recipe_sh || exit \$\$?; \\
	)

$html/clean: FORCE
	-rm -f -r \\
	  \$(@D) \\
	  \$(@D)\$(TSUF)* \\
	  $prefix$imagesdir/diag-* \\
	;

$clean_rule-local: $html/clean

$tar_file: $html
	\$(AM_V_at)rm -f -r \$@ \$@\$(TSUF)*
	\$(AM_V_at)\$(MKDIR_P) \$@\$(TSUF)1/$tarname
	\$(AM_V_at)cp $html \$@\$(TSUF)1/$tarname
	\$(GATBPS_at)( \\
	\\
	  xs=; \\
	\\
	  for y in \\
	    $html.children \\
	    $html.children_nodist \\
	  ; do \\
	    ys=\`cat \$\$y\` || exit \$\$?; \\
	    for x in \$\$ys; do \\
	      case \$\$x in *.adoc) \\
	        : \\
	      ;; *) \\
	        case \$\$xs in *" \$\$x "*) \\
	          : \\
	        ;; *) \\
	          xs="\$\$xs \$\$x "; \\
	        esac; \\
	      esac; \\
	    done; \\
	  done; \\
	\\
	  for x in \\
	    css \\
	    gif \\
	    jpg \\
	    js \\
	    png \\
	    svg \\
	    woff \\
	    woff2 \\
	  ; do \\
	    xs="\$\$xs "\` \\
	      find -L ${prefix%/} -name "*.\$\$x" -type f \\
	    \`" " || exit \$\$?; \\
	  done; \\
	\\
	  for x in \$\$xs; do \\
	    y='$prefix\\(.*\\)'; \\
	    y=\`expr \$\$x : \$\$y\` || exit \$\$?; \\
	    case \$\$y in \\
	      */*) \\
	        d=\`dirname \$\$y\` || exit \$\$?; \\
	        if test -d \$@\$(TSUF)1/$tarname/\$\$d; then \\
	          :; \\
	        else \\
	          \$(AM_V_P) && echo \$(MKDIR_P) \\
	            \$@\$(TSUF)1/$tarname/\$\$d \\
	          ; \\
	          \$(MKDIR_P) \\
	            \$@\$(TSUF)1/$tarname/\$\$d \\
	          || exit \$\$?; \\
	        fi; \\
	      ;; \\
	    esac; \\
	    y=\$@\$(TSUF)1/$tarname/\$\$y; \\
	    if test -d \$\$x && test -r \$\$y; then \\
	      echo uh oh 1 >&2; \\
	      exit 1; \\
	    fi; \\
	    if test -f \$\$x && test -d \$\$y; then \\
	      echo uh oh 2 >&2; \\
	      exit 1; \\
	    fi; \\
	    if test -f \$\$y; then \\
	      :; \\
	    else \\
	      \$(AM_V_P) && echo cp -R -L \$\$x \$\$y; \\
	      cp -R -L \$\$x \$\$y || exit \$\$?; \\
	    fi; \\
	  done; \\
	)
	\$(AM_V_at)(cd \$@\$(TSUF)1 && \$(TAR) c $tarname) >\$@\$(TSUF)2
	\$(AM_V_at)mv -f \$@\$(TSUF)2 \$@

${tar_file_slug}_leaves = \$(${slug}_leaves)

$tar_file/clean: FORCE
$tar_file/clean: $html/clean
	-rm -f -r \$(@D) \$(@D)\$(TSUF)*

mostlyclean-local: $tar_file/clean

#-----------------------------------------------------------------------
EOF

    # Distribute any images generated by Asciidoctor Diagram.
    if ((distribute)); then
      autogen_am_var_append EXTRA_DIST $prefix$imagesdir
    fi

  done

}; readonly -f sst_ajh_asciidoctor_document
