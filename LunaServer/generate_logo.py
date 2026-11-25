import os
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

SRC_JPG = r"C:\Users\acer\.gemini\antigravity\brain\362581f9-bd75-4cca-aa27-c59fda6c3e48\shadow_luna_icon_2026_v2_1795576843016.jpg"
ROOT_DIR = r"c:\Users\acer\Downloads\my purpose\Ai"
SERVER_DIR = os.path.join(ROOT_DIR, "LunaServer")
RES_DIR = os.path.join(ROOT_DIR, "DeviceMemory", "app", "src", "main", "res")


def boost_obsidian_prism(img: Image.Image) -> Image.Image:
    """Slightly lift midtones & contrast so metallic obsidian + neon cyan/violet read crisply at small sizes."""
    rgb = ImageEnhance.Color(img.convert("RGB")).enhance(1.16)
    rgb = ImageEnhance.Contrast(rgb).enhance(1.10)
    return ImageEnhance.Brightness(rgb).enhance(1.08)


def make_squircle_icon(img: Image.Image, size: int = 512) -> Image.Image:
    base = boost_obsidian_prism(img).resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    if size <= 64:
        base = base.filter(ImageFilter.UnsharpMask(radius=0.8, percent=135, threshold=2))
    # Supersampled 4x rounded-squircle alpha mask
    mask_big = Image.new("L", (size * 4, size * 4), 0)
    md = ImageDraw.Draw(mask_big)
    radius = int(size * 4 * 0.225)
    md.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=radius, fill=255)
    mask = mask_big.resize((size, size), Image.Resampling.LANCZOS)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)

    # Crisp glass outer rim so dark icon stands out cleanly even on pitch-black #000000 backgrounds
    rim_big = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim_big)
    rd.rounded_rectangle(
        [3, 3, size * 4 - 4, size * 4 - 4],
        radius=radius,
        outline=(90, 200, 255, 95),
        width=max(6, size // 36),
    )
    rim = rim_big.resize((size, size), Image.Resampling.LANCZOS)
    return Image.alpha_composite(out, rim)


def make_round_icon(img: Image.Image, size: int = 512) -> Image.Image:
    base = boost_obsidian_prism(img).resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask_big = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask_big).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask_big.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)

    rim_big = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    ImageDraw.Draw(rim_big).ellipse(
        [3, 3, size * 4 - 4, size * 4 - 4],
        outline=(90, 200, 255, 95),
        width=max(6, size // 36),
    )
    rim = rim_big.resize((size, size), Image.Resampling.LANCZOS)
    return Image.alpha_composite(out, rim)


def make_adaptive_foreground(img: Image.Image, size: int = 432) -> Image.Image:
    """
    Android 108dp adaptive icon spec: center 66% (72dp) is always visible across squircle/circle masks.
    Remove only the outer dark flat background by smooth chroma+luminance matte outside the glyph,
    while preserving the entire center obsidian prism and center glowing cyan dot 100% opaque.
    """
    w, h = img.size
    m = int(w * 0.14)
    tight_crop = boost_obsidian_prism(img.crop((m, m, w - m, h - m)))

    cropped_size = int(size * 0.66)
    glyph = tight_crop.resize((cropped_size, cropped_size), Image.Resampling.LANCZOS).convert("RGBA")
    cx, cy = cropped_size / 2.0, cropped_size / 2.0
    r_inner_protect = cropped_size * 0.24  # protects central fold + cyan dot
    r_outer_cut = cropped_size * 0.50

    px = glyph.load()
    for y in range(cropped_size):
        dy = y - cy
        for x in range(cropped_size):
            dx = x - cx
            dist = (dx * dx + dy * dy) ** 0.5
            r, g, b, _ = px[x, y]
            if dist >= r_outer_cut:
                px[x, y] = (r, g, b, 0)
                continue
            # Circular soft vignette fade around outer edge so it blends into adaptive background cleanly
            radial_alpha = 1.0
            r_fade_start = cropped_size * 0.42
            if dist > r_fade_start:
                t = (dist - r_fade_start) / (r_outer_cut - r_fade_start)
                radial_alpha = max(0.0, 1.0 - t * t)

            # Outside the inner protected core, also softly fade pure pitch-black background pixels (#06050B)
            lum = max(r, g, b)
            bg_alpha = 1.0
            if dist > r_inner_protect and lum < 20:
                bg_alpha = max(0.0, (lum - 10) / 10.0)

            alpha = int(min(radial_alpha, bg_alpha) * 255)
            px[x, y] = (r, g, b, alpha)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - cropped_size) // 2
    canvas.paste(glyph, (offset, offset), glyph)
    return canvas


def main():
    raw = Image.open(SRC_JPG).convert("RGB")
    bg_hex = "#0C0D1A"
    print(f"Generating 2026 logo assets cleanly (adaptive bg: {bg_hex})...")

    w, h = raw.size
    margin = int(w * 0.14)
    bold_crop = raw.crop((margin, margin, w - margin, h - margin))

    # 1. Master 1024 PNG & Server Static PNG
    master_1024 = make_squircle_icon(bold_crop, 1024)
    master_path = os.path.join(ROOT_DIR, "shadow_luna_logo.png")
    master_1024.save(master_path, "PNG")

    static_dir = os.path.join(SERVER_DIR, "static")
    os.makedirs(static_dir, exist_ok=True)
    master_1024.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(static_dir, "logo.png"), "PNG")

    # 2. Windows App Multi-Size ICO
    ico_path = os.path.join(SERVER_DIR, "shadow_luna.ico")
    win_256 = make_squircle_icon(bold_crop, 256)
    win_256.save(
        ico_path,
        format="ICO",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
    )

    # 3. Android In-App Mark & Density-Scaled Adaptive Foregrounds (108dp per DPI)
    nodpi_dir = os.path.join(RES_DIR, "drawable-nodpi")
    os.makedirs(nodpi_dir, exist_ok=True)
    master_1024.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(nodpi_dir, "shadow_brand_mark.png"), "PNG")

    # Remove old conflicted/single-bucket foregrounds so DPI folders resolve unambiguously
    for stale in [
        os.path.join(RES_DIR, "drawable", "ic_launcher_foreground.xml"),
        os.path.join(nodpi_dir, "ic_launcher_foreground.png"),
    ]:
        if os.path.exists(stale):
            os.remove(stale)
            print(f"Removed stale {stale}")

    fg_master = make_adaptive_foreground(raw, 432)
    for folder, px in [
        ("drawable-mdpi", 108),
        ("drawable-hdpi", 162),
        ("drawable-xhdpi", 216),
        ("drawable-xxhdpi", 324),
        ("drawable-xxxhdpi", 432),
    ]:
        fpath = os.path.join(RES_DIR, folder)
        os.makedirs(fpath, exist_ok=True)
        fg_master.resize((px, px), Image.Resampling.LANCZOS).save(os.path.join(fpath, "ic_launcher_foreground.png"), "PNG")

    # Legacy Mipmaps
    for folder, px in [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]:
        fpath = os.path.join(RES_DIR, folder)
        os.makedirs(fpath, exist_ok=True)
        make_squircle_icon(bold_crop, px).save(os.path.join(fpath, "ic_launcher.png"), "PNG")
        make_round_icon(bold_crop, px).save(os.path.join(fpath, "ic_launcher_round.png"), "PNG")

    # Update colors.xml cleanly (NO ic_launcher_foreground color to avoid any resource ambiguity)
    colors_xml = os.path.join(RES_DIR, "values", "colors.xml")
    with open(colors_xml, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <!-- Shadow & Luna 2026 Spatial Obsidian Glass Palette -->\n'
            f'    <color name="ic_launcher_background">{bg_hex}</color>\n'
            '    <color name="luna_violet">#7C3AED</color>\n'
            '    <color name="luna_cyan">#38BDF8</color>\n'
            '    <color name="shadow_dark">#06050B</color>\n'
            '    <color name="shadow_surface">#10101C</color>\n'
            '</resources>\n'
        )
    print("All 2026 Windows & Android adaptive icon assets generated cleanly!")


if __name__ == "__main__":
    main()
