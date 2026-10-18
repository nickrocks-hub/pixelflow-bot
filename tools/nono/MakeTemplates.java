import com.nickrocks.pixelflowbot.nono.*;
import java.util.*;

/**
 * Builds DigitData templates from screenshots whose clues are known.
 * Args: repeated  <frame.png> <digits in reading order: columns top-to-bottom, then rows left-to-right>
 */
public class MakeTemplates {
    public static void main(String[] a) throws Exception {
        List<String> out = new ArrayList<>();
        Map<Integer, Integer> perDigit = new TreeMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) {
            NonoVision v = new NonoVision();
            v.glyphLog = new ArrayList<>();
            NonoVision.Reading r = v.read(NonoProbe.load(a[i]));
            String want = a[i + 1].replaceAll("[^0-9]", "");
            if (v.glyphLog.size() != want.length()) {
                System.err.println(a[i] + ": got " + v.glyphLog.size() + " glyphs, expected " + want.length() + " (" + r.error + ")");
                continue;
            }
            for (int k = 0; k < want.length(); k++) {
                int d = want.charAt(k) - '0';
                int have = perDigit.getOrDefault(d, 0);
                if (have >= 6) continue;
                // skip near-duplicates
                String enc = Digits.encode(d, v.glyphLog.get(k));
                if (out.contains(enc)) continue;
                perDigit.put(d, have + 1);
                out.add(enc);
            }
        }
        System.err.println("templates per digit: " + perDigit);
        for (String s : out) System.out.println("        \"" + s + "\",");
    }
}
