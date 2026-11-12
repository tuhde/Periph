///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS1881Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1
def store = args.length > 0 && args[0] == "--store"

def connection = new I2CConnection(bus, DS1881Full.DEFAULT_ADDRESS)                             // open I²C bus, device address, (bus, address=0x28) → I2CConnection
try {
    def amp = new DS1881Full(connection)                                                            // construct driver, (connection) → DS1881Full
    amp.init()                                                                                      // Apply defaults, () → void

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    for (int step = 0; step < 41; step++) {
        double db = 2.0 * Math.min(step, 40 - step)
        amp.setAttenuationBoth(db)                                                                  // Set both attenuations, (db0 dB, db1=db0 dB) → void
        int[] pos = amp.readAll()                                                                   // Read everything, () → int[] {position0, position1, config}
        println("" + db + " dB -> positions " + pos[0] + " " + pos[1])
        Thread.sleep(100)
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0)                                                                                     // Mute channel, (channel 0–1) → void
    println("channel 0 muted: " + amp.getAttenuation(0))                                            // Read attenuation, (channel 0–1) → double dB
    Thread.sleep(1000)
    amp.setAttenuation(0, 0.0)                                                                      // Set attenuation, (channel 0–1, db dB) → void

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.setOption(2)                                                                                // Select option, (option 1–2) → void
    for (int position : [0, 12, 13, 24, 25, 32, 33] as int[]) {
        println("position " + position + " -> " + amp.positionToDb(position) + " dB")               // Table lookup, (position) → double dB
    }
    amp.setOption(1)                                                                                // Select option, (option 1–2) → void
    amp.setAttenuationBoth(10.0)                                                                    // Set both attenuations, (db0 dB, db1=db0 dB) → void
    def cfg = amp.getConfig()                                                                       // Read configuration, () → Config {option, zeroCrossing, nonvolatile, raw}
    println("option " + cfg.option + " zc " + cfg.zeroCrossing + " nv " + cfg.nonvolatile)

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested with --store.
    if (store) {
        println("WARNING: EEPROM endurance is 50 000 writes")
        amp.setNonvolatile(true)                                                                    // Select EEPROM storage, (enabled bool) → void
        amp.store()                                                                                 // Store positions, () → void
        amp.setNonvolatile(false)                                                                   // Select EEPROM storage, (enabled bool) → void
    }
} finally {
    connection.close()
}
