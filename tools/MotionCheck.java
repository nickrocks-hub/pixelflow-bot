import com.aigame.subwaysurfers.core.SurferBrain;
import java.io.*;
/** Replays a raw recording at 30 or 60 fps (each frame shown N times) and reports the motion measure. Usage: java MotionCheck rec.raw N */
public class MotionCheck { public static void main(String[] a) throws Exception {
  int w=360,h=840; int dup=Integer.parseInt(a[1]); double step=1000.0/30/dup;
  SurferBrain b=new SurferBrain(new SurferBrain.Params()); b.simulateAckMs=60;
  byte[] buf=new byte[w*h*3]; int[] px=new int[w*h]; int n=0, low=0, stoppedFrames=0, moves=0; double t=0;
  java.util.List<Double> ms=new java.util.ArrayList<>();
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(a[0]),1<<20))){
    for(int f=0;f<1000;f++){ in.readFully(buf); for(int i=0,j=0;i<px.length;i++,j+=3) px[i]=((buf[j]&0xFF)<<16)|((buf[j+1]&0xFF)<<8)|(buf[j+2]&0xFF);
      for(int d=0;d<dup;d++){ long tt=(long)t; t+=step; SurferBrain.Move m=b.onFrame(px,w,h,tt,tt); if(m!=SurferBrain.Move.NONE) moves++;
        double mo=b.vision.motion; if(!Double.isNaN(mo)){ ms.add(mo); n++; if(mo<=4) low++; }
        if(b.game==SurferBrain.Game.STOPPED) stoppedFrames++; } } }
  java.util.Collections.sort(ms);
  System.out.printf("fps=%d  motion p5=%.1f p50=%.1f  frames<=4: %d of %d  STOPPED frames=%d  moves=%d%n", 30*dup, ms.get(ms.size()/20), ms.get(ms.size()/2), low, n, stoppedFrames, moves);
}}
