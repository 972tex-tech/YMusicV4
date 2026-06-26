#!/usr/bin/env python3
"""
Update PSL DAFSA data from publicsuffix.org

Usage: python update-psl-data.py
"""

import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
CORE_DIR = SCRIPT_DIR.parent  # kmpHttp/core
OUTPUT_FILE = CORE_DIR / "src/nativeMain/kotlin/kmphttp/internal/publicsuffix/PslDafsa.kt"
PSL_FILE = CORE_DIR / "src/nativeTest/resources/public_suffix_list.dat"
PSL_URL = "https://publicsuffix.org/list/public_suffix_list.dat"

def main():
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        psl_file = PSL_FILE
        dafsa_file = tmpdir / "psl.dafsa"

        # Download PSL
        print(f"Downloading {PSL_URL}...")
        result = subprocess.run(
            ["curl", "-sL", PSL_URL, "-o", str(psl_file)],
            check=True
        )

        # Generate DAFSA binary (ASCII mode - stores punycode only, not unicode)
        print("Generating DAFSA binary...")
        result = subprocess.run(
            ["python3", str(SCRIPT_DIR / "psl-make-dafsa.py"),
             "--output-format=binary", "--encoding=ascii",
             str(psl_file), str(dafsa_file)],
            check=True
        )

        # Read binary data
        data = dafsa_file.read_bytes()
        print(f"DAFSA size: {len(data)} bytes")

        # Generate Kotlin file
        print(f"Writing {OUTPUT_FILE}...")
        with open(OUTPUT_FILE, "w") as f:
            f.write(f"""/*
 * Public Suffix List DAFSA data
 * Generated from: {PSL_URL}
 * Generated on: {datetime.now().strftime("%Y-%m-%d")}
 *
 * DAFSA format from Chromium project (BSD license)
 * See: https://github.com/rockdaboot/libpsl
 */
package kmphttp.internal.publicsuffix

/**
 * Embedded DAFSA (Directed Acyclic Finite State Automaton) for Public Suffix List.
 *
 * Encoding: ASCII (punycode only, no unicode)
 * - Unicode domains like "bücher.com" are stored as punycode "xn--bcher-kva.com"
 * - Callers must convert unicode domains to punycode before lookup
 *
 * Size: {len(data)} bytes
 */
internal val PSL_DAFSA: ByteArray = byteArrayOf(
""")
            # Write bytes in chunks of 20
            line = "    "
            for i, byte in enumerate(data):
                signed = byte if byte < 128 else byte - 256
                line += f"{signed},"
                if (i + 1) % 20 == 0:
                    f.write(line + "\n")
                    line = "    "

            if line.strip():
                f.write(line.rstrip(",") + "\n")

            f.write(")\n")

        print("Done!")


if __name__ == "__main__":
    main()
