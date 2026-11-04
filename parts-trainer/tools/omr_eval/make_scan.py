"""Синтетические «сканы» из чистых страниц (для сверки распознавания сканов с эталоном).

    python3 -I tools/omr_eval/make_scan.py <папка> <стр1,стр2,...> <папка-выхода> [вариант ...]

Варианты (по умолчанию — три «скана»; одиночные дефекты — res150, blur, noise, jpeg60, tilt2, light и пары res150_*):
  scan300 — 300 dpi: наклон, лёгкое размытие, шум, неровная яркость бумаги, JPEG q=85;
  scan200 — 200 dpi: то же сильнее, JPEG q=70;
  scan150 — 150 dpi: грубый скан, наклон до 2°, JPEG q=60.
Страницы на входе — PNG шириной ~1920 px (≈230 dpi для A4). Случайность фиксирована (зерно = имя страницы + вариант),
поэтому результат воспроизводим. Файлы на выходе: <стр>_<вариант>.png (PNG без потерь поверх JPEG-артефактов).
"""
import hashlib
import io
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

A4_WIDTH_IN = 8.27
VARIANTS = {
    #          dpi  наклон°  размытие  шум σ  перепад яркости  JPEG
    'scan300': (300, 0.7,    0.6,      6.0,   18,              85),
    'scan200': (200, 1.2,    0.8,      9.0,   28,              70),
    'scan150': (150, 2.0,    1.0,      12.0,  36,              60),
    # по одному дефекту (dpi None — без смены разрешения, JPEG None — без сжатия): что именно мешает распознаванию
    'res150':        (150,  0.0, 0.0, 0.0,  0,  None),
    'res150_blur':   (150,  0.0, 1.0, 0.0,  0,  None),
    'res150_noise':  (150,  0.0, 0.0, 12.0, 0,  None),
    'res150_jpeg':   (150,  0.0, 0.0, 0.0,  0,  60),
    'blur':          (None, 0.0, 1.5, 0.0,  0,  None),
    'noise':         (None, 0.0, 0.0, 12.0, 0,  None),
    'jpeg60':        (None, 0.0, 0.0, 0.0,  0,  60),
    'tilt2':         (None, 2.0, 0.0, 0.0,  0,  None),
    'light':         (None, 0.0, 0.0, 0.0,  36, None),
}
SCANS = ['scan300', 'scan200', 'scan150']


def make(src: Image.Image, name: str, variant: str) -> Image.Image:
    dpi, max_angle, blur, noise, light, q = VARIANTS[variant]
    seed = int.from_bytes(hashlib.sha256(f'{name}:{variant}'.encode()).digest()[:4], 'little')
    rng = np.random.default_rng(seed)
    img = src.convert('L')
    if dpi is not None:
        w = int(round(A4_WIDTH_IN * dpi))
        img = img.resize((w, int(round(img.height * w / img.width))), Image.LANCZOS)
    angle = float(rng.uniform(-max_angle, max_angle))
    if max_angle > 0:
        img = img.rotate(angle, resample=Image.BICUBIC, expand=False, fillcolor=255)
    if blur > 0:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(img).astype(np.float32)
    h, ww = a.shape
    # неровная яркость: плавный наклон плоскости + затемнение к краю (как у корешка книги)
    yy, xx = np.mgrid[0:h, 0:ww].astype(np.float32)
    gx, gy = rng.uniform(-1, 1, 2)
    shade = light * (0.5 * (gx * xx / ww + gy * yy / h) + 0.5 * (xx / ww) ** 4)
    a = a * (1 - shade / 255.0) - shade * 0.15
    if noise > 0:
        a += rng.normal(0, noise, a.shape).astype(np.float32)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    if q is None:
        return img
    buf = io.BytesIO()
    img.save(buf, 'JPEG', quality=q)
    buf.seek(0)
    return Image.open(buf).convert('L')


def main():
    src_dir, names, out_dir = sys.argv[1], sys.argv[2].split(','), sys.argv[3]
    variants = sys.argv[4:] or SCANS
    os.makedirs(out_dir, exist_ok=True)
    for v in variants:
        for n in names:
            out = make(Image.open(os.path.join(src_dir, n + '.png')), n, v)
            out.save(os.path.join(out_dir, f'{n}_{v}.png'))
            print(f'{n}_{v}.png {out.size[0]}x{out.size[1]}')


if __name__ == '__main__':
    main()
