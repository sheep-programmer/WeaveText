"""Precompute font glyph skeletons (128 px) for all classes: skel_pts.npy (uint8 x,y) + skel_idx.npy (font, class, start, end)."""
import numpy as np, multiprocessing as mp
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from skimage.morphology import skeletonize
import gen
chars, _ = gen.classes()
def work(fi):
    p = f'{gen.WORK}/fonts/{gen.FONT_FILES[fi]}'
    cmap = TTFont(p, lazy=True).getBestCmap()
    font = ImageFont.truetype(p, 100)
    pts, idx = [], []
    for ci, ch in enumerate(chars):
        if ord(ch) not in cmap: continue
        img = Image.new('L', (128, 128), 0)
        ImageDraw.Draw(img).text((64, 64), ch, fill=255, font=font, anchor='mm')
        a = np.array(img) > 127
        if a.sum() < 3: continue
        ys, xs = np.nonzero(skeletonize(a))
        if len(xs) < 2: continue
        idx.append((fi, ci, len(xs)))
        pts.append(np.stack([xs, ys], 1).astype(np.uint8))
    return idx, pts
if __name__ == '__main__':
    with mp.Pool(9) as pool:
        res = pool.map(work, range(len(gen.FONT_FILES)))
    allp, allidx, off = [], [], 0
    for idx, pts in res:
        for (fi, ci, n), p in zip(idx, pts):
            allidx.append((fi, ci, off, off + n)); off += n; allp.append(p)
    np.save(f'{gen.WORK}/skel_pts.npy', np.concatenate(allp)); np.save(f'{gen.WORK}/skel_idx.npy', np.array(allidx, np.int64))
    print(len(allidx), off)
