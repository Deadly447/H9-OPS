#!/usr/bin/env python3
"""Make H9 OPS firmware P2 from the official TD-H9 V1.0.38 firmware.

    python3 patch_p2.py TD-H9-V1.0.38.fw

Writes TD-H9-V1.0.38.P2.fw next to it. Works offline. The official file is
checked first (size and SHA-256) and the result is checked again, so a wrong
or damaged input never produces a firmware file.
"""
import hashlib
import json
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = Path(sys.argv[1])
    patch = json.loads((Path(__file__).with_name("p2-patch.json")).read_text())
    data = bytearray(src.read_bytes())
    if len(data) != patch["input_size"] or hashlib.sha256(data).hexdigest() != patch["input_sha256"]:
        sys.exit(f"{src.name} isn't the official TD-H9 V1.0.38 firmware (size or SHA-256 differs).\n"
                 f"Expected SHA-256 {patch['input_sha256']}. Nothing was written.")
    for offset, hexbytes in patch["runs"]:
        new = bytes.fromhex(hexbytes)
        data[offset:offset + len(new)] = new
    if hashlib.sha256(data).hexdigest() != patch["output_sha256"]:
        sys.exit("The patched file didn't come out as expected. Nothing was written.")
    out = src.with_name(patch["output_name"])
    out.write_bytes(data)
    print(f"Wrote {out} (SHA-256 {patch['output_sha256']})")


if __name__ == "__main__":
    main()
