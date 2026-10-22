SYNTHETIC DEVELOPMENT FIXTURE - NOT A RECEIPT DATASET

synthetic_manifest.csv describes ten invented receipts (fictional merchants, dates and totals)
used only to test the benchmark manifest validator, scorer and OCR adapter seam. The image files
it names are generated at test time by SyntheticReceiptImages (8x8 pixel patterns with no receipt
content) and are never committed. Any score computed from these rows is a harness check, not an
OCR accuracy result. A real benchmark needs authorized, redacted receipt images and human-written
labels supplied by a person; see RESULTS.md ("Receipt image seam and benchmark contract").
