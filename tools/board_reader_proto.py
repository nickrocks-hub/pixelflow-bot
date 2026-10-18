import sys, numpy as np, colorsys
from PIL import Image, ImageDraw
im = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(float)
bx0,bx1,by0,by1,N = 146,933,497,1239,31
cw=(bx1-bx0+1)/N; ch=(by1-by0+1)/N
PAL = {'white':(235,240,248),'yellow':(255,215,40),'orange':(255,130,20),'red':(220,20,20),
       'blue':(30,80,230),'green':(60,200,40),'purple':(140,50,240)}
def classify(rgb):
    r,g,b=rgb/255; h,s,v=colorsys.rgb_to_hsv(r,g,b)
    if v<0.55: return 'empty'
    if s<0.18: return 'white'
    h*=360
    if h<12 or h>=340: return 'red'
    if h<42: return 'orange'
    if h<70: return 'yellow'
    if h<170: return 'green'
    if h<250: return 'blue'
    return 'purple'
grid=[]
for r in range(N):
    row=[]
    for c in range(N):
        cx=bx0+(c+.5)*cw; cy=by0+(r+.5)*ch
        p=im[int(cy-ch*.15):int(cy+ch*.15), int(cx-cw*.15):int(cx+cw*.15)].reshape(-1,3)
        row.append(classify(np.median(p,0)))
    grid.append(row)
from collections import Counter
print(Counter(x for row in grid for x in row))
out=Image.new('RGB',(N*12,N*12),(30,30,50)); d=ImageDraw.Draw(out)
for r in range(N):
    for c in range(N):
        k=grid[r][c]
        if k!='empty': d.rectangle([c*12+1,r*12+1,c*12+10,r*12+10],fill=PAL[k])
out.save(sys.argv[2])
