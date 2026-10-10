# Trace the source alpha outline into code-native vector paths; keep original PNG untouched.
from pathlib import Path
from PIL import Image
import cv2
import numpy as np
import html
project=Path(__file__).resolve().parents[2]
src=project/'common/src/main/res/drawable-nodpi/cluster_carplay_ultra_logo.png'
a=np.asarray(Image.open(src))[:,:,3].astype(float)/255
# Padding closes silhouettes that touch an edge of the original mark.
a=np.pad(a,1)
h,w=a.shape
nodes={};adj={}
def edge(r,c,k):
 if k==0:key=('h',r,c);x,y=c,r;x2,y2=c+1,r
 elif k==1:key=('v',r,c+1);x,y=c+1,r;x2,y2=c+1,r+1
 elif k==2:key=('h',r+1,c);x,y=c,r+1;x2,y2=c+1,r+1
 else:key=('v',r,c);x,y=c,r;x2,y2=c,r+1
 if key not in nodes:
  f=(.5-a[y,x])/(a[y2,x2]-a[y,x])
  nodes[key]=(x+f*(x2-x)-.5,y+f*(y2-y)-.5)
 return key
pairs={1:[(3,0)],2:[(0,1)],3:[(3,1)],4:[(1,2)],6:[(0,2)],7:[(3,2)],8:[(2,3)],9:[(0,2)],11:[(1,2)],12:[(1,3)],13:[(0,1)],14:[(3,0)]}
for r in range(h-1):
 for c in range(w-1):
  vals=[a[r,c],a[r,c+1],a[r+1,c+1],a[r+1,c]]
  mask=sum(1<<i for i,v in enumerate(vals) if v>=.5)
  if mask in (0,15):continue
  if mask==5:ps=[(0,1),(2,3)] if sum(vals)>=2 else [(0,3),(1,2)]
  elif mask==10:ps=[(0,3),(1,2)] if sum(vals)>=2 else [(0,1),(2,3)]
  else:ps=pairs[mask]
  for e1,e2 in ps:
   p,q=edge(r,c,e1),edge(r,c,e2)
   adj.setdefault(p,[]).append(q);adj.setdefault(q,[]).append(p)
assert all(len(v)==2 for v in adj.values())
seen=set();paths=[]
for start in adj:
 if start in seen:continue
 contour=[];prev=None;node=start
 while node not in seen:
  seen.add(node);contour.append(nodes[node])
  neighbors=adj[node];nxt=neighbors[0] if neighbors[0]!=prev else neighbors[1]
  prev,node=node,nxt
 assert node==start
 pts=cv2.approxPolyDP(np.array(contour,dtype=np.float32),.12,True).reshape(-1,2)
 if len(pts)<3:continue
 def xy(p):return f'{float(p[0]):.3f},{float(p[1]):.3f}'
 paths.append('M'+xy(pts[0])+' '+' '.join('L'+xy(p) for p in pts[1:])+' Z')
data=' '.join(paths)
xml='<?xml version="1.0" encoding="utf-8"?>\n<!-- Outline traced from the original Apple website PNG, not an official vector release. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="584dp" android:height="98dp" android:viewportWidth="584" android:viewportHeight="98">\n    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="'+data+'"/>\n</vector>\n'
(project/'common/src/main/res/drawable/cluster_carplay_ultra_logo_vector.xml').write_text(xml)
print('Closed outline loops:',len(paths),'XML bytes:',len(xml))
