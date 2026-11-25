import os
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

SRC_JPG = r"C:\Users\acer\.gemini\antigravity\brain\362581f9-bd75-4cca-aa27-c59fda6c3e48\shadow_luna_icon_2026_v2_1795576843016.jpg"
ROOT_DIR = r"c:\Users\acer\Downloads\my purpose\Ai"
SERVER_DIR = os.path.join(ROOT_DIR, "LunaServer")
RES_DIR = os.path.join(ROOT_DIR, "DeviceMemory", "app", "src", "main", "res")


def make_squircle_icon(img: Image.Image, size: int = 512) -> Image.Image:
    base = img.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    if size <= 48:
        base = base.filter(ImageFilter.UnsharpMask(radius=0.8, percent=120, threshold=2))
        base = ImageEnhance.Contrast(base).enhance(1.12)
    # Supersampled 4x rounded-squircle alpha mask for razor-sharp edges
    mask_big = Image.new("L", (size * 4, size * 4), 0)
    md = ImageDraw.Draw(mask_big)
    radius = int(size * 4 * 0.225)
    md.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=radius, fill=255)
    mask = mask_big.resize((size, size), Image.Resampling.LANCZOS)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)

    # Subtle 1px inner glass rim highlight so icon pops on both dark & light themes
    rim_big = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim_big)
    rd.rounded_rectangle(
        [2, 2, size * 4 - 3, size * 4 - 3],
        radius=radius,
        outline=(165, 225, 255, 48),
        width=max(4, size // 56),
    )
    rim = rim_big.resize((size, size), Image.Resampling.LANCZOS)
    return Image.alpha_composite(out, rim)


def make_round_icon(img: Image.Image, size: int = 512) -> Image.Image:
    base = img.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask_big = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask_big).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask_big.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out


def make_adaptive_foreground(img: Image.Image, size: int = 432) -> Image.Image:
    # Adaptive icons display the center 66% safe zone across circle/squircle/teardrop masks.
    # Size glyph container at 78% and softly fade only the outer obsidian rim.
    w, h = img.size
    m = int(w * 0.11)
    safe_crop = img.crop((m, m, w - m, h - m))
    cropped_size = int(size * 0.78)
    glyph = safe_crop.resize((cropped_size, cropped_size), Image.Resampling.LANCZOS).convert("RGBA")
    cx, cy = cropped_size / 2.0, cropped_size / 2.0
    r_solid = cropped_size * 0.40
    r_fade = cropped_size * 0.495

    px = glyph.load()
    for y in range(cropped_size):
        dy = y - cy
        for x in range(cropped_size):
            dx = x - cx
            dist = (dx * dx + dy * dy) ** 0.5
            if dist <= r_solid:
                continue
            r, g, b, _ = px[x, y]
            if dist >= r_fade:
                alpha = 0
            else:
                t = (dist - r_solid) / (r_fade - r_solid)
                alpha = int((1.0 - t * t) * 255)
            px[x, y] = (r, g, b, alpha)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - cropped_size) // 2
    canvas.paste(glyph, (offset, offset), glyph)
    return canvas


def main():
    raw = Image.open(SRC_JPG).convert("RGB")
    bg_rgb = raw.getpixel((10, 10))
    bg_hex = "#{:02X}{:02X}{:02X}".format(*bg_rgb)
    print(f"Sampled 2026 obsidian background color: {bg_rgb} ({bg_hex})")

    # Tighter crop (14% margin) so the sculpted prism S-portal fills squircles & 16px taskbar icon boldly
    w, h = raw.size
    margin = int(w * 0.14)
    bold_crop = raw.crop((margin, margin, w - margin, h - margin))

    # 1. Master 1024 PNG & Server Static PNG
    master_1024 = make_squircle_icon(bold_crop, 1024)
    master_path = os.path.join(ROOT_DIR, "shadow_luna_logo.png")
    master_1024.save(master_path, "PNG")
    print(f"Saved master modern PNG: {master_path}")

    static_dir = os.path.join(SERVER_DIR, "static")
    os.makedirs(static_dir, exist_ok=True)
    master_1024.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(static_dir, "logo.png"), "PNG")

    # 2. Windows App Multi-Size ICO (individually tuned small sizes for razor-sharp taskbar rendering)
    ico_path = os.path.join(SERVER_DIR, "shadow_luna.ico")
    win_256 = make_squircle_icon(bold_crop, 256)
    win_256.save(
        ico_path,
        format="ICO",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
    )
    print(f"Saved Windows modern ICO: {ico_path}")

    # 3. Android Adaptive Foreground PNG (432x432 in drawable-nodpi), In-App Mark & Mipmaps
    nodpi_dir = os.path.join(RES_DIR, "drawable-nodpi")
    os.makedirs(nodpi_dir, exist_ok=True)
    fg_432 = make_adaptive_foreground(raw, 432)
    fg_432.save(os.path.join(nodpi_dir, "ic_launcher_foreground.png"), "PNG")
    master_1024.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(nodpi_dir, "shadow_brand_mark.png"), "PNG")

    # Remove old flat vector foreground XML if present
    old_fg_xml = os.path.join(RES_DIR, "drawable", "ic_launcher_foreground.xml")
    if os.path.exists(old_fg_xml):
        os.remove(old_fg_xml)
        print("Removed old flat vector ic_launcher_foreground.xml")

    # Update legacy mipmap densities cleanly
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

    # Update colors.xml with exact sampled obsidian background + 2026 palette
    colors_xml = os.path.join(RES_DIR, "values", "colors.xml")
    with open(colors_xml, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <!-- Shadow & Luna 2026 Spatial Obsidian Glass Palette -->\n'
            f'    <color name="ic_launcher_background">{bg_hex}</color>\n'
            '    <color name="ic_launcher_foreground">#38BDF8</color>\n'
            '    <color name="luna_violet">#7C3AED</color>\n'
            '    <color name="luna_cyan">#38BDF8</color>\n'
            f'    <color name="shadow_dark">{bg_hex}</color>\n'
            '    <color name="shadow_surface">#10101C</color>\n'
            '</resources>\n'
        )
    print(f"Updated Android colors.xml, shadow_brand_mark.png & adaptive mipmaps with bg {bg_hex}!")


if __name__ == "__main__":
    main()
