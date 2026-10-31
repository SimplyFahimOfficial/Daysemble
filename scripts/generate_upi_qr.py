#!/usr/bin/env python3
"""Generates the UPI QR code shown in the README's "Support development" section, then decodes it
to check the payload. Runs offline; no QR web service is used.

    pip install "qrcode[pil]" zxing-cpp
    python3 scripts/generate_upi_qr.py

The URI has no amount (`am`), so the payer chooses how much to send.
"""
import sys
from pathlib import Path

import qrcode
import zxingcpp
from PIL import Image

UPI_URI = "upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR"
OUT = Path(__file__).resolve().parent.parent / "docs/assets/support/upi-qr.png"


def main() -> int:
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=16, border=4)
    qr.add_data(UPI_URI)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, optimize=True)

    # Check the saved file, at full size and at the size the README displays it.
    saved = Image.open(OUT)
    for test in (saved, saved.resize((200, 200), Image.LANCZOS)):
        results = zxingcpp.read_barcodes(test)
        if len(results) != 1 or results[0].text != UPI_URI:
            print(f"Decoded {[r.text for r in results]!r}, expected {UPI_URI!r}", file=sys.stderr)
            return 1
    print(f"{OUT.relative_to(Path.cwd()) if OUT.is_relative_to(Path.cwd()) else OUT}: {saved.size[0]} px, decodes to {UPI_URI}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
