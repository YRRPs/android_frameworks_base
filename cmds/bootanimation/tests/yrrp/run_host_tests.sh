#!/bin/bash
# Builds and runs the pure YRRP Matrix and XP units outside the Android tree, then
# checks both shaders against the GLSL ES 1.00 grammar. Needs g++, gtest and
# glslangValidator on the host.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
src=$(cd "${here}/../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "${out}"' EXIT

units=()
for unit in YrrpMatrixConfig YrrpMatrixTimeline YrrpMatrixStatus YrrpXpLayout YrrpXpTimeline; do
    if [[ -f "${src}/${unit}.cpp" ]]; then
        units+=("${src}/${unit}.cpp")
    fi
done
tests=("${here}/YrrpMatrixTest.cpp")
if [[ -f "${here}/YrrpXpTest.cpp" ]]; then
    tests+=("${here}/YrrpXpTest.cpp")
fi

g++ -std=c++17 -Wall -Werror -Wunused -Wunreachable-code -I"${src}" "${units[@]}" "${tests[@]}" \
    -lgtest -lgtest_main -pthread -o "${out}/tests"
"${out}/tests"

if [[ -f "${src}/YrrpMatrixShader.h" ]]; then
    g++ -std=c++17 -Wall -Werror -I"${src}" "${here}/dump_matrix_shader.cpp" -o "${out}/dump"
    "${out}/dump" vert > "${out}/matrix.vert"
    "${out}/dump" frag > "${out}/matrix.frag"
    glslangValidator "${out}/matrix.vert"
    glslangValidator "${out}/matrix.frag"
fi
echo "yrrp bootanimation host checks passed"
