import math
import shutil
from pathlib import Path
from PIL import Image, ImageDraw

res = Path("app/src/main/res")
for p in Path("app/src").rglob("*ic_launcher*"):
    if p.is_file():
        p.unlink()
shutil.rmtree(res / "mipmap-anydpi", ignore_errors=True)

for sub in ["drawable", "mipmap-anydpi-v26", "values", "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]:
    (res / sub).mkdir(parents=True, exist_ok=True)

ns = "http://" + "schemas.android.com" + "/apk/res/android"
(res / "values" / "colors.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#191C22</color>\n</resources>\n',
    encoding="utf-8",
)
adaptive_xml = (
    f'<?xml version="1.0" encoding="utf-8"?>\n'
    f'<adaptive-icon xmlns:android="{ns}">\n'
    f'    <background android:drawable="@color/ic_launcher_background"/>\n'
    f'    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
    f'</adaptive-icon>\n'
)
(res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(adaptive_xml, encoding="utf-8")
(res / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(adaptive_xml, encoding="utf-8")

def draw_exact_pc_star(size: int, scale: float, bg_hex: str | None) -> Image.Image:
    ss = size * 4
    img = Image.new("RGBA", (ss, ss), bg_hex if bg_hex else (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    s = ss * scale
    ox = (ss - s) * 0.5
    oy = (ss - s) * 0.5
    cx = ox + s * 0.5
    cy = oy + s * 0.52
    outer_r = s * 0.43
    inner_r = s * 0.24

    pts = []
    for i in range(10):
        angle = -math.pi / 2.0 + i * (math.pi / 5.0)
        rad = outer_r if i % 2 == 0 else inner_r
        pts.append((cx + rad * math.cos(angle), cy + rad * math.sin(angle)))

    poly = []
    n = len(pts)
    steps = 16
    for i in range(n):
        p0 = pts[(i - 1) % n]
        p1 = pts[i]
        p2 = pts[(i + 1) % n]
        in_pt = (p1[0] * 0.72 + p0[0] * 0.28, p1[1] * 0.72 + p0[1] * 0.28)
        out_pt = (p1[0] * 0.72 + p2[0] * 0.28, p1[1] * 0.72 + p2[1] * 0.28)
        for st in range(steps + 1):
            t = st / steps
            mt = 1.0 - t
            bx = mt * mt * in_pt[0] + 2 * mt * t * p1[0] + t * t * out_pt[0]
            by = mt * mt * in_pt[1] + 2 * mt * t * p1[1] + t * t * out_pt[1]
            poly.append((bx, by))

    mask = Image.new("L", (ss, ss), 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)

    grad = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(grad)
    y_top = int(oy + s * 0.1)
    y_bot = int(oy + s * 0.9)
    for y in range(ss):
        t = max(0.0, min(1.0, (y - y_top) / max(1, y_bot - y_top)))
        r = int(255 * (1 - t) + 227 * t)
        g = int(248 * (1 - t) + 192 * t)
        b = int(70 * (1 - t) + 54 * t)
        gdraw.line([(0, y), (ss, y)], fill=(r, g, b, 255))

    img.paste(grad, (0, 0), mask)
    stroke_w = max(2, int(round(s * 0.055)))
    draw.line(poly + [poly[0]], fill=(170, 144, 40, 255), width=stroke_w, joint="curve")

    eye_w = s * 0.075
    eye_h = s * 0.19
    eye_y = cy - s * 0.09
    sh_w = eye_w * 0.45
    for ex, sh_x in ((cx - s * 0.115, cx - s * 0.105), (cx + s * 0.040, cx + s * 0.050)):
        draw.rounded_rectangle(
            [ex, eye_y, ex + eye_w, eye_y + eye_h],
            radius=eye_w * 0.5,
            fill=(17, 19, 21, 255),
        )
        draw.ellipse(
            [sh_x, eye_y + eye_h * 0.16, sh_x + sh_w, eye_y + eye_h * 0.16 + sh_w],
            fill=(255, 255, 255, 225),
        )

    return img.resize((size, size), Image.Resampling.LANCZOS)

draw_exact_pc_star(432, 0.58, None).save(res / "drawable" / "ic_launcher_foreground.png")
for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    ic = draw_exact_pc_star(px, 0.70, "#191C22")
    ic.save(res / folder / "ic_launcher.png")
    ic.save(res / folder / "ic_launcher_round.png")

print("SUCCESS: Exact 1:1 PC Mario Star icons generated!")
