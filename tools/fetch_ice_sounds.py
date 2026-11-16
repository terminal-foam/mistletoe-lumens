import json, urllib.request, os
from pathlib import Path

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

manifest = json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
entry = next(v for v in manifest['versions'] if v['id'] == '26.4-snapshot-8')
vjson = json.loads(fetch(entry['url']))
asset_url = vjson['assetIndex']['url']
print('asset index:', asset_url)
index = json.loads(fetch(asset_url))
objs = index['objects']
names = [n for n in objs if 'sounds/block/ice' in n or 'sounds/block/glass' in n]
for n in sorted(names):
    print(n, objs[n]['hash'], objs[n]['size'])
