import math, time, sys, os, numpy as np, torch, torch.nn as nn, torch.nn.functional as F
import gen

# 可选的真人笔迹评测集（handeval 格式，不随仓库提供）。 Optional real-ink eval set (handeval format, not shipped).
EVAL = os.environ.get('HANDNET_EVAL', os.path.join(gen.WORK, 'eval_real.txt'))

CH = [32, 64, 128, 256]
class Net(nn.Module):
    def __init__(self, ncls, ch=CH):
        super().__init__()
        c0, c1, c2, c3 = ch
        def cbr(i, o): return [nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True)]
        self.f = nn.Sequential(*cbr(1, c0), nn.MaxPool2d(2),
                               *cbr(c0, c1), *cbr(c1, c1), nn.MaxPool2d(2),
                               *cbr(c1, c2), *cbr(c2, c2), nn.MaxPool2d(2),
                               *cbr(c2, c3), *cbr(c3, c3), nn.MaxPool2d(2))
        self.drop = nn.Dropout(0.2)
        self.fc = nn.Linear(c3, ncls)
    def forward(self, x):
        x = self.f(x)
        x = x.mean((2, 3))
        return self.fc(self.drop(x))

_W = {}
def _winit(seed):
    import os
    torch.set_num_threads(1)
    _W['rng'] = np.random.default_rng(seed + os.getpid() * 7919)
    _W['chars'], _W['med'] = gen.classes(); _W['fonts'] = gen.Fonts()

def _batch(bs):
    """one batch as uint8 (0..255) images + int64 labels; plain pickling, no shared memory"""
    rng, chars, med, fonts = _W['rng'], _W['chars'], _W['med'], _W['fonts']
    xs = np.empty((bs, gen.N, gen.N), np.uint8); ys = np.empty(bs, np.int64); i = 0
    while i < bs:
        y = int(rng.integers(len(chars)))
        img = gen.sample(rng, chars[y], med, fonts)
        if img is None: continue
        xs[i] = np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8); ys[i] = y; i += 1
    return xs, ys

def batches(bs, workers, seed):
    """endless batches from a process pool (macOS torch shm manager times out under load, so avoid it)"""
    import multiprocessing as mp, itertools
    pool = mp.get_context('spawn').Pool(workers, initializer=_winit, initargs=(seed,))
    for xs, ys in pool.imap(_batch, itertools.repeat(bs), chunksize=1):
        yield torch.from_numpy(xs).float().div_(255.0)[:, None], torch.from_numpy(ys)

def load_eval(chars, limit=None):
    idx = {c: i for i, c in enumerate(chars)}
    xs, ys = [], []
    for i, l in enumerate(open(EVAL)):
        if limit and i >= limit: break
        ch, ink = l.rstrip('\n').split('\t')
        if ch not in idx: continue
        st = [[tuple(map(int, p.split(','))) for p in s.split(' ')] for s in ink.split(';') if s]
        xs.append(gen.real_sample(st)); ys.append(idx[ch])
    return torch.from_numpy(np.stack(xs))[:, None], torch.tensor(ys)

def evaluate(net, ex, ey, dev):
    net.eval(); t1 = t5 = 0
    with torch.no_grad():
        for i in range(0, len(ex), 512):
            o = net(ex[i:i+512].to(dev)).cpu()
            top = o.topk(5, 1).indices
            y = ey[i:i+512]
            t1 += (top[:, 0] == y).sum().item(); t5 += (top == y[:, None]).any(1).sum().item()
    net.train()
    return t1 / len(ex), t5 / len(ex)

if __name__ == '__main__':
    steps = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
    tag = sys.argv[2] if len(sys.argv) > 2 else 'run'
    base = sys.argv[3] if len(sys.argv) > 3 else None
    bs = 256
    dev = torch.device('mps')
    chars, _ = gen.classes()
    ex, ey = load_eval(chars) if os.path.exists(EVAL) else (None, None)
    print('classes', len(chars), 'eval', 0 if ex is None else len(ex), flush=True)
    net = Net(len(chars)).to(dev)
    done = 0
    if base and os.path.exists(base):
        d = torch.load(base, map_location='cpu', weights_only=False)
        net.load_state_dict(d['model'])
        done = int(d.get('step', 0))
        print('resumed from', base, 'step', done, flush=True)
    print('params', sum(p.numel() for p in net.parameters()), flush=True)
    opt = torch.optim.SGD(net.parameters(), lr=0.1, momentum=0.9, weight_decay=5e-4, nesterov=True)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=0.2, total_steps=steps, pct_start=0.15)
    for _ in range(done):
        sched.step()
    it = batches(bs, 8, 1234 + done)
    t = time.time(); lossavg = 0
    for step in range(done + 1, steps + 1):
        x, y = next(it)
        x = x.to(dev, non_blocking=True); y = y.to(dev)
        with torch.autocast('mps', dtype=torch.float16):
            out = net(x)
            loss = F.cross_entropy(out.float(), y, label_smoothing=0.1)
        opt.zero_grad(set_to_none=True); loss.backward(); opt.step(); sched.step()
        lossavg = 0.98 * lossavg + 0.02 * loss.item() if step > done + 1 else loss.item()
        if step % 200 == 0:
            print(f'step {step} loss {lossavg:.3f} lr {sched.get_last_lr()[0]:.4f} {bs*200/(time.time()-t):.0f} img/s', flush=True); t = time.time()
        if step % 1000 == 0 or step == steps:
            if ex is not None:
                a1, a5 = evaluate(net, ex, ey, dev)
                print(f'EVAL step {step} real top1 {a1*100:.1f}% top5 {a5*100:.1f}%', flush=True)
            torch.save({'model': net.state_dict(), 'chars': chars, 'ch': CH, 'step': step}, f'{tag}.pt')
            torch.save({'model': net.state_dict(), 'chars': chars, 'ch': CH, 'step': step}, f'{tag}.last.pt')
