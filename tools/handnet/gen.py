"""Synthetic handwriting samples for the hand CNN.
Sources: Make Me a Hanzi medians (Arphic PL) and OFL fonts (skeletonised). Output: N x N float32 ink images
normalised exactly like the Rust side (fit bbox into N-2*M, centred)."""
import json, math, random, numpy as np, cv2
cv2.setNumThreads(1)
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont
from skimage.morphology import skeletonize

N = 48; M = 3
import os
# 源数据目录（仓库外，与 data/build.sh 相同的 .ref/）与工作目录（字体、骨架缓存、检查点）。
# Source data (outside git, the same .ref/ as data/build.sh) and the work dir (fonts, skeleton cache, checkpoints).
ROOT = os.environ.get('WEAVE_REF', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '.ref'))
WORK = os.environ.get('HANDNET_WORK', os.path.join(ROOT, 'hwtrain'))
SYMBOLS = list('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz') + \
    list('，。、？！：；“”‘’（）《》…—·@#%&*+-=/~')

def load_medians():
    med = {}
    for l in open(f'{ROOT}/makemeahanzi/graphics.txt'):
        d = json.loads(l)
        med[d['character']] = [[(x, 900 - y) for x, y in s] for s in d['medians']]
    return med

FONT_FILES = ['LXGWWenKai-Regular.ttf', 'NotoSansSC.ttf', 'NotoSerifSC.ttf', 'MaShanZheng-Regular.ttf',
              'ZhiMangXing-Regular.ttf', 'LiuJianMaoCao-Regular.ttf', 'LongCang-Regular.ttf',
              'ZCOOLXiaoWei-Regular.ttf', 'ZCOOLKuaiLe-Regular.ttf']

class Fonts:
    """precomputed skeletons (skel.py): points at 128 px per (font, class)"""
    def __init__(self):
        import os
        d = WORK
        self.pts = np.load(f'{d}/skel_pts.npy', mmap_mode='r')
        idx = np.load(f'{d}/skel_idx.npy')
        chars, _ = classes()
        self.ci = {c: i for i, c in enumerate(chars)}
        self.by = {}
        for fi, ci, a, b in idx: self.by.setdefault(int(ci), []).append((int(a), int(b)))
    def pick(self, rng, ch):
        lst = self.by.get(self.ci.get(ch, -1))
        if not lst: return None
        a, b = lst[int(rng.integers(len(lst)))]
        return np.asarray(self.pts[a:b], np.float32)

def catmull(pts, step):
    """smooth polyline through points, about `step` apart (vectorised Catmull-Rom)"""
    pts = np.asarray(pts, np.float32)
    if len(pts) < 2: return pts
    P = np.concatenate([pts[:1], pts, pts[-1:]])
    p0, p1, p2, p3 = P[:-3], P[1:-2], P[2:-1], P[3:]
    n = np.maximum(1, (np.linalg.norm(p2 - p1, axis=1) / step).astype(int))
    seg = np.repeat(np.arange(len(n)), n)
    start = np.cumsum(n) - n
    t = ((np.arange(n.sum()) - start[seg]) / n[seg]).astype(np.float32)[:, None]
    q0, q1, q2, q3 = p0[seg], p1[seg], p2[seg], p3[seg]
    t2 = t * t; t3 = t2 * t
    out = 0.5 * ((2 * q1) + (-q0 + q2) * t + (2 * q0 - 5 * q1 + 4 * q2 - q3) * t2 + (-q0 + 3 * q1 - 3 * q2 + q3) * t3)
    return np.concatenate([out, pts[-1:]]).astype(np.float32)

def rand_affine(rng, k):
    a = rng.uniform(-0.18, 0.18) * k            # rotation
    sh = rng.uniform(-0.3, 0.3) * k             # slant
    sx = math.exp(rng.uniform(-0.25, 0.25) * k)
    sy = math.exp(rng.uniform(-0.25, 0.25) * k)
    c, s = math.cos(a), math.sin(a)
    A = np.array([[c, -s], [s, c]]) @ np.array([[1, sh], [0, 1]]) @ np.diag([sx, sy])
    return A.astype(np.float32)

def elastic(rng, pts, size, amp):
    """smooth low-frequency displacement"""
    out = pts.copy()
    for _ in range(2):
        fx, fy = rng.uniform(0.5, 2.0, 2) * 2 * math.pi / size
        px, py = rng.uniform(0, 2 * math.pi, 2)
        ax, ay = rng.normal(0, amp, 2)
        out[:, 0] += ax * np.sin(pts[:, 1] * fy + py)
        out[:, 1] += ay * np.sin(pts[:, 0] * fx + px)
    return out

def median_strokes(rng, strokes, k):
    """perturb Make Me a Hanzi medians (1024 box) into sloppy handwriting strokes"""
    S = 1024.0
    out = []
    for s in strokes:
        p = np.array(s, np.float32)
        p += rng.normal(0, 0.012 * S * k, p.shape)                     # control-point jitter
        c = p.mean(0)
        a = rng.uniform(-0.12, 0.12) * k
        sc = math.exp(rng.uniform(-0.15, 0.15) * k)
        R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]], np.float32) * sc
        p = (p - c) @ R.T + c + rng.normal(0, 0.03 * S * k, 2)           # per-stroke placement
        if len(p) >= 2 and rng.random() < 0.5:                          # shorten / extend ends
            d0 = p[1] - p[0]; d1 = p[-1] - p[-2]
            p[0] = p[0] - d0 * rng.uniform(-0.25, 0.2) * k
            p[-1] = p[-1] + d1 * rng.uniform(-0.25, 0.2) * k
        out.append(catmull(p, 20))
    # cursive joins in writing order
    pj = min(0.95, rng.uniform(0, 0.9) * k)
    joined = [out[0]]
    for s in out[1:]:
        prev = joined[-1]
        gap = np.linalg.norm(s[0] - prev[-1])
        if rng.random() < pj and gap < 0.7 * S:
            mid = (prev[-1] + s[0]) / 2 + rng.normal(0, 0.05 * S, 2)
            bridge = catmull(np.stack([prev[-1], mid, s[0]]), 20)
            joined[-1] = np.concatenate([prev, bridge[1:-1], s])
        else:
            joined.append(s)
    # stroke order shuffles barely matter for images; occasionally drop a tiny stroke
    if len(joined) > 3 and rng.random() < 0.04:
        lens = [np.ptp(s, 0).max() for s in joined]
        i = int(np.argmin(lens))
        if lens[i] < 0.12 * S: joined.pop(i)
    return joined

def normalise(strokes, n=N, m=M):
    allp = np.concatenate(strokes)
    lo = allp.min(0); hi = allp.max(0)
    w, h = hi - lo
    s = max(w, h, 1e-3)
    scale = (n - 2 * m) / s
    off = np.array([(n - w * scale) / 2, (n - h * scale) / 2], np.float32)
    return [(st - lo) * scale + off for st in strokes]

def raster(strokes, n=N, r=1.25):
    """analytic coverage: v = clamp(r + 0.5 - distance, 0, 1) to the polyline; pixel centres at i + 0.5"""
    img = np.zeros((n, n), np.float32)
    for st in strokes:
        if len(st) == 1: st = np.concatenate([st, st])
        # decimate: points closer than 0.6 px add almost nothing at this resolution
        keep = [0]
        d = np.linalg.norm(np.diff(st, axis=0), axis=1)
        acc = 0.0
        for i, v in enumerate(d, 1):
            acc += v
            if acc >= 0.6 or i == len(st) - 1: keep.append(i); acc = 0.0
        st = st[keep]
        if len(st) == 1: st = np.concatenate([st, st])
        a = st[:-1]; b = st[1:]
        x0 = int(max(0, np.floor(min(a[:, 0].min(), b[:, 0].min()) - r - 1))); x1 = int(min(n, np.ceil(max(a[:, 0].max(), b[:, 0].max()) + r + 1)))
        y0 = int(max(0, np.floor(min(a[:, 1].min(), b[:, 1].min()) - r - 1))); y1 = int(min(n, np.ceil(max(a[:, 1].max(), b[:, 1].max()) + r + 1)))
        if x1 <= x0 or y1 <= y0: continue
        ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32) + 0.5
        px = xs.ravel()[None]; py = ys.ravel()[None]
        best = np.full(px.shape[1], 1e9, np.float32)
        for k in range(0, len(a), 64):
            aa = a[k:k+64]; bb = b[k:k+64]
            ax = aa[:, 0:1]; ay = aa[:, 1:2]
            dx = bb[:, 0:1] - ax; dy = bb[:, 1:2] - ay
            L = np.maximum(dx * dx + dy * dy, 1e-6)
            t = np.clip(((px - ax) * dx + (py - ay) * dy) / L, 0, 1)
            qx = ax + t * dx - px; qy = ay + t * dy - py
            best = np.minimum(best, (qx * qx + qy * qy).min(0))
        v = np.clip(r + 0.5 - np.sqrt(best), 0, 1).reshape(y1 - y0, x1 - x0)
        img[y0:y1, x0:x1] = np.maximum(img[y0:y1, x0:x1], v)
    return img

def font_sample(rng, fonts, ch, k):
    pts = fonts.pick(rng, ch)
    if pts is None: return None
    pts = pts + rng.normal(0, 0.25, pts.shape)
    return [pts]   # rendered as a dense point cloud (each point a dot)

def sample(rng, ch, med, fonts, n=N):
    k = rng.uniform(0.3, 1.3)                    # sloppiness
    use_font = ch not in med or rng.random() < 0.35
    if use_font:
        st = font_sample(rng, fonts, ch, k)
        if st is None:
            if ch not in med: return None
            use_font = False
    if not use_font:
        st = median_strokes(rng, med[ch], k)
    A = rand_affine(rng, k)
    allp = np.concatenate(st); c = allp.mean(0)
    size = max(np.ptp(allp, 0).max(), 1.0)
    st = [elastic(rng, (s - c) @ A.T, size, 0.025 * size * k) for s in st]
    st = normalise(st, n)
    r = rng.uniform(0.9, 1.7)
    if use_font:
        pts = st[0]
        img = np.zeros((n, n), np.float32)
        # dots: splat with the same coverage function via a small distance transform
        mask = np.zeros((n * 4, n * 4), np.uint8)
        q = np.clip((pts * 4).astype(int), 0, n * 4 - 1)
        mask[q[:, 1], q[:, 0]] = 1
        dist = cv2.distanceTransform((1 - mask).astype(np.uint8), cv2.DIST_L2, 3) / 4.0
        img = cv2.resize(np.clip(r + 0.5 - dist, 0, 1), (n, n), interpolation=cv2.INTER_AREA)
        img = np.clip(img * 1.3, 0, 1)
    else:
        img = raster(st, n, r)
    return img.astype(np.float32)

def real_sample(strokes, n=N):
    st = [np.array(s, np.float32) for s in strokes if len(s)]
    return raster(normalise(st, n), n, 1.25)

def classes():
    med = load_medians()
    chars = list(med.keys()) + [s for s in SYMBOLS if s not in med]
    return chars, med

if __name__ == '__main__':
    import sys, time
    chars, med = classes(); fonts = Fonts()
    rng = np.random.default_rng(0)
    print(len(chars))
    tiles = []
    t = time.time()
    for ch in '我爱中国手写识别输入法永鹰魔0A？':
        row = [sample(rng, ch, med, fonts) for _ in range(8)]
        tiles.append(np.concatenate([r if r is not None else np.zeros((N, N)) for r in row], 1))
    print('ms/sample', (time.time() - t) * 1000 / (len(tiles) * 8))
    cv2.imwrite(os.path.join(WORK, 'gen.png'), (255 - np.concatenate(tiles, 0) * 255).astype(np.uint8))
