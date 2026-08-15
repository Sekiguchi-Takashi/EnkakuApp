#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=EnkakuApp
API=https://api.github.com
curl -s -o /dev/null -X POST -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" $API/user/repos -d "{\"name\":\"$REPO\",\"private\":true}"
if [ ! -d .git ]; then
  git init -b main
fi
git config user.email "deploy@appathy.local"
git config user.name "$GHUSER"
git remote remove origin 2>/dev/null
git remote add origin "https://$GHUSER:$TOKEN@github.com/$GHUSER/$REPO.git"
git add -A
git commit -m "${1:-update}"
git fetch origin main 2>/dev/null
git pull --rebase origin main 2>/dev/null
git push -u origin main
LATEST=$(curl -s -H "Authorization: token $TOKEN" $API/repos/$GHUSER/$REPO/releases/latest | grep '"tag_name"' | head -n1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
if [ -z "$LATEST" ]; then
  NEXT=v1.0.0
else
  BASE=${LATEST#v}
  MAJOR=$(printf '%s' "$BASE" | cut -d. -f1)
  MINOR=$(printf '%s' "$BASE" | cut -d. -f2)
  PATCH=$(printf '%s' "$BASE" | cut -d. -f3)
  PATCH=$((PATCH + 1))
  NEXT=v$MAJOR.$MINOR.$PATCH
fi
SHA=$(curl -s -H "Authorization: token $TOKEN" $API/repos/$GHUSER/$REPO/git/refs/heads/main | grep '"sha"' | head -n1 | sed -E 's/.*"sha": *"([^"]+)".*/\1/')
curl -s -o /dev/null -X POST -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" $API/repos/$GHUSER/$REPO/git/refs -d "{\"ref\":\"refs/tags/$NEXT\",\"sha\":\"$SHA\"}"
printf 'pushed and tagged %s\n' "$NEXT"
