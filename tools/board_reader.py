import sys, numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0,'.')
from colors import board_box, classify, PAL
from pitch import pitch

def cellvar(im,box,n,m):
    x0,x1,y0,y1=box; cw=(x1-x0+1)/n; ch=(y1-y0+1)/m; s=0
    for r in range(m):
        for c in range(n):
            cx=x0+(c+.5)*cw; cy=y0+(r+.5)*ch
            s+=im[int(cy-ch*.2):int(cy+ch*.2), int(cx-cw*.2):int(cx+cw*.2)].reshape(-1,3).std(0).sum()
    return s/(n*m)

def calibrate(im):
    """Run once at level start (full board). Returns box and grid size."""
    box=board_box(im); x0,x1,y0,y1=box
    lum=im[y0:y1+1,x0:x1+1].mean(2)
    px=pitch(np.abs(np.diff(lum,axis=1)).mean(0)); py=pitch(np.abs(np.diff(lum,axis=0)).mean(1))
    W=x1-x0+1; H=y1-y0+1
    cands=[(cellvar(im,box,n,m),n,m) for n in {int(W/px),round(W/px),int(W/px)+1}
                                      for m in {int(H/py),round(H/py),int(H/py)+1}]
    sc,n,m=min(cands); return box,n,m,sc

def read_grid(im,box,n,m):
    x0,x1,y0,y1=box; cw=(x1-x0+1)/n; ch=(y1-y0+1)/m
    return [[classify(np.median(im[int(y0+(r+.5)*ch-ch*.15):int(y0+(r+.5)*ch+ch*.15),
                                   int(x0+(c+.5)*cw-cw*.15):int(x0+(c+.5)*cw+cw*.15)].reshape(-1,3),0))
             for c in range(n)] for r in range(m)]

def render(path,box,grid,out):
    m=len(grid); n=len(grid[0]); S=12
    img=Image.new('RGB',(n*S,m*S),(57,59,99)); d=ImageDraw.Draw(img)
    for r in range(m):
        for c in range(n):
            k=grid[r][c]
            if k!='empty': d.rectangle([c*S+1,r*S+1,c*S+S-2,r*S+S-2],fill=PAL[k])
    src=Image.open(path).crop((box[0],box[2],box[1]+1,box[3]+1)).resize((n*S,m*S))
    o=Image.new('RGB',(n*S*2+10,m*S)); o.paste(src,(0,0)); o.paste(img,(n*S+10,0)); o.save(out)

if __name__=='__main__':
    from collections import Counter
    cal=sys.argv[1]; im=np.asarray(Image.open(cal).convert('RGB')).astype(float)
    box,n,m,sc=calibrate(im); print('calibrated on',cal,'box',box,'grid',n,'x',m,'fit',round(sc,1))
    for f in sys.argv[2:]:
        im2=np.asarray(Image.open(f).convert('RGB')).astype(float)
        g=read_grid(im2,box,n,m); print(f,Counter(x for r in g for x in r))
        render(f,box,g,f.replace('.png','_chk.png'))
