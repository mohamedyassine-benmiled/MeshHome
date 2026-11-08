param([ValidateSet('arm64-v8a', 'x86_64', 'armeabi-v7a')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
# Convenience for this workstation's isolated tools. General builds should use
# build-native.py with their own Rust/CMake/NDK installations.
$tools = Join-Path $env:LOCALAPPDATA 'Temp\opencode\meshhome-native-tools'
if (!(Test-Path "$tools\cargo\bin\cargo.exe")) { throw 'Isolated toolchain not found. Use build-native.py with an installed toolchain.' }
$env:RUSTUP_HOME = "$tools\rustup"
$env:CARGO_HOME = "$tools\cargo"
$env:PYTHONPATH = "$tools\python-packages"
$hostBin = "$tools\rustup\toolchains\1.99.1-x86_64-pc-windows-gnu\lib\rustlib\x86_64-pc-windows-gnu\bin"
$env:PATH = "$tools\bin;$tools\cargo\bin;$tools\python-packages\cmake\data\bin;$tools\python-packages\bin;$hostBin;$env:PATH"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = "$hostBin\rust-lld.exe"
$env:CMAKE_GENERATOR = 'Ninja'
python "$PSScriptRoot\build-native.py" --ndk "$tools\android-ndk-r28c" --abi $Abi
exit $LASTEXITCODE
