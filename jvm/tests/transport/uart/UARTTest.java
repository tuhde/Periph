///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT

import it.uhde.periph.transport.UARTTransport;

/**
 * UART loopback test — assumes a jumper bridging TXD and RXD on the port
 * under test. Configure the port via the UART_PORT environment variable
 * (default: /dev/ttyS0) and baud rate via UART_BAUD (default: 9600).
 */
public class UARTTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        String port    = System.getenv().getOrDefault("UART_PORT", "/dev/ttyS0");
        int    baud    = Integer.parseInt(System.getenv().getOrDefault("UART_BAUD", "9600"));

        try (var transport = new UARTTransport(port, baud, 8, 1.0, 'N', 1000, -1)) {

            byte[] payload = {0x42};
            transport.write(payload);
            checkTrue("write accepted", true);

            byte[] rxByte = transport.read(1);
            checkTrue("read returns 1 byte", rxByte.length == 1);
            checkTrue("loopback byte matches", rxByte[0] == 0x42);

            byte[] cmd  = {(byte) 0xA5, (byte) 0x5A};
            byte[] resp = transport.writeRead(cmd, 2);
            checkTrue("writeRead returns 2 bytes", resp.length == 2);
            checkTrue("writeRead loopback matches",
                      resp[0] == (byte) 0xA5 && resp[1] == (byte) 0x5A);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
