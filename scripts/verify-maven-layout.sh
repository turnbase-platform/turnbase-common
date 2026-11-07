#!/usr/bin/env bash
# Proves a Maven repository directory is consumable (IMP-M21-05, ADR-0022):
# a throwaway consumer pom resolves every artifact from it as a file:// repository,
# using an empty local repository so nothing is satisfied from a cache.
# Usage: verify-maven-layout.sh <repo-dir> <version> <artifactId>...
set -euo pipefail
repo=$(cd "$1" && pwd); version=$2; shift 2
group=io.github.turnbase-platform
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
for a in "$@"; do
  base="$repo/${group//.//}/$a/$version/$a-$version"
  for f in .pom .jar -sources.jar; do
    [[ -s "$base$f" ]] || { echo "missing: $base$f" >&2; exit 1; }
    [[ -s "$base$f.sha1" ]] || { echo "missing checksum: $base$f.sha1" >&2; exit 1; }
  done
done
{
  echo '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
  echo '<groupId>test</groupId><artifactId>consumer</artifactId><version>1</version>'
  echo "<repositories><repository><id>layout</id><url>file://$repo</url></repository></repositories>"
  echo '<dependencies>'
  for a in "$@"; do
    echo "<dependency><groupId>$group</groupId><artifactId>$a</artifactId><version>$version</version></dependency>"
  done
  echo '</dependencies></project>'
} > "$work/pom.xml"
mvn --batch-mode --no-transfer-progress -f "$work/pom.xml" -Dmaven.repo.local="$work/repo" dependency:resolve
echo "layout OK: $*"
