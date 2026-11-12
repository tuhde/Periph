///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.CS43L22Full;

public class CS43L22Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x4A"));

        try (var connection = new I2CConnection(bus, addr)) {
            var dac = new CS43L22Full(connection);
            dac.init();
            checkTrue(true, "init");
            checkTrue(dac.readId()[0] == 0x1C, "read_id");
            dac.setVolume(-20.0);
            checkTrue(Math.abs(dac.getVolume() - -20.0) < 0.01, "volume_roundtrip");
            dac.setVolumeChannels(-10.0, -12.0);
            checkTrue(dac.readRegister(0x20) == 0xEC && dac.readRegister(0x21) == 0xE8, "volume_channels");
            dac.setHeadphoneGain(CS43L22Full.GAIN_1_000);
            checkTrue(dac.headphoneGain() == 1.0, "headphone_gain");
            dac.mute(true);
            checkTrue((dac.readRegister(0x0F) & 0xC0) == 0xC0, "mute");
            dac.mute(false);
            checkTrue((dac.readRegister(0x0F) & 0xC0) == 0, "unmute");
            dac.setTone(6.0, 6.0, 0, 0, true);
            checkTrue(dac.readRegister(0x1F) == 0x44, "tone");
            dac.setLimiter(true, -6, -12);
            checkTrue((dac.readRegister(0x28) & 0x80) == 0x80, "limiter");
            dac.setLimiter(false, 0, 0);
            checkTrue(dac.readSpeakerStatus() != null, "speaker_status_readable");
            dac.stop();
            checkTrue(dac.readRegister(0x02) == 0x9F, "stop_powers_down");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed > 0 ? 1 : 0);
    }
}
