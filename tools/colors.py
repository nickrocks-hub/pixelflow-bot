import sys, numpy as np, colorsys
from PIL import Image, ImageDraw
BG = np.array([57,59,99.])
PAL = {'white':(235,240,248),'yellow':(255,215,40),'orange':(255,130,20),'red':(220,20,20),
       'blue':(30,80,230),'green':(60,200,40),'purple':(140,50,240),'black':(55,55,60)}

def classify(p):
    r,g,b = p/255; h,s,v = colorsys.rgb_to_hsv(r,g,b)
    if v < 0.55:                       # dark: black cube (grey) vs navy background (bluish)
        return 'black' if s < 0.26 else 'empty'
    if s < 0.18: return 'white'
    h *= 360
    if h < 12 or h >= 340: return 'red'
    if h < 42: return 'orange'
    if h < 70: return 'yellow'
    if h < 170: return 'green'
    if h < 250: return 'blue'
    return 'purple'

def board_box(im):
    # search inside the board panel area; cubes = pixels clearly unlike navy bg
    y0,y1,x0,x1 = 420,1320,100,980
    sub = im[y0:y1,x0:x1]
    m = np.abs(sub-BG).sum(2) > 60
    rows = np.where(m.mean(1) > 0.35)[0]; cols = np.where(m.mean(0) > 0.35)[0]
    return x0+cols.min(), x0+cols.max(), y0+rows.min(), y0+rows.max()

def fit(im, bx0,bx1,by0,by1):
    W=bx1-bx0+1; H=by1-by0+1; best=None
    for n in range(10,61):
        cw=W/n
        if not 14<=cw<=60: continue
        for m in range(max(10,round(H/cw)-2), round(H/cw)+3):
            ch=H/m
            if abs(ch-cw)/cw>0.1: continue
            s=0; k=0
            for r in range(0,m,max(1,m//12)):
                for c in range(0,n,max(1,n//12)):
                    cx=bx0+(c+.5)*cw; cy=by0+(r+.5)*ch
                    p=im[int(cy-ch*.2):int(cy+ch*.2), int(cx-cw*.2):int(cx+cw*.2)].reshape(-1,3)
                    s+=p.std(0).sum(); k+=1
            sc=s/k
            if best is None or sc<best[0]: best=(sc,n,m)
    return best

def read(path):
    im=np.asarray(Image.open(path).convert('RGB')).astype(float)
    bx0,bx1,by0,by1=board_box(im)
    sc,n,m=fit(im,bx0,bx1,by0,by1)
    cw=(bx1-bx0+1)/n; ch=(by1-by0+1)/m
    grid=[[classify(np.median(im[int(by0+(r+.5)*ch-ch*.15):int(by0+(r+.5)*ch+ch*.15),
                                    int(bx0+(c+.5)*cw-cw*.15):int(bx0+(c+.5)*cw+cw*.15)].reshape(-1,3),0))
           for c in range(n)] for r in range(m)]
    return (bx0,bx1,by0,by1),(n,m,sc),grid

if __name__=='__main__':
    from collections import Counter
    box,(n,m,sc),grid=read(sys.argv[1])
    print('box',box,'grid',n,'x',m,'fit',round(sc,1)); print(Counter(x for r in grid for x in r))
    S=12; out=Image.new('RGB',(n*S,m*S),(57,59,99)); d=ImageDraw.Draw(out)
    for r in range(m):
        for c in range(n):
            k=grid[r][c]
            if k!='empty': d.rectangle([c*S+1,r*S+1,c*S+S-2,r*S+S-2],fill=PAL[k])
    src=Image.open(sys.argv[1]).crop((box[0],box[2],box[1]+1,box[3]+1)).resize((n*S,m*S))
    o=Image.new('RGB',(n*S*2+10,m*S),(0,0,0)); o.paste(src,(0,0)); o.paste(out,(n*S+10,0)); o.save(sys.argv[2])
