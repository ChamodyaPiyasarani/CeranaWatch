# ---------- Part 8c: the new detector for the device (BeeDetect.h) ----------
use_device = len(DY) > 0
NEW = mlp(*train_set(None, use_device, device_rows=np.ones(len(DY), bool)))
rng = np.random.default_rng(7)
def spread(groups, n):                              # examples spread over all hives / all sound kinds
    keys = sorted(set(groups)); per = int(np.ceil(n / len(keys))); pick = []
    for kk in keys:
        idx = np.where(groups == kk)[0]; pick += list(rng.choice(idx, min(per, len(idx)), replace=False))
    pick = np.array(pick); rng.shuffle(pick); return pick[:n]
FO = np.vstack([FN, FS]); okind = np.r_[D['kind'], np.array(['syn_' + s for s in D['skind']])]
store_bee = hidden(NEW, FH[spread(hive, 240)]); store_other = hidden(NEW, FO[spread(okind, 240)])   # kept on the device for teaching

def num(v):
    t = f'{v:.9g}'
    return t if ('.' in t or 'e' in t) else t + '.0'
def arr(name, vals, per=8):
    vals = [float(v) for v in np.ravel(vals)]
    return (f'static const float {name}[{len(vals)}] = {{\n' +
            ''.join('  ' + ', '.join(num(v) + 'f' for v in vals[i:i + per]) + ',\n' for i in range(0, len(vals), per)) + '};\n')

model_name = time.strftime('colab %d %b %Y') + (f' (70 hives + {len(DY)} device windows)' if use_device else ' (70 hives)')
last = R8.iloc[-1]
results = '// Test (Colab Part 8): ' + '; '.join(f'{c} = {v}' for c, v in last.items() if v is not None)
header = str(D['template'])
for key, val in {
    '@@MODEL_NAME@@': model_name, '@@RESULTS@@': results, '@@THRESHOLD@@': '0.5',
    '@@BAND_LO@@': ', '.join(str(b[0]) for b in NEW['bands']), '@@BAND_HI@@': ', '.join(str(b[1]) for b in NEW['bands']),
    '@@ARRAYS@@': arr('BD_MEAN', NEW['mean']) + '\n' + arr('BD_SCALE', NEW['scale']) + '\n' + arr('BD_W1', np.ravel(NEW['W1'])) + '\n' +
                  arr('BD_B1', NEW['b1']) + '\n' + arr('BD_W2', NEW['W2']) + 'static const float BD_B2 = ' + num(NEW['b2']) + 'f;\n\n' +
                  '// hidden-layer values of stored examples, used when the device is taught (see fineTune)\n' +
                  arr('BD_STORE_BEE', store_bee) + '\n' + arr('BD_STORE_OTHER', store_other)}.items():
    header = header.replace(key, val)
assert '@@' not in header
out = os.path.join(WORK, 'BeeDetect.h')
open(out, 'w').write(header)
print(f'Saved {out} ({len(header) // 1024} KB).')
print('Next: put it in the CeranaNode folder (replace the old BeeDetect.h), upload to the ESP32, then teach "No bees here" once more.')
print('RESULTS ' + json.dumps({'model': model_name, **{c: v for c, v in last.items()}}))
if IN_COLAB:
    from google.colab import files
    files.download(out)
