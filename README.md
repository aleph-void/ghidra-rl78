> **Ghidra-Rare fork.** This branch rebuilds the instruction set from the RL78 manual, fixes the
> spec files, and adds release builds for the three newest Ghidra releases and test suites; see
> [RL78/GHIDRA-RARE.md](RL78/GHIDRA-RARE.md) for the test results and remaining gaps, and
> [RL78/CREDITS.md](RL78/CREDITS.md) for the original author and every change. Prebuilt zips for
> Ghidra 12.1.3, 12.1.4 and 12.2 are on this fork's
> [Releases page](https://github.com/aleph-void/ghidra-rl78/releases). The original README
> follows; only the parts that are no longer correct were changed (marked *Ghidra-Rare*).

# Renesas RL78 architecture for Ghidra

Supports:
- RL78-S1: RL78/G10, RL78/G1M, RL78/G1N
- RL78-S2: RL78/G12, RL78/G13, RL78/G13A, RL78/G1A, RL78/G1C, RL78/G1D, RL78/G1E, RL78/G1P, RL78/I1A, RL78/L12, RL78/L13, RL78/D1A, RL78/F12
- RL78-S3 core: RL78/G23, RL78/G11, RL78/G14, RL78/G1F, RL78/G1G, RL78/G1H, RL78/H1D, RL78/I1B, RL78/I1C, RL78/I1D, RL78/I1E, RL78/L1A, RL78/L1C, RL78/F23, RL78/F24, RL78/F13, RL78/F14, RL78/F15

*Ghidra-Rare:* the three cores map to two languages. They differ in one encoding: `CE FB xx` is
the multiply/divide/MAC group on S3 and a plain `mov 0xffffb, #xx` on S1/S2.

| Core | Language ID | Notes |
|---|---|---|
| RL78-S3 | `RL78:LE:16:default` | The default. Includes MULHU, MULH, DIVHU, DIVWU, MACHU and MACH |
| RL78-S2 | `RL78:LE:16:S2` | No multiply/divide/MAC |
| RL78-S1 (G10) | `RL78:LE:16:S2` | S1 is S2 without register banks: no `SEL RBn`, and only bank 0 (FFEF8-FFEFF, the same addresses as S2's bank 0). The S2 language still decodes `SEL RBn`, which S1 code does not contain. S1's smaller flash mirror (F8000-FDEFF) is not modelled; no mirror is modelled for any core |

ELF files built by GCC select the language from `e_flags`: G14 or unspecified selects S3; G13 and
G10 select S2. For a raw image, choose the language by the chip's core.

# Installing

~~Copy `RL78` to <ghidra_dir>/Ghidra/Processors~~

~~Or install via [GVM](https://github.com/CUB3D/ghidra-version-manager/) with `gvm extensions install rl78`~~

*Ghidra-Rare:* this branch targets Ghidra 12.1.4 and is built and tested on 12.1.3, 12.1.4 and
12.2. To install it:

- **Release zip:** download the zip for your Ghidra version from the
  [Releases page](https://github.com/aleph-void/ghidra-rl78/releases), then in Ghidra use
  *File > Install Extensions*, add the zip, and restart Ghidra.
- **From source:** `cd RL78 && GHIDRA_INSTALL_DIR=/path/to/ghidra_12.1.4_PUBLIC gradle buildExtension`,
  then install the zip from `RL78/dist/` the same way. (Copying `RL78` into
  `<ghidra_dir>/Ghidra/Processors` also still works; Ghidra compiles the `.slaspec` files the first
  time each language is used.)

`gvm extensions install rl78` installs CUB3D's upstream release, not this fork.

# Upstream issues

*Ghidra-Rare:* status of the upstream repository's issues:

- [#1](https://github.com/CUB3D/ghidra-rl78/issues/1) "Slaspec has a typo" (`shl C` carry
  expression) and pull request #2 for the same fix: fixed upstream in 77d1a78, before this
  fork. The instruction set has since been regenerated (`RL78/tools/gen_rl78.py`), so all
  shifts share one carry expression, which the emulator tests check (`shl a`).
- [#3](https://github.com/CUB3D/ghidra-rl78/issues/3) "Errors compiling" on Ghidra 12.1.2: fixed
  upstream in v0.3.0. Both `.slaspec` files compile without errors on 12.1.3, 12.1.4 and 12.2.
  12.1.2 was not tested.
