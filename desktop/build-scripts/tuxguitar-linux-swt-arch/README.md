# TuxGuitar Arch package

Packages the "generic GNU/Linux" build (INSTALL.md, *Generic GNU/Linux*) as a
pacman package, so one build can be installed and updated on several machines
without unpacking tarballs by hand.

The upstream build is a plain directory of jars, `.so` files and resources. This
copies it to `/opt/tuxguitar` and adds the usual FHS symlinks, so pacman tracks
every file and removes them again on uninstall.

## What you need

* One machine with `pacman`, to run `makepkg`. That is all - no JDK, Maven or
  SWT is needed on any machine that only installs.
* Your existing build machine, to produce the tarball.

Runtime dependencies are declared in the PKGBUILD and installed automatically:
`jre-openjdk`, `alsa-lib`, `fluidsynth`, `jack2`, `lilv`, `suil`. TuxGuitar
bundles its own SWT and JNI libraries, so nothing else is required.

## Build and install

**1. Build TuxGuitar** as described in INSTALL.md, then tar the result straight
into this directory:

```sh
(cd desktop/build-scripts/tuxguitar-linux-swt && mvn -e clean verify -P native-modules)

tar -C desktop/build-scripts/tuxguitar-linux-swt/target \
    -czf desktop/build-scripts/tuxguitar-linux-swt-arch/tuxguitar-linux-swt.tar.gz \
    tuxguitar-9.99-SNAPSHOT-linux-swt
```

**2. Build the package** (on an Arch machine):

```sh
cd desktop/build-scripts/tuxguitar-linux-swt-arch
makepkg -f --nodeps
```

This produces `tuxguitar-9.99.rNNNN-1-x86_64.pkg.tar.zst`, where `NNNN` is the
current commit count.

* `-f` so a rebuild does not stop to ask about overwriting the previous package.
* `--nodeps` because everything in `depends` is a *runtime* requirement of the
  machines you install onto, not something the package build needs. The
  `package()` step only copies an already-built tree, so `makepkg` has nothing
  to resolve them against. Without it you would have to install a JRE and JACK
  on the build machine just to produce a tarball.

**3. Install on each machine:**

```sh
scp tuxguitar-9.99.rNNNN-1-x86_64.pkg.tar.zst <host>:~/
ssh <host> 'sudo pacman -U ./tuxguitar-9.99.rNNNN-1-x86_64.pkg.tar.zst'
```

## Updating

Repeat steps 1 and 2 for the new build, then step 3 on each machine. The version
is derived from the commit count, so pacman sees each rebuild as a normal
upgrade rather than a downgrade.

If you ever build outside a git checkout, `pkgver` falls back to `9.99.r0` and
the second upgrade is rejected as a downgrade. Bump `pkgver` by hand in that
case, or pass `--allow-downgrade`.

Songs and settings live in your home directory, not in `/opt/tuxguitar`, so they
survive upgrades and uninstalls.

## Uninstall

```sh
sudo pacman -R tuxguitar
```

Removes every file the package installed; nothing is left behind in `/usr`.

## Optional: the LV2 synth backend

TuxGuitar can drive a third-party LV2 synth. The helper at
`/opt/tuxguitar/lv2-client/tuxguitar-synth-lv2.bin` is what does it: TuxGuitar
launches it as a subprocess, it connects back over a local socket, loads the LV2
plugin with lilv, and shows the plugin's interface with suil and Qt5.

The helper ships with the package but stays inert until you opt in:

1. Install `qt5-base`, which the helper needs to display the plugin's UI.
2. Install the LV2 synth you want to drive, so it is registered with lilv.
3. Set the `lv2.client.command` property of the `tuxguitar-synth-lv2`
   configuration to a comma-separated command:

   ```
   /opt/tuxguitar/lv2-client/tuxguitar-synth-lv2.bin,${lv2.sessionId},${lv2.serverPort},${lv2.bufferSize},${lv2.pluginUri}
   ```

That argument order is fixed by `LV2Client_parseArguments` in
`desktop/TuxGuitar-synth-lv2/src/main/cxx/LV2Client.c`.

## Native library compatibility

The JNI libraries record only sonames (`libfluidsynth.so.3`, `libjack.so.0`,
`liblilv-0.so.0`, `libasound.so.2`), so each machine resolves them against its
own libraries. Building in the `sbx` sandbox and installing on Arch is
therefore fine. It does mean the package ties you to those sonames: if a
dependency ever bumps its soname, rebuild TuxGuitar.
