import sys, numpy as np
from PIL import Image
sys.path.insert(0,'.')
from colors import board_box
def pitch(profile):
    p=profile-profile.mean(); n=len(p)
    best=(0,0)
    for P in np.arange(15,45,0.02):
        k=np.arange(n); s=abs(np.sum(p*np.exp(-2j*np.pi*k/P)))
        if s>best[0]: best=(s,P)
    return best[1]
