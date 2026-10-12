# ---------- Part 8a: load the training numbers and the device's own sounds ----------
import os, json, time, urllib.request, numpy as np, pandas as pd
from sklearn.preprocessing import StandardScaler
from sklearn.neural_network import MLPClassifier
FIREBASE_URL = 'https://YOUR-PROJECT-default-rtdb.REGION.firebasedatabase.app'   # the same address as in the device
HIVE_NAME = 'H01'                                                                         # the same name as in the device
cands = [os.path.join(WORK, 'device_features_v2.npz'), '/content/drive/MyDrive/bee_data/device_features_v2.npz',
         '/content/device_features_v2.npz', 'device_features_v2.npz']
DEV_NPZ = next((p for p in cands if os.path.exists(p)), None)
assert DEV_NPZ, 'Copy device_features_v2.npz (APP/results_notebook) to Google Drive: MyDrive/bee_data/ , then run this cell again.'
D = np.load(DEV_NPZ)
BASE = json.loads(str(D['base_model']))

def fb_get(path):
    with urllib.request.urlopen(FIREBASE_URL + path, timeout=60) as r:
        return json.loads(r.read().decode('utf-8'))

try:
    raw = fb_get(f'/trainfeatures/{HIVE_NAME}.json') or {}
except Exception as e:
    print('Could not read the device data from Firebase:', e)
    raw = {}
# teaching sessions: readings with the same label, less than 2 minutes apart
DROP_SESSIONS = []        # write session numbers here to leave them out, e.g. [2, 3] if "Bees here" was pressed away from the bees
sessions, last = [], None
for key in sorted(raw):
    r = raw[key]
    t, lab = r.get('t', 0), r.get('label', '')
    if last is None or lab != last[0] or t - last[1] > 120:
        sessions.append({'label': lab, 'start': t, 'records': []})
    sessions[-1]['records'].append(r); last = (lab, t)
rows8a = []
DF, DY, DT = [], [], []
for i, sn in enumerate(sessions, 1):
    probs = [p for r in sn['records'] for p in r.get('p', [])]
    when = time.strftime('%d %b %H:%M', time.gmtime(sn['start'] + 5.5 * 3600)) if sn['start'] > 1.6e9 else '?'
    rows8a.append({'session': i, 'label': 'bees here' if sn['label'] == 'hive' else 'no bees here', 'start (Sri Lanka time)': when,
                   'windows': sum(len(r.get('f', [])) for r in sn['records']),
                   'average bee chance heard': round(float(np.mean(probs)), 2) if probs else None, 'used': i not in DROP_SESSIONS})
    if i in DROP_SESSIONS: continue
    for r in sn['records']:
        for f in r.get('f', []):
            if isinstance(f, list) and len(f) == 50:
                DF.append(f); DY.append(1 if sn['label'] == 'hive' else 0); DT.append(r.get('t', 0))
DF, DY, DT = np.array(DF, float).reshape(-1, 50), np.array(DY, int), np.array(DT)
if rows8a:
    display(pd.DataFrame(rows8a).set_index('session'))
    print('Check the table: a "bees here" session must have been done right at the bees. If not, put its number in DROP_SESSIONS and run this cell again.')
print(f"Training numbers: {len(D['FH'])} hive windows (70 hives), {len(D['FN'])} other-sound windows, "
      f"{len(D['FS'])} synthetic room sounds, {len(D['MX'])} hive+noise mixtures")
print(f'From the device {HIVE_NAME}: {int((DY == 0).sum())} windows taught as "no bees here", {int((DY == 1).sum())} as "bees here"')
if len(DY) == 0:
    print('No device data yet: Part 8b still tests the detector, and 8c makes the same detector again. Teach the device first for a better one.')
