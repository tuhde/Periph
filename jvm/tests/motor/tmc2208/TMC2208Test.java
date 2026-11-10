///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.UARTConnection;
import it.uhde.periph.chips.motor.TMC2208Full;

public class TMC2208Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("UART_PORT", "/dev/ttyS0");
        int baud = Integer.parseInt(System.getenv().getOrDefault("UART_BAUD", "115200"));

        try (var connection = new UARTConnection(port, baud, 8, 1.0, 'N', 100, -1)) {
            var motor = new TMC2208Full(connection);
            checkTrue(true, "init");

            var ioin = motor.readIoin();
            checkTrue(ioin.version() != 0x00 && ioin.version() != 0xFF, "ioin_version");
            checkTrue(ioin.selA(), "ioin_sel_a");
            int before = motor.readIfcnt();
            motor.setCurrent(0.5);
            checkTrue(motor.readIfcnt() == ((before + 2) & 0xFF), "ifcnt_advances");
            motor.setMicrosteps(16);
            checkTrue(((motor.readRegister(0x6C) >> 24) & 0xF) == 4, "mres_readback");
            checkTrue((motor.readRegister(0x00) & 0x40) == 0x40, "gconf_pdn_disable");
            motor.enable();
            motor.run(100);
            Thread.sleep(1000);
            checkTrue(!motor.isStandstill(), "moving");
            checkTrue(motor.readTstep() > 0, "tstep_nonzero");
            checkTrue(motor.readFault() == 0, "no_fault");
            motor.stop();
            Thread.sleep(500);
            motor.disable();
            checkTrue(motor.readStatus().csActual() <= 31, "status_decoded");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        if (failed > 0) System.exit(1);
    }
}
