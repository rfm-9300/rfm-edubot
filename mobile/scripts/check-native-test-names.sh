#!/usr/bin/env bash
# Kotlin/Native rejects certain characters inside a backtick-quoted declaration name, while the JVM
# and Android accept them. A comma in a test name therefore compiles locally, passes the `logic` and
# `android` CI jobs, and only fails on the macOS `ios-framework` job minutes later:
#
#   e: ...TimesTest.kt:20:9 Name contains illegal characters: ",".
#
# This catches it on Linux in under a second. Run from the `mobile` directory.
#
# The comparison is plain bash string equality, one character at a time. Regex classes and `awk -v`
# both mangle at least one of these characters — a lone backslash through `awk -v` arrives empty,
# and `index(s, "")` is true for every line, which is how an earlier version of this script
# reported every name in a file as an offender.
set -uo pipefail

cd "$(dirname "$0")/.."

# What Kotlin/Native refuses in a backticked name. Space and apostrophe are fine.
illegal=(',' '.' ';' '(' ')' '[' ']' '{' '}' '/' '\' '<' '>' ':')

contains_illegal() {
    local name=$1 i char bad
    for ((i = 0; i < ${#name}; i++)); do
        char=${name:i:1}
        for bad in "${illegal[@]}"; do
            if [[ $char == "$bad" ]]; then
                return 0
            fi
        done
    done
    return 1
}

found=0
while IFS= read -r line; do
    # "path:line: fun `the name`() {" -> location and name, without touching the path.
    location=${line%%: *}
    name=${line#*fun \`}
    name=${name%%\`*}
    if contains_illegal "$name"; then
        printf '  %s\n    %s\n' "$location" "$name" >&2
        found=1
    fi
done < <(grep -rn 'fun `' --include='*.kt' . | grep -v '/build/')

if [[ $found -eq 1 ]]; then
    cat >&2 <<'EOF'

Those backtick-quoted names contain characters Kotlin/Native rejects, so the iOS targets will not
compile. Rephrase the name instead of dropping the character: "reads as offline rather than as a
crash" rather than "reads as offline not as a crash".
EOF
    exit 1
fi

echo "No backtick-quoted name uses a character Kotlin/Native rejects."
