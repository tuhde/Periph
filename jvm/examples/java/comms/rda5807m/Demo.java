///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.comms.Rda5807mFull;

/**
 * FM band scanner: starts at the bottom of the world-wide band and
 * repeatedly seeks upward (SKMODE=1, the Minimal/Full default, stops
 * seeking at the band limit) until the top of the band is reached,
 * printing frequency, signal strength, stereo/mono status, and — when
 * available — the RDS Program Service (station) name for every station
 * found along the way.
 */
public class Demo {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x10)) {
            var fm = new Rda5807mFull(transport, 87.5, 10);

            // --- FM band scanner ---
            // Start at the bottom of the world-wide band and repeatedly seek
            // upward with SKMODE=1 (stop at band limit) so a seek that
            // returns null means the top of the band has been reached.
            fm.enableRds(true);

            System.out.println("Scanning...");
            int count = 0;
            while (true) {
                Double freq = fm.seek(true);
                if (freq == null) break;
                if (!fm.isStation()) continue;

                int rssi = fm.signalStrength();
                boolean stereo = fm.isStereo();

                // --- Try to read the Program Service (station) name via RDS ---
                // Group types 0A/0B carry the 8-character PS name, four
                // segments of two characters each, addressed by block B bits
                // 1:0. Give the decoder up to 2 seconds to assemble a full name.
                Character[] psChars = new Character[8];
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    if (fm.rdsReady()) {
                        int[] group = fm.readRdsGroup();
                        if (group != null) {
                            int blockB = group[1];
                            int blockD = group[3];
                            int groupType = blockB >> 12;
                            int isBVariant = (blockB >> 11) & 1;
                            if (groupType == 0 && isBVariant == 0) {
                                int segment = blockB & 0x03;
                                psChars[segment * 2] = (char) (blockD >> 8);
                                psChars[segment * 2 + 1] = (char) (blockD & 0xFF);
                                if (allSet(psChars)) break;
                            }
                        }
                    }
                    Thread.sleep(40);
                }

                String label = allSet(psChars) ? joinChars(psChars).trim() : "(no RDS name)";
                System.out.printf("%.2f MHz  RSSI=%d  %s  %s%n", freq, rssi, stereo ? "stereo" : "mono", label);
                count++;
            }

            System.out.println();
            System.out.println("Scan complete: " + count + " station(s) found");
        }
    }

    private static boolean allSet(Character[] chars) {
        for (Character c : chars) if (c == null) return false;
        return true;
    }

    private static String joinChars(Character[] chars) {
        StringBuilder sb = new StringBuilder();
        for (Character c : chars) sb.append(c.charValue());
        return sb.toString();
    }
}
