#!/usr/bin/env python3
"""Build the pinned GPL libtelio JNI bridge with an existing Rust + Android NDK.

Does not install toolchains, accept SDK licenses, or download private Nord artifacts.
Run: python scripts/build-native.py --ndk <NDK path> [--abi arm64-v8a]
"""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ndk", type=Path, required=True)
parser.add_argument("--abi", choices=["arm64-v8a", "x86_64", "armeabi-v7a"], default="arm64-v8a")
args = parser.parse_args()
UPSTREAM = "c15a3bd60340e9cc0da74d93fe50b3b875e9e052"
vendor = ROOT / "vendor" / "libtelio"
patch = ROOT / "native" / "patches" / "android-socketpair.patch"
targets = {"arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
           "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
           "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi")}
target, clang_target = targets[args.abi]
host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
toolchain = args.ndk.resolve() / "toolchains" / "llvm" / "prebuilt" / host / "bin"
clang = toolchain / (f"{clang_target}29-clang.cmd" if os.name == "nt" else f"{clang_target}29-clang")
if not clang.exists():
    parser.error(f"NDK compiler not found: {clang}")
for tool in ("cargo", "rustup", "cmake", "git"):
    if shutil.which(tool) is None:
        parser.error(f"Install {tool} and add it to PATH first. See docs/NATIVE.md.")
if not vendor.exists():
    vendor.mkdir(parents=True)
    subprocess.run(["git", "init", str(vendor)], check=True)
    subprocess.run(["git", "-C", str(vendor), "remote", "add", "origin", "https://github.com/NordSecurity/libtelio"], check=True)
    subprocess.run(["git", "-C", str(vendor), "fetch", "--depth", "1", "origin", UPSTREAM], check=True)
    subprocess.run(["git", "-C", str(vendor), "checkout", "--detach", "FETCH_HEAD"], check=True)
head = subprocess.check_output(["git", "-C", str(vendor), "rev-parse", "HEAD"], text=True).strip()
if head != UPSTREAM:
    parser.error("vendor/libtelio is not the pinned revision; refusing to overwrite it.")
changed = subprocess.check_output(["git", "-C", str(vendor), "diff", "--name-only"], text=True).splitlines()
if any(name != "crates/telio-wg/src/adapter/neptun.rs" for name in changed):
    parser.error("Upstream has additional local changes; inspect them before building.")
if subprocess.run(["git", "-C", str(vendor), "diff", "--cached", "--quiet"]).returncode != 0:
    parser.error("Upstream has staged changes; refusing to alter its working tree.")
# Apply only our checked-in patch, without resetting or discarding local changes.
if subprocess.run(["git", "-C", str(vendor), "apply", "--check", str(patch)], capture_output=True).returncode == 0:
    subprocess.run(["git", "-C", str(vendor), "apply", str(patch)], check=True)
elif subprocess.run(["git", "-C", str(vendor), "apply", "--reverse", "--check", str(patch)], capture_output=True).returncode != 0:
    parser.error("Native adapter differs from the supported patch; inspect local changes manually.")
env = os.environ.copy()
env["BYPASS_LLT_SECRETS"] = "1"
env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(clang)
env[f"CC_{target.replace('-', '_')}"] = str(clang)
env[f"AR_{target.replace('-', '_')}"] = str(toolchain / ("llvm-ar.exe" if os.name == "nt" else "llvm-ar"))
env["ANDROID_NDK_HOME"] = str(args.ndk.resolve())
env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
subprocess.run(["rustup", "target", "add", target], cwd=ROOT / "native", env=env, check=True)
subprocess.run(["cargo", "build", "--locked", "--release", "--target", target], cwd=ROOT / "native", env=env, check=True)
source = ROOT / "native" / "target" / target / "release" / "libmeshhome.so"
destination = ROOT / "app" / "src" / "main" / "jniLibs" / args.abi
destination.mkdir(parents=True, exist_ok=True)
shutil.copy2(source, destination / source.name)
strip = toolchain / ("llvm-strip.exe" if os.name == "nt" else "llvm-strip")
subprocess.run([str(strip), "--strip-unneeded", str(destination / source.name)], check=True)
licenses = ROOT / "app" / "src" / "main" / "assets" / "licenses"
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy2(vendor / "LICENSE", licenses / "libtelio-LICENSE.txt")
print(f"Installed {destination / source.name}; now build the Android APK with Gradle.")
