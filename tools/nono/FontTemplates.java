import com.nickrocks.pixelflowbot.nono.*;
import com.nickrocks.pixelflowbot.vision.Frame;
import java.awt.*; import java.awt.image.*; import java.io.*; import java.util.*;
/** Renders digit templates from the Inter font (Nonogram.com uses an Inter-style face) and checks them against real glyphs. */
public class FontTemplates {
  static float[] render(Font font, int d) {
    BufferedImage im = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = im.createGraphics(); g.setColor(new Color(233,236,247)); g.fillRect(0,0,200,200);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(new Color(39,33,95)); g.setFont(font); g.drawString(""+d, 50, 150);
    int[] px = im.getRGB(0,0,200,200,null,0,200); Frame f = new Frame(200,200,px);
    int x0=999,x1=-1,y0=999,y1=-1;
    for(int y=0;y<200;y++)for(int x=0;x<200;x++){ int p=px[y*200+x]; int l=((p>>16&255)*3+(p>>8&255)*6+(p&255))/10; if(l<205){x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);} }
    return Digits.features(f,x0,y0,x1,y1,null);
  }
  public static void main(String[] a) throws Exception {
    Font base = Font.createFont(Font.TRUETYPE_FONT, new File(a[0])).deriveFont(Float.parseFloat(a[1]));
    float[][] T = new float[10][]; for (int d=0;d<10;d++) T[d]=render(base,d);
    if (a.length>2) { for (int d=0;d<10;d++) System.out.println(Digits.encode(d,T[d])); return; }
    // classify game glyphs
    String[][] sets = {{"/home/claude/vid/all/f080.png","2155523444 5".replace(" ","")},{"/home/claude/vid/all/f129.png","42222313115114351615532221121111111111426977"}};
    int ok=0,n=0;
    for (String[] s : sets) { NonoVision v = new NonoVision(); v.glyphLog=new ArrayList<>(); v.read(NonoProbe.load(s[0]));
      for (int k=0;k<v.glyphLog.size();k++){ float[] g=v.glyphLog.get(k); int best=-1; float bd=1e9f, sd=1e9f;
        for(int d=0;d<10;d++){ float dd=0; for(int i=0;i<g.length;i++){float e=g[i]-T[d][i]; dd+=e*e;} dd/=g.length; if(dd<bd){sd=bd;bd=dd;best=d;} else if(dd<sd) sd=dd; }
        n++; int want=s[1].charAt(k)-'0'; if(best==want) ok++; else System.out.println("miss want "+want+" got "+best+" d="+bd);
      } }
    System.out.println(ok+"/"+n);
  }
}
