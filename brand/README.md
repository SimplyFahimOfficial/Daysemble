# Brand assets

The supplied originals, kept unmodified:

| File | Variant |
|---|---|
| `Logo1.png` | **Classic**, cream tile (the default) |
| `Logo2.png` | Midnight, black tile with filled shapes |
| `Logo3.png` | Outline, black tile with outlined shapes |
| `Wordlogo.png` | Horizontal "Daysemble." wordmark |

To regenerate the app resources after changing a file, run this from the repo root:

```bash
pip install pillow
python3 scripts/import_logos.py --classic brand/Logo1.png --midnight brand/Logo2.png \
  --outline brand/Logo3.png --wordmark brand/Wordlogo.png
```

The script removes only the black square outside each tile; the artwork isn't recoloured or redrawn.
It writes 512 px copies for in-app use and adaptive launcher icons sized to the 66dp safe zone.
