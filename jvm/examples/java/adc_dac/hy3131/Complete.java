///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Hy3131Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 0, 1_000_000)) {                              // Open SPI bus 0, CS 0, Mode 0, 1 MHz, (bus, device, mode=0, maxSpeedHz=1_000_000) → SPIConnection
            var adc = new Hy3131Full(connection, 1.2f);                                             // Create HY3131 driver, (connection, vref=1.2 V) → Hy3131Full
            adc.configureAd1(4, 2, 0, 0, 0, 0);                                                     // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → void
                                                                                                    // OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
            adc.setAd1Input(10, 3, 2);                                                              // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filterR=2) → void
                                                                                                    // PB2 vs AGND with the filter resistor bypassed
            adc.setAd1Reference(1, 1);                                                              // Select AD1 reference, (high=0–6, low=0–6) → void
                                                                                                    // REFO (+) / AGND (−)

            int raw = adc.readRaw();                                                                // Read AD1 code, () → int 24-bit
                                                                                                    // waits for AD1F in INTF, returns the sign-extended code
            float v = adc.readVoltage();                                                            // Read AD1 voltage, () → float V
                                                                                                    // code / 2^23 · RG − DCOS, scaled by vref / input gain
            System.out.println("raw=" + raw + " v=" + v);

            int tcode = adc.readTemperatureCode();                                                  // Read temperature code, () → int
                                                                                                    // (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
            float t = adc.readTemperature(tcode);                                                   // Read die temperature, (tcode25) → float °C
                                                                                                    // one-point calibrated against tcode25
            System.out.println("temperature=" + t);

            adc.enableAd2(5, 1, 0, false, false, 0, false);                                         // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAd3=false) → void
                                                                                                    // AD2 samples OP1O at gain 1.0, OSR 1024
            int ad2 = adc.readAd2Raw();                                                             // Read AD2 code, () → int 19-bit
                                                                                                    // waits for AD2F
            adc.enableLpf(0);                                                                       // Enable low-pass filter, (bw=0) → void
                                                                                                    // OSR4 = 128
            int lpf = adc.readLpfRaw();                                                             // Read LPF code, () → int 19-bit
                                                                                                    // waits for LPFF
            adc.enableRms();                                                                        // Enable RMS converter, () → void
                                                                                                    // accumulates the squared LPF input
            float rms = adc.readRms();                                                              // Read RMS, () → float LSB
                                                                                                    // square root of the accumulator
            adc.enablePeakHold(0);                                                                  // Enable peak hold, (source=0) → void
                                                                                                    // tracks AD2 min/max; re-enabling resets
            int[] peak = adc.readPeak();                                                            // Read peak hold, () → int[2] {min, max} 19-bit
            System.out.println("ad2=" + ad2 + " lpf=" + lpf + " rms=" + rms + " peak=" + peak[0] + ".." + peak[1]);

            adc.startCounter(0);                                                                    // Start frequency counter, (gatePreload=0) → void
                                                                                                    // enables the CNT buffer and the counter
            long[] ct = adc.readCounter();                                                          // Read counter, () → long[4] {cta, ctb, ctc, overflow}
                                                                                                    // waits for CTF; overflow=1 means the measurement is invalid
            System.out.println("counter=" + ct[0] + " " + ct[1] + " " + ct[2] + " overflow=" + ct[3]);

            adc.enableInterrupts(0x04);                                                             // Enable interrupts, (mask) → void
                                                                                                    // AD1IE: DO goes high while CS is high and AD1F is set
            int flags = adc.readFlags();                                                            // Read and clear INTF, () → int
                                                                                                    // bit 2 = AD1F
            System.out.println("flags=" + flags);

            adc.reset();                                                                            // Software reset, () → void
                                                                                                    // writes 0x60 to R37 and re-applies the Minimal defaults
        }
    }
}
