#include <cstdio>
#include <cstring>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CS43L22.h"

int main() {
    I2CConnectionLinux connection(1, CS43L22Full::I2C_ADDRESS);
    CS43L22Full dac(connection);                     // Create CS43L22 Full driver, (connection, resPin=nullptr)

    // --- Bring up the headphone amplifier ---
    // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
    // I2S stream come from the board's I2S master. init() loads the I2S-slave
    // defaults while the chip is powered down; start() powers it up once MCLK runs.
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.setVolume(-60.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.start();                                     // Power up the DAC once MCLK is running, () → void

    // --- Wait for the serial port to lock ---
    // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
    // reading the status register clears the flags.
    for (int i = 0; i < 50; i++) {
        CS43L22Status st = dac.readStatus();         // Read status flags (clears them), () → CS43L22Status
        if (!st.clockError) break;
        usleep((100) * 1000UL);
    }

    // --- Ramp the volume up ---
    // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
    // smooths each step further.
    for (int vol = -60; vol <= 0; vol += 2) {
        dac.setVolume((float)vol);                   // Set master volume, (db -102–12 dB) → void
        usleep((100) * 1000UL);
    }

    // --- Shape the tone and protect the headphones ---
    // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
    // loud peaks from clipping the amplifier.
    dac.setTone(6.0f, 6.0f);                         // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → bool
    dac.setLimiter(true, -6, -12);                   // Configure the peak limiter, (enabled, maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false) → bool

    // --- Play a single beep ---
    // The beep generator needs no audio data; it mixes into the output once MCLK
    // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
    dac.setBeep(0x1, 2, 0, -6.0f, CS43L22Full::BEEP_SINGLE); // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → bool
    usleep((1500) * 1000UL);
    dac.stopBeep();                                  // Stop the beep generator, () → void

    // --- Power down safely ---
    // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
    // only then may the application remove MCLK.
    dac.stop();                                      // Power down the DAC, () → void
    printf("MCLK may now be removed\n");
    return 0;
}
