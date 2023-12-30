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

sst_push_postmortem_job_container() {

  declare    not_pushing

  not_pushing="Not pushing postmortem job container:"
  readonly not_pushing

  #---------------------------------------------------------------------
  #
  # The if condition here should be kept in sync with the one in
  # sst_exit_trap.
  #
  # The reason for repeating it there is for performance. If we didn't,
  # we'd incur a load and call of sst_push_postmortem_job_container at
  # every exit, not just at every exit in CI.
  #

  if [[ x \
    && ! "${GITLAB_CI-}" \
  ]]; then
    return
  fi

  #---------------------------------------------------------------------

  if ((SST_PRELUDE_DEPTH > 0)); then
    return
  fi

  if [[ ! "${SST_PUSH_POSTMORTEM_JOB_CONTAINER-}" ]]; then
    sst_join "$not_pushing" \
      "SST_PUSH_POSTMORTEM_JOB_CONTAINER is unset or empty." \
    || sst_barf
    return
  fi

  if [[ "${SST_NO_PUSH_POSTMORTEM_JOB_CONTAINER-}" ]]; then
    sst_join "$not_pushing" \
      "SST_NO_PUSH_POSTMORTEM_JOB_CONTAINER is nonempty." \
    || sst_barf
    return
  fi

  if ! docker --version &>/dev/null; then
    sst_join "$not_pushing" \
      "Docker is not installed." \
    || sst_barf
    return
  fi

  if [[ "${GITLAB_CI-}" ]]; then

    if [[ "${CI_COMMIT_REF_PROTECTED-}" == true && \
          ! "${SST_PUSH_PROTECTED_POSTMORTEM_JOB_CONTAINER-}" ]]; then
      sst_join "$not_pushing" \
        "This job is protected and" \
        "SST_PUSH_PROTECTED_POSTMORTEM_JOB_CONTAINER" \
        "is unset or empty." \
      || sst_barf
      return
    fi

    local dst
    local -a dsts
    local mounted
    local s
    local src
    local var

    dsts=()

    if [[ x \
      && "${CI_REGISTRY_IMAGE-}" \
      && "${CI_JOB_ID-}" \
    ]]; then
      dst=$CI_REGISTRY_IMAGE
      dst+=/jobs
      dst+=:$CI_JOB_ID
      dsts+=("$dst")
    fi

    if [[ x \
      && "${CI_REGISTRY-}" \
      && "${CI_MERGE_REQUEST_SOURCE_PROJECT_PATH-}" \
      && "${CI_JOB_ID-}" \
    ]]; then
      dst=$CI_REGISTRY
      dst+=/$CI_MERGE_REQUEST_SOURCE_PROJECT_PATH
      dst+=/jobs
      dst+=:$CI_JOB_ID
      dsts+=("$dst")
    fi

    readonly dsts

    if ((${#dsts[@]} == 0)); then
      sst_join \
        "Not pushing postmortem job container." \
        "Variables of interest:" \
      || sst_barf
      for var in \
        CI_JOB_ID \
        CI_MERGE_REQUEST_SOURCE_PROJECT_PATH \
        CI_REGISTRY \
        CI_REGISTRY_IMAGE \
      ; do
        eval sst_z=\${$var+x}
        if [[ "$sst_z" ]]; then
          eval sst_z=\$$var
          sst_z=$(sst_smart_quote "$sst_z") || sst_barf
          sst_join "  $var=$sst_z" || sst_barf
        else
          sst_join "  $var is unset" || sst_barf
        fi
      done
      return
    fi

    mounted=$(mount) || sst_barf
    if [[ "$mounted" == *' /builds '* ]]; then
      mounted=1
    else
      mounted=
    fi
    readonly mounted

    src=$(docker ps --format="{{.CreatedAt}} @{{.ID}}") || sst_barf
    src=$(sort <<<"$src") || sst_barf
    src=$(head -n 1 <<<"$src") || sst_barf
    src=${src##*@}

    if ((mounted)); then
      cp -R /builds /builds2 || sst_barf
    fi

    src=$(docker commit --pause=false $src) || sst_barf

    if ((mounted)); then
      src=$(
        docker build -q - <<<"
          FROM $src
          RUN rmdir /builds && mv /builds2 /builds
        "
      )
    fi

    readonly src

    for dst in "${dsts[@]}"; do
      docker tag "$src" "$dst" || sst_barf
      sst_join "Pushing postmortem job container: $dst" || sst_barf
      docker push "$dst" >/dev/null && s=0 || s=$?
      if ((s == 0)); then
        sst_join "Pushing postmortem job container succeeded." || sst_barf
      else
        sst_join "Pushing postmortem job container failed." || sst_barf
      fi
    done

  else

    sst_barf "Missing case."

  fi

}; readonly -f sst_push_postmortem_job_container
