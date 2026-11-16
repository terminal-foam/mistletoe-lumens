import json

exp_base = r'E:\bb-agent\workspace\default\exports\_with_lantern\1.20.1\Lumen_Seishin\Lumen_Seishin'
cls = r'E:\dsh\mistletoe-classic\src\main\resources\assets\mistletoe'

anim_path = cls + r'\animations\lumen_seishin.animation.json'
anim = json.loads(open(anim_path, encoding='utf-8').read())
exp_anim = json.loads(open(exp_base + r'\animations\arm.animation.json', encoding='utf-8').read())
key = 'hold_offhand$minecraft:lantern'
lan = dict(exp_anim['animations'][key])
lan['animation_length'] = 2.0
anim['animations']['slashblade:lantern'] = lan
open(anim_path, 'w', encoding='utf-8').write(json.dumps(anim, ensure_ascii=False))
print('lantern anim added:', lan)
