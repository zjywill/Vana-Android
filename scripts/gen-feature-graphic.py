#!/usr/bin/env python3
# Google Play「置顶大图」1024x500。用法:python3 scripts/gen-feature-graphic.py docs/store/zh-CN/feature-graphic.png
# 颜色取自 ui/theme/Color.kt(BrandBlue #3570CF / background #F7F9FD / onBackground #1E2635),
# 图标直接用 mipmap-xxxhdpi 的 ic_launcher_foreground.png,不另存一份。
# 文字右边收在 x<=948:Play 在部分展示位会裁掉边缘。

from PIL import Image, ImageDraw, ImageFont, ImageFilter
import sys

W, H = 1024, 500
BG_TOP, BG_BOT = (233, 241, 253), (250, 252, 255)
INK = (30, 38, 53)
DIM = (90, 110, 140)
BLUE = (53, 112, 207)
SOFT = (220, 232, 251)

HIRA = "/System/Library/Fonts/Hiragino Sans GB.ttc"
SF = "/System/Library/Fonts/SFNS.ttf"

def sf(size, weight="Bold"):
    f = ImageFont.truetype(SF, size)
    try: f.set_variation_by_name(weight)
    except Exception: pass
    return f

def hira(size, bold=False):
    return ImageFont.truetype(HIRA, size, index=2 if bold else 0)

# --- background: vertical gradient ---
img = Image.new("RGB", (W, H), BG_BOT)
d = ImageDraw.Draw(img)
for y in range(H):
    t = y / (H - 1)
    d.line([(0, y), (W, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOT)))

# --- soft decorative circles (drawn on an overlay, blurred) ---
ov = Image.new("RGB", (W, H))
ovd = ImageDraw.Draw(ov)
ovd.rectangle([0, 0, W, H], fill=(0, 0, 0))
ovd.ellipse([760, -170, 1180, 250], fill=(255, 255, 255))
ovd.ellipse([600, 330, 900, 630], fill=(120, 120, 120))
ov = ov.filter(ImageFilter.GaussianBlur(60))
img = Image.composite(Image.new("RGB", (W, H), (255, 255, 255)), img, ov.convert("L").point(lambda v: int(v * 0.55)))

d = ImageDraw.Draw(img)

# --- icon, left ---
ICON = 300
icon = Image.open("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png").convert("RGBA")
icon = icon.resize((ICON, ICON), Image.LANCZOS)
ix, iy = 96, (H - ICON) // 2
# soft shadow under the mark
sh = Image.new("L", (W, H), 0)
ImageDraw.Draw(sh).ellipse([ix + 60, iy + 210, ix + ICON - 60, iy + 260], fill=70)
sh = sh.filter(ImageFilter.GaussianBlur(22))
img.paste(Image.new("RGB", (W, H), (150, 175, 210)), (0, 0), sh)
img.paste(icon, (ix, iy), icon)

# --- text block, right ---
# 右边留出安全边距:Play 在部分位置会裁掉边缘,文字一律收在 x <= 948 以内
x, RIGHT = 400, 948

def fit(text, mk, size):
    """从 size 起逐档缩小,直到这一行落在安全区里。"""
    while size > 12:
        f = mk(size)
        if d.textlength(text, font=f) <= RIGHT - x:
            return f
        size -= 1
    return mk(12)

d.text((x, 198), "Vana", font=fit("Vana", lambda s: sf(s, "Bold"), 100), fill=INK, anchor="ls")
d.text((x, 272), "拍下化验单，就能聊的健康助手",
       font=fit("拍下化验单，就能聊的健康助手", lambda s: hira(s, bold=True), 42), fill=INK, anchor="ls")
d.text((x, 330), "本机识别 · 自备 API key · 无账号 · 无订阅",
       font=fit("本机识别 · 自备 API key · 无账号 · 无订阅", lambda s: hira(s), 27), fill=DIM, anchor="ls")

# --- accent rule ---
d.rounded_rectangle([x, 364, x + 84, 371], radius=4, fill=BLUE)

img.save(sys.argv[1], "PNG", optimize=True)
print("ok")
