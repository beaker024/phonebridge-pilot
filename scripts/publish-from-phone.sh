#!/usr/bin/env bash
# Run ONLY after choosing to publish this open-source code to your own FREE,
# public GitHub repository. No phone data, keys or cloud deployment are uploaded.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo 'Install GitHub CLI in Termux: pkg install gh git'; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'First run: gh auth login (GitHub.com, HTTPS, browser sign-in)'; exit 1; }
test ! -e .phonebridge || { echo 'Remove private pairing/config files from this source directory first.'; exit 1; }
test -z "$(find . -name '*.key' -o -name '*.keystore' -o -name '*.jks')" || { echo 'Private key file found. Refusing to publish.'; exit 1; }
git init -b main
git add .gitignore LICENSE README.md android bridge docs research scripts tests .github
git -c user.name='PhoneBridge Builder' -c user.email='builder@localhost' commit -m 'Restricted phone-local pilot'
gh repo create phonebridge-pilot --public --source=. --remote=origin --push
echo 'Open your repository → Actions → Free public-repository pilot build.'
echo 'After a green run, download phonebridge-pilot-apks. No payment method is needed.'
