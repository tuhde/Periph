# Debian packaging

Debian 13 (trixie) packages, built on every release and published to the APT
repository [tuhde/periph-apt](https://github.com/tuhde/periph-apt), served by
GitHub Pages at <https://tuhde.github.io/periph-apt>. User instructions:
`INSTALL.md`.

| Package | Arch | Built from |
|---------|------|------------|
| `python3-periph` | all | `python/` (pybuild, `pyproject.toml`) |
| `libperiph-dev` | amd64, arm64, armhf | `cpp/linux/CMakeLists.txt` → `libperiph.a`, headers, `periph.pc`, CMake package `periph::periph` |
| `sigrok-decoders-periph` | all | `sigrok/<name>/` → `/usr/share/libsigrokdecode/decoders/<name>/` |

## Files

- `debian/` — the packaging (one source package `periph`, binary-only builds).
  `build-debs.sh` copies it to `debian/` in a staged source tree; it is not at
  the repo root.
- `build-debs.sh VERSION [ARCH] [OUTDIR]` — builds the packages into
  `dist/debian/`. The build machine's own architecture builds all three
  packages; any other architecture cross-builds `libperiph-dev` only. It
  first checks that no sigrok decoder name clashes with libsigrokdecode4's
  own decoders and that no decoder `id` is used twice.
- `publish-apt.sh APT_REPO_DIR DEB...` — adds packages to a checkout of
  `periph-apt` with reprepro, signing with the default key of `GNUPGHOME`.
  Packages already published at the same version are skipped, so a retried
  release is safe.
- `apt-repo/` — reprepro configuration and the landing page, synced into
  `periph-apt` on every publish (edit them here, not there).

## CI

- `.github/workflows/debian.yml` builds all architectures in `debian:trixie`
  containers on GitHub-hosted runners and installs and smoke-tests the result
  (amd64, arm64). It runs on pull requests that touch the packaged sources.
- `release.yml` calls it with the release version (job `debian`); the `.deb`
  files become release assets, and `debian-publish` pushes them to `periph-apt`.

## Local build

On Debian 13:

```sh
sudo apt install build-essential debhelper cmake libgpiod-dev \
  dh-python pybuild-plugin-pyproject python3-all python3-setuptools lintian
packaging/build-debs.sh 1.6.0
```

Cross-building for `arm64`/`armhf` additionally needs
`sudo dpkg --add-architecture <arch>` and
`crossbuild-essential-<arch> libgpiod-dev:<arch>`.

## One-time setup of the APT repository

1. Create the public repository `tuhde/periph-apt` with an initial commit that
   contains an empty `docs/.nojekyll`, so `main` and `docs/` exist. The first
   release fills in `conf/`, `db/` and the rest of `docs/`.
2. In its settings, enable GitHub Pages: *Deploy from a branch*, `main`, `/docs`.
3. Create the signing key (no passphrase; it only signs the repository index):
   ```sh
   export GNUPGHOME=$(mktemp -d)
   gpg --batch --passphrase '' --quick-gen-key \
     "Periph APT repository" rsa4096 sign never
   gpg --armor --export-secret-keys > apt-signing-key.asc
   ```
4. Create a deploy key with write access to `periph-apt`:
   ```sh
   ssh-keygen -t ed25519 -N '' -C periph-apt-deploy -f periph-apt-deploy
   ```
   Add `periph-apt-deploy.pub` under *periph-apt → Settings → Deploy keys*
   with *Allow write access*.
5. In `tuhde/Periph`, create the environment `apt-repo` with the secrets
   `APT_SIGNING_KEY` (contents of `apt-signing-key.asc`) and
   `APT_REPO_DEPLOY_KEY` (contents of `periph-apt-deploy`). Then delete the
   local key files and the temporary `GNUPGHOME`. Keep an offline backup of
   the signing key: replacing it later forces every user to re-download the
   keyring.

The public key is exported to `docs/periph-archive-keyring.gpg` (and `.asc`)
on every publish.
