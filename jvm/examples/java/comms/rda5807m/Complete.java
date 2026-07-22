///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.comms.Rda5807mFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x10)) {              // open I²C bus 1, device 0x10, (bus, address) → I2CTransport
            var fm = new Rda5807mFull(transport, 100.0, 8);             // construct driver, (transport, frequencyMhz=100.0, volume=8) → Rda5807mFull
                                                                       // runs the init sequence and tunes to the initial frequency

            fm.setFrequency(97.5);                                     // tune to frequency, (frequencyMhz) → void
                                                                       // computes CHAN from the current band/spacing and blocks until STC
            System.out.printf("frequency=%.2f MHz%n", fm.frequency()); // read tuned frequency, () → double MHz
                                                                       // converts READCHAN back to MHz

            fm.setVolume(10);                                          // set volume, (level 0-15) → void
            fm.mute(false);                                            // mute/unmute, (enable) → void
                                                                       // enable=true mutes; here we ensure audio is audible

            Double freq = fm.seek(true);                               // seek next station, (up=true) → Double or null
                                                                       // blocks until STC; returns null if SF (seek fail) is set
            System.out.println("seek=" + freq);

            fm.configure(Rda5807mFull.BAND_WORLD, Rda5807mFull.SPACE_100K, true, 8, true, null, null, null);
                                                                       // configure tuner, (band, space, deEmphasis, seekThreshold, seekMode, clkMode, afcDisable, eastEurope50m) → void
                                                                       // re-tunes to the current frequency if band or space changed

            fm.setBassBoost(true);                                     // enable bass boost, (enable) → void
            fm.setMono(false);                                         // force mono/allow stereo, (enable) → void
            fm.setSoftmute(true);                                      // enable soft mute, (enable) → void

            fm.enableRds(true);                                        // enable RDS/RBDS, (enable) → void
            Thread.sleep(1000);
            System.out.println("rdsReady=" + fm.rdsReady());           // check RDS group ready, () → boolean
            int[] group = fm.readRdsGroup();                           // read raw RDS blocks, () → int[4] or null
            if (group != null) {
                System.out.println("rdsGroup=" + java.util.Arrays.toString(group));
            }

            System.out.println("isStereo=" + fm.isStereo());          // check stereo indicator, () → boolean
            System.out.println("isStation=" + fm.isStation());        // check real station, () → boolean
            System.out.println("isReady=" + fm.isReady());            // check tuner ready, () → boolean
            System.out.println("signalStrength=" + fm.signalStrength()); // read RSSI, () → int 0-127

            fm.standby(true);                                          // power down/up, (enable) → void
            Thread.sleep(10);
            fm.standby(false);

            fm.softReset();                                            // pulse soft reset, () → void
        }
    }
}
