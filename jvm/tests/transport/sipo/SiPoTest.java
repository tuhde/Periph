///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0

import it.uhde.periph.transport.SiPoTransport;

/**
 * SiPo transport test. Configure the wiring via environment variables:
 * SIPO_MODE (hw|sw, default sw), SIPO_RCK, SIPO_SRCLR, SIPO_G (GPIO line
 * numbers), SIPO_SER_IN, SIPO_SRCK (software mode GPIO lines), and
 * SIPO_SPI_BUS/SIPO_SPI_DEVICE (hardware mode spidev).
 */
public class SiPoTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        String mode   = System.getenv().getOrDefault("SIPO_MODE", "sw");
        int rckLine    = Integer.parseInt(System.getenv().getOrDefault("SIPO_RCK", "5"));
        int srclrLine  = Integer.parseInt(System.getenv().getOrDefault("SIPO_SRCLR", "6"));
        int gLine      = Integer.parseInt(System.getenv().getOrDefault("SIPO_G", "13"));

        SiPoTransport transport;
        if (mode.equals("hw")) {
            int busNumber    = Integer.parseInt(System.getenv().getOrDefault("SIPO_SPI_BUS", "0"));
            int deviceNumber = Integer.parseInt(System.getenv().getOrDefault("SIPO_SPI_DEVICE", "0"));
            transport = SiPoTransport.hardware(busNumber, deviceNumber, rckLine, srclrLine, gLine);
        } else {
            int serInLine = Integer.parseInt(System.getenv().getOrDefault("SIPO_SER_IN", "19"));
            int srckLine  = Integer.parseInt(System.getenv().getOrDefault("SIPO_SRCK", "26"));
            transport = SiPoTransport.software(serInLine, srckLine, rckLine, srclrLine, gLine);
        }

        try (transport) {
            transport.write(new byte[]{(byte) 0xA5});
            checkTrue("write accepted", true);

            transport.write(new byte[]{0x00, (byte) 0xFF});
            checkTrue("write multi-byte accepted", true);

            transport.clear();
            checkTrue("clear accepted", true);

            transport.setOutputEnable(false);
            checkTrue("setOutputEnable(false) accepted", true);

            transport.setOutputEnable(true);
            checkTrue("setOutputEnable(true) accepted", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
