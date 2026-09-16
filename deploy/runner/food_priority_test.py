#!/usr/bin/env python3
"""Run the full gamer selector with no known food and a competing resource."""
import argparse,json,re,statistics,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
from food_policy_probe import policy_probe
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag',required=True)
ap.add_argument('--seconds',type=int,default=30)
ap.add_argument('--fed',action='store_true')
ap.add_argument('--meal',type=int,default=0,help='Give food during active exploration and require eating.')
ap.add_argument('--meal-item',default='bread')
ap.add_argument('--reserve-meal',action='store_true')
ap.add_argument('--keep-unused',action='store_true')
ap.add_argument('--observe-seconds',type=float,default=20)
ap.add_argument('--output-dir',type=Path,required=True)
ap.add_argument('--hurt',action='store_true')
ap.add_argument('--unarmed',action='store_true')
ap.add_argument('--until-exit',action='store_true')
ap.add_argument('--expect-bug',action='store_true')
a=ap.parse_args()
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon();old={};recording=False;food_scope=None;policy=None
previous_difficulty=next(v for v in ('peaceful','easy','normal','hard') if v in r.cmd('difficulty').lower())
try:
 if 'tester1' not in r.cmd('list') or not p.call('getGameState').get('self'):p.call('ConnectToServer','test-server')
 for _ in range(45):
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
  time.sleep(1)
 else:raise RuntimeError('flat login failed')
 p.call('stopPathing');p.call('closeOpenScreen')
 for k in ['allowBreak','allowPlace','botFpsNoIdleThrottle']:
  old[k]=p.call('tungstenSetting',k,'').split('=',1)[1];p.call('tungstenSetting',k,'true')
 for cmd in ['gamemode spectator tester1','difficulty peaceful','forceload add 3570 1570 3630 1630','execute positioned 3600 -58 1600 run kill @e[type=!player,distance=..70]','clear tester1','effect clear tester1','fill 3570 -61 1570 3630 -61 1630 bedrock','fill 3570 -60 1570 3630 -53 1630 stone','fill 3570 -52 1570 3630 -51 1630 stone','fill 3598 -58 1598 3604 -56 1602 air','setblock 3603 -57 1601 coal_ore','give tester1 cobblestone 64','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true','tp tester1 3600.5 -58 1600.5 0 0']:
  r.cmd(cmd)
 for y in range(-50,-25,8):r.cmd(f'fill 3570 {y} 1570 3630 {min(y+7,-26)} 1630 air')
 if not a.unarmed:
  r.cmd('give tester1 diamond_pickaxe');r.cmd('give tester1 diamond_sword')
 rec_start(a.seconds+25);recording=True;time.sleep(3)
 r.cmd('difficulty normal');r.cmd('gamemode survival tester1')
 if a.fed:
  r.cmd('effect give tester1 saturation 1 20 true');time.sleep(1.2)
 if not a.fed:
  r.cmd('effect clear tester1 saturation');r.cmd('effect give tester1 hunger 8 255 true');time.sleep(8.5);r.cmd('effect clear tester1 hunger');r.cmd('effect give tester1 instant_health 1 5 true')
 if a.hurt:r.cmd('effect give tester1 instant_damage 1 1 true')
 hunger_response=r.cmd('data get entity tester1 foodLevel');hunger=int(re.search(r': (\d+)$',hunger_response).group(1))
 assert (hunger==20 if a.fed else hunger<=10),hunger_response
 before=p.call('getGameState')['self']
 if a.hurt:assert before['hp']<=10,before
 if a.reserve_meal or a.keep_unused:food_scope=policy_probe(mode='enter',item=a.meal_item,reserve=a.reserve_meal,keep_unused=a.keep_unused)
 p.call('ExecuteCommand','@gamer')
 rows=[];start=time.monotonic();selected=False;emerged=False;meal_given=False
 while time.monotonic()-start<a.seconds:
  state=p.call('getGameState')['self'];chain=p.call('getTaskChainString')
  rows.append({'t':round(time.monotonic()-start,2),'state':state,'chain':chain,'fps':p.call('getPerfStats').get('fps',0)})
  selected=selected or 'units of food' in chain
  if a.meal and selected and not meal_given and time.monotonic()-start>=5:
   r.cmd(f'give tester1 {a.meal_item} {a.meal}');meal_given=True
   policy=policy_probe(mode='sample',item=a.meal_item)
  emerged=state['onGround'] and float(state['pos'].split(',')[1])>=-51
  if not a.expect_bug and selected and (not a.until_exit or emerged) and time.monotonic()-start>=a.observe_seconds:break
  time.sleep(.3)
 expected=not a.fed or a.hurt
 hunger_after=int(re.search(r': (\d+)$',r.cmd('data get entity tester1 foodLevel')).group(1))
 success=(selected==expected) and (not a.until_exit or emerged) and (not a.meal or (hunger_after==hunger if a.reserve_meal else hunger_after>hunger))
 d={'policy':policy,'reserved':a.reserve_meal,'keep_unused':a.keep_unused,'meal_item':a.meal_item,'meal':a.meal,'hunger_after':hunger_after,'before':before,'hunger_before':hunger,'fed':a.fed,'hurt':a.hurt,'unarmed':a.unarmed,'food_selected':selected,'emerged':emerged,'samples':rows,'median_fps':statistics.median(v['fps'] for v in rows)}
 d['gamer_seen']=any('Beating the game' in v['chain'] for v in rows)
 d['policy_valid']=not a.meal or (policy['consume']==(not a.reserve_meal) and policy['discard'] is False and policy['reserved']==a.reserve_meal and policy['discard_protected'] is True)
 d['pass']=d['gamer_seen'] and all(v['state']['hp']>0 for v in rows) and d['median_fps']>=14 and (not success if a.expect_bug else success and d['policy_valid'])
 (root/(a.tag+'.json')).write_text(json.dumps(d,indent=2));p.screenshot(str(root/(a.tag+'.png')));print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('full gamer food-selection gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/(a.tag+'.mp4')))
 if food_scope:policy_probe(mode='exit',item=a.meal_item,old=food_scope)
 r.cmd('forceload remove 3570 1570 3630 1630');r.cmd('gamemode survival tester1');r.cmd('difficulty '+previous_difficulty)
 for k,v in old.items():p.call('tungstenSetting',k,v)
