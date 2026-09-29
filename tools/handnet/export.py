"""export a trained Net checkpoint to the WVHN format read by core/weave-dict/src/handnet.rs
BatchNorm is folded into the preceding conv; weights are symmetric per-output-channel int8."""
import sys, struct, numpy as np, torch
import gen
from train import Net

def q8(w):
    """per-output-row symmetric int8: returns (scale[out], q[out, ...])"""
    flat = w.reshape(w.shape[0], -1)
    s = np.abs(flat).max(1) / 127.0
    s[s == 0] = 1e-8
    q = np.clip(np.round(flat / s[:, None]), -127, 127).astype(np.int8)
    return s.astype(np.float32), q

def main(src, dst):
    d = torch.load(src, map_location='cpu', weights_only=False)
    chars = d['chars']
    net = Net(len(chars), d['ch']); net.load_state_dict(d['model']); net.eval()
    layers = []
    mods = list(net.f)
    i = 0
    while i < len(mods):
        m = mods[i]
        if isinstance(m, torch.nn.Conv2d):
            bn = mods[i + 1]
            g = (bn.weight / torch.sqrt(bn.running_var + bn.eps)).detach().numpy()
            w = m.weight.detach().numpy() * g[:, None, None, None]
            b = (bn.bias.detach().numpy() - bn.running_mean.numpy() * g)
            if m.bias is not None: b = b + m.bias.detach().numpy() * g
            layers.append(('conv', w.astype(np.float32), b.astype(np.float32)))
            i += 3  # conv, bn, relu
        elif isinstance(m, torch.nn.MaxPool2d):
            layers.append(('pool',)); i += 1
        else:
            raise SystemExit(f'unexpected layer {m}')
    layers.append(('gap',))
    layers.append(('fc', net.fc.weight.detach().numpy().astype(np.float32), net.fc.bias.detach().numpy().astype(np.float32)))
    out = bytearray(b'WVHN')
    out += struct.pack('<IIIfII', 1, gen.N, gen.M, 1.25, len(chars), len(layers))
    for c in chars: out += struct.pack('<I', ord(c))
    for L in layers:
        if L[0] == 'conv':
            w, b = L[1], L[2]
            s, q = q8(w)
            out += struct.pack('<BII', 1, w.shape[1], w.shape[0]) + s.tobytes() + q.tobytes() + b.tobytes()
        elif L[0] == 'pool': out += b'\x02'
        elif L[0] == 'gap': out += b'\x03'
        else:
            w, b = L[1], L[2]
            s, q = q8(w)
            out += struct.pack('<BII', 4, w.shape[1], w.shape[0]) + s.tobytes() + q.tobytes() + b.tobytes()
    open(dst, 'wb').write(out)
    print(dst, len(out), 'bytes', len(chars), 'classes')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
