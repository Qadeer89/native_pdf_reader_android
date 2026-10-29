"""Generate a minimal, legally-usable single-page PDF test fixture.

Hand-built (no third-party PDF library) so there is no ambiguity about
provenance or licensing of the fixture content itself. Produces a single
US-Letter page (612x792 pt) with a short text string, suitable for the
Phase 1 vertical-slice smoke test (open + render page 1).
"""
import pathlib

OUT_PATH = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "fixtures" / "sample.pdf"

content_stream = b"BT /F1 24 Tf 72 700 Td (PDF Reader Smoke Test Fixture) Tj ET"

objects = []
objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
objects.append(
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
    b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
)
objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
objects.append(
    b"<< /Length " + str(len(content_stream)).encode() + b" >>\nstream\n"
    + content_stream + b"\nendstream"
)

pdf = bytearray()
pdf += b"%PDF-1.4\n"
offsets = [0]  # object 0 is free, placeholder
for i, obj in enumerate(objects, start=1):
    offsets.append(len(pdf))
    pdf += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"

xref_offset = len(pdf)
num_objs = len(objects) + 1
pdf += f"xref\n0 {num_objs}\n".encode()
pdf += b"0000000000 65535 f \n"
for off in offsets[1:]:
    pdf += f"{off:010d} 00000 n \n".encode()

pdf += b"trailer\n"
pdf += f"<< /Size {num_objs} /Root 1 0 R >>\n".encode()
pdf += b"startxref\n"
pdf += f"{xref_offset}\n".encode()
pdf += b"%%EOF\n"

OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
OUT_PATH.write_bytes(bytes(pdf))
print(f"Wrote {OUT_PATH} ({len(pdf)} bytes)")
