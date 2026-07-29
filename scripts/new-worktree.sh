#!/usr/bin/env bash
set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: $0 <branch-name>" >&2
  exit 1
fi

BRANCH="$1"
REPO_ROOT="$(git rev-parse --show-toplevel)"
WORKTREE_DIR="$(dirname "$REPO_ROOT")/and-code-${BRANCH}"

git fetch origin

if git worktree list | grep -q "$WORKTREE_DIR"; then
  echo "Worktree already exists: $WORKTREE_DIR" >&2
  exit 1
fi

git worktree add "$WORKTREE_DIR" -b "$BRANCH" origin/main

echo ""
echo "Worktree created: $WORKTREE_DIR"
echo "Branch: $BRANCH (based on origin/main)"
