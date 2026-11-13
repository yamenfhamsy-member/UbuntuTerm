# Third party notices

UbuntuTerm is a fork of [Termux](https://github.com/termux/termux-app) and re-uses code and
data from several other projects. The licences of all of them apply to the corresponding
parts of UbuntuTerm.

## UbuntuTerm

Distributed under the terms of the GNU General Public License version 3, the same licence as
Termux. See [../LICENSE.md](../LICENSE.md).

## Termux (termux/termux-app)

* Copyright: Termux project and contributors
* Licence: GPL-3.0
* Used for: the complete application (terminal emulator, terminal view, shared library,
  bootstrap handling, application services) and the `testkey_untrusted.jks` test key
* Upstream commit: `8629e632fcb95da272221be327db653fb24befe9`
* <https://github.com/termux/termux-app>

## Termux:API (termux/termux-api)

* Copyright: Termux project and contributors
* Licence: GPL-3.0
* Used for: the embedded Android API implementation (`app/src/main/java/com/termux/api/`),
  namely the receiver, the socket listener, the result returner and the API classes
* Upstream commit: the `master` branch of <https://github.com/termux/termux-api>
* The upstream sources are modified: the package name was adapted to `com.ubutumerm.app`, the
  receiver is part of the main application (no separate APK), and only permission light API
  methods are exposed.

## Termux:API package (termux/termux-api-package)

* Copyright: Termux project and contributors
* Licence: MIT
* Used for: the command line wrappers that are installed into `/usr/local/bin` of the guest
  (`app/src/main/assets/ubuntu/bin/`). The scripts were taken from release `v0.60.0` of
  <https://github.com/termux/termux-api-package> and adapted: the shebang and the path of
  the `termux-api` client point to the guest paths.

```
MIT License

Copyright (c) 2017-2021 Termux

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## PRoot (termux/proot)

* Copyright: Cédric VINCENT and STMicroelectronics, maintained by the Termux project
* Licence: GPL-2.0
* Used for: the `proot` binary of the host runtime, which executes the Ubuntu guest
* <https://github.com/termux/proot>

## termux-packages

* Copyright: Termux project and contributors
* Licence: GPL-3.0 (per package, see `packages/*/build.sh`)
* Used for: the host bootstrap (bash, coreutils, apt, dpkg, proot, xz, …) that is compiled
  from source by the UbuntuTerm build, and the `termux-packages` patch in `patches/`
* <https://github.com/termux/termux-packages>

## Ubuntu (ubuntu-base)

* Copyright: Canonical Ltd. and the Ubuntu authors
* Licence: the packages of the image are licensed individually, the base image itself is
  published under the terms of the Ubuntu distribution
* Used for: the Ubuntu 26.04.1 LTS (Resolute Raccoon) arm64 rootfs that is bundled in the
  APK and downloaded from <https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/>
* The individual package licences are installed inside the guest under
  `/usr/share/doc/<package>/copyright`.

## Apache Commons (commons-compress, commons-io)

* Copyright: The Apache Software Foundation
* Licence: Apache-2.0
* Used for: the decompression of the bundled Ubuntu rootfs archive (commons-compress and its
  XZ support, commons-io for the bounded input stream of the XZ decoder)
* <https://commons.apache.org/proper/commons-compress/> and
  <https://commons.apache.org/proper/commons-io/>

## XZ for Java (org.tukaani:xz)

* Copyright: The XZ for Java authors
* Licence: public domain (0BSD)
* Used for: the XZ decoder of commons-compress that decompresses the bundled Ubuntu rootfs
  archive
* <https://github.com/tukaani-project/xz-java>

## Android Open Source Project

* Copyright: The Android Open Source Project
* Licence: Apache-2.0
* Used for: the Android SDK, the Android NDK and the platform APIs that the application is
  built against. The application is not linked against the AOSP sources.

## Other components of the Termux bootstrap

The bootstrap contains binaries of many packages (bash, coreutils, apt, dpkg, curl, openssl,
libtalloc, libandroid-shmem, …). Every one of them is distributed under its own licence, the
licence texts are part of the bootstrap under `/usr/share/LICENSES` and the sources are
published at <https://github.com/termux/termux-packages>.

## Reporting a licensing issue

Please open an issue at <https://github.com/yamenfhamsy-member/UbuntuTerm/issues>.
