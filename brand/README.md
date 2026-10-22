# Brand assets

Put the supplied files here, then run (from the repo root):

```bash
python3 scripts/import_logos.py \
  --classic  brand/logo1.png \   # supplied logo #1, light/cream tile, the DEFAULT
  --midnight brand/logo2.png \   # supplied logo #2, black tile, filled shapes
  --outline  brand/logo3.png \   # supplied logo #3, black tile, outlined shapes
  --wordmark brand/wordmark.png
```

The script keeps the originals here (`*_original.png`), removes only the black square
outside each tile, and writes resized copies for the app (512 px) and the adaptive launcher icons.
