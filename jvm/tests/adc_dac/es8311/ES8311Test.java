///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ES8311Full;

public class ES8311Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x18").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var codec = new ES8311Full(connection);
            checkTrue("init accepted", true);

            codec.setVolume(-20.0);
            checkTrue("setVolume/getVolume round trip", Math.abs(codec.getVolume() - (-20.0)) < 0.01);
            codec.setMicGain(24.0);
            checkTrue("setMicGain/getMicGain round trip", Math.abs(codec.getMicGain() - 24.0) < 0.01);
            codec.setMute(true);
            checkTrue("DAC mute bits set", (codec.readRegister(0x31) & 0x60) == 0x60);
            codec.setMute(false);
            checkTrue("DAC mute bits cleared", (codec.readRegister(0x31) & 0x60) == 0x00);
            codec.configureClock(12288000, 16000, false, false);
            checkTrue("configureClock accepted", true);
            codec.configureSerial("i2s", 16);
            checkTrue("configureSerial reads back format/word length", (codec.readRegister(0x09) & 0x1F) == 0x0C);
            codec.setAdcVolume(0.0);
            checkTrue("ADC volume 0 dB (0xBF)", codec.readRegister(0x17) == 0xBF);
            codec.configureAlc(true, -12.0, -18.1, 0, true, 0, -60.0);
            checkTrue("ALC enabled bit", (codec.readRegister(0x18) & 0x80) == 0x80);
            codec.configureAlc(false);
            codec.configureDrc(true, -12.0, -24.1, 0);
            checkTrue("DRC enabled bit", (codec.readRegister(0x34) & 0x80) == 0x80);
            codec.configureDrc(false);
            codec.setLoopback(true);
            checkTrue("loopback bit set", (codec.readRegister(0x44) & 0x80) == 0x80);
            codec.setLoopback(false);
            codec.setAdcdatMode("dac_adc");
            checkTrue("ADCDAT mode dac_adc (4)", ((codec.readRegister(0x44) >> 4) & 7) == 4);
            codec.setAdcdatMode("adc_adc");
            codec.setDacRamp(8);
            checkTrue("DAC ramp rate 8", (codec.readRegister(0x37) >> 4) == 8);
            codec.powerDown();
            checkTrue("powerDown clears CSM_ON", (codec.readRegister(0x00) & 0x80) == 0x00);
            codec.reset();
            checkTrue("reset accepted", true);

            System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
