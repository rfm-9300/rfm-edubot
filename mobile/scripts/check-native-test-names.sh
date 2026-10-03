#!/usr/bin/env bash
# Kotlin/Native rejects certain characters inside a backtick-quoted declaration name, while the JVM
# and Android accept them. A comma in a test name therefore compiles locally, passes the `logic` and
# `android` CI jobs, and only fails on the macOS `ios-framework` job minutes later:
#
#   e: ...TimesTest.kt:20:9 Name contains illegal characters: ",".
#
# This catches it on Linux in under a second. Run from the `mobile` directory.
set -uo pipefail

cd "$(dirname "$0")/.."

# What Kotlin/Native refuses in a backticked name. Space and apostrophe are fine. Matched one
# character at a time with grep -F, because several of these are awkward in a shell or regex class.
illegal=(',' '.' ';' '(' ')' '[' ']' '{' '}' '/' '\' '<' '>' ':')

names=$(grep -rn 'fun `' --include='*.kt' . | grep -v '/build/' | sed 's/^\(.*:[0-9]*\): *fun `\([^`]*\)`.*/\1\t\2/')

offenders=""
for char in "${illegal[@]}"; do
    # Only the name half of each line is checked; a path always contains "/" and "." legitimately.
    hits=$(printf '%s\n' "$names" | awk -F'\t' -v c="$char" 'index($2, c) { print }')
    [ -n "$hits" ] && offenders+="$hits"$'\n'
done

if [ -n "${offenders//[$'\n']/}" ]; then
    printf '%s' "$offenders" | sort -u | awk -F'\t' '{printf "  %s\n    %s\n", $1, $2}' >&2
    cat >&2 <<'EOF'

Those backtick-quoted names contain characters Kotlin/Native rejects, so the iOS targets will not
compile. Rephrase the name instead of dropping the character: "reads as offline rather than as a
crash" rather than "reads as offline not as a crash".
EOF
    exit 1
fi

echo "No backtick-quoted name uses a character Kotlin/Native rejects."
