# ---------- Part 8b: is a detector trained with the device's own sounds better? (honest test) ----------
def mlp(X, y, seed=0):
    sc = StandardScaler().fit(X)
    clf = MLPClassifier(hidden_layer_sizes=(16,), alpha=1e-3, max_iter=3000, random_state=seed).fit(sc.transform(X), y)
    return dict(mean=sc.mean_.tolist(), scale=sc.scale_.tolist(), W1=clf.coefs_[0].tolist(), b1=clf.intercepts_[0].tolist(),
                W2=clf.coefs_[1][:, 0].tolist(), b2=float(clf.intercepts_[1][0]), bands=BASE['bands'], threshold=0.5)

def hidden(m, X):
    Z = (np.asarray(X, float) - np.array(m['mean'])) / np.array(m['scale'])
    return np.maximum(0, Z @ np.array(m['W1']) + np.array(m['b1']))

def prob(m, X):
    return 1 / (1 + np.exp(-(hidden(m, X) @ np.array(m['W2']) + m['b2'])))

def presence(p_windows):
    """windows in time order -> share of 10-second readings (3 windows) reported as bees by the device's 3-of-4 rule"""
    on, hist, out = False, [], []
    for i in range(0, len(p_windows) - 2, 3):
        hist = (hist + [np.mean(p_windows[i:i + 3]) >= 0.5])[-4:]
        c = sum(hist)
        if not on and c >= 3: on = True
        elif on and c <= 1: on = False
        out.append(on)
    return np.mean(out) if out else np.nan

FH, FN, FS, MX = D['FH'], D['FN'], D['FS'], D['MX']
site, hive, k = D['site'], D['hive'], D['k']
train_n = D['split'] == 'train'
dev_test = np.zeros(len(DY), bool)                 # the newest 30% of each kind of device window are kept for testing
for lab in (0, 1):
    idx = np.where(DY == lab)[0]; idx = idx[np.argsort(DT[idx], kind='stable')]
    dev_test[idx[int(len(idx) * 0.7):]] = True
DEV_W = 5                                           # each device window counts 5 times (there are few of them)

def train_set(exclude_site=None, with_device=False, device_rows=None):
    Xp = [FH[site != exclude_site], MX[D['mix_site'] != exclude_site]]
    Xn = [FN[train_n], FS[:len(FS) // 2]]
    if with_device and len(DY):
        rows = ~dev_test if device_rows is None else device_rows
        Xp += [DF[rows & (DY == 1)]] * DEV_W
        Xn += [DF[rows & (DY == 0)]] * DEV_W
    Xp, Xn = np.vstack(Xp), np.vstack(Xn)
    return np.vstack([Xp, Xn]), np.r_[np.ones(len(Xp)), np.zeros(len(Xn))]

def report(name, models_by_site, m_all):
    rates = []
    for s, m in models_by_site.items():
        for h in sorted(set(hive[site == s])):
            idx = np.where(hive == h)[0]; idx = idx[np.argsort(k[idx])]
            rates.append(presence(prob(m, FH[idx])))
    pn = prob(m_all, FN[~train_n]); kinds = D['kind'][~train_n]
    row = {'model': name,
           'hive readings reported as bees (unseen apiaries)': round(float(np.nanmean(rates)), 3),
           'other-sound readings reported as bees (57 kinds)': round(float(np.nanmean([presence(pn[kinds == kk]) for kk in sorted(set(kinds))])), 3)}
    if len(DY):
        pdv = prob(m_all, DF[dev_test])
        for lab, col in [(0, 'device "no bees" windows called bees (newest 30%)'), (1, 'device "bees" windows called bees (newest 30%)')]:
            sel = DY[dev_test] == lab
            row[col] = round(float(np.mean(pdv[sel] >= 0.5)), 3) if sel.any() else None
    return row

t0 = time.time(); rows = []
for name, with_dev in [('A: detector without device data', False), ("B: detector with the device's own sounds", True)]:
    if with_dev and not len(DY): continue
    per_site = {s: mlp(*train_set(s, with_dev)) for s in ['S01', 'S02', 'S03', 'S04']}
    rows.append(report(name, per_site, mlp(*train_set(None, with_dev))))
R8 = pd.DataFrame(rows).set_index('model')
display(R8)
print(f'({time.time() - t0:.0f} s) "hive readings": higher is better. "other sounds" and "no bees windows": lower is better.')
