// Demo: headphone amplifier bring-up with volume ramp, tone, limiter and beep.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::cs43l22::*;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Bring up the headphone amplifier ---
    // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
    // I2S stream come from the board's I2S master. init() loads the I2S-slave
    // defaults while the chip is powered down; start() powers it up once MCLK runs.
    let mut dac = CS43L22Full::new(dev, CS43L22_I2C_ADDRESS); // Create CS43L22 Full driver, (i2c, addr=0x4A)
    dac.init().expect("init"); // Verify ID, load defaults, () → ()
    println!("{:?}", dac.read_id().expect("read_id")); // Read chip ID and revision, () → (u8, u8)
    dac.set_volume(-60.0).expect("set_volume"); // Set master volume, (db -102–12 dB) → ()
    dac.start().expect("start"); // Power up the DAC once MCLK is running, () → ()

    // --- Wait for the serial port to lock ---
    // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
    // reading the status register clears the flags.
    for _ in 0..50 {
        if !dac.read_status().expect("read_status").clock_error { // Read status flags (clears them), () → Status
            break;
        }
        sleep(Duration::from_millis(100));
    }

    // --- Ramp the volume up ---
    // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
    // smooths each step further.
    for vol in (-60..=0).step_by(2) {
        dac.set_volume(vol as f32).expect("set_volume"); // Set master volume, (db -102–12 dB) → ()
        sleep(Duration::from_millis(100));
    }

    // --- Shape the tone and protect the headphones ---
    // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
    // loud peaks from clipping the amplifier.
    dac.set_tone(6.0, 6.0, 0, 0, true).expect("set_tone"); // Configure treble / bass shelves, (treble_db, bass_db, treble_corner, bass_corner, enabled) → ()
    dac.set_limiter(&Limiter { enabled: true, max_db: -6, cushion_db: -12, ..Limiter::default() }).expect("set_limiter"); // Configure the peak limiter, (&Limiter) → ()

    // --- Play a single beep ---
    // The beep generator needs no audio data; it mixes into the output once MCLK
    // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
    dac.set_beep(0x1, 2, 0, -6.0, BEEP_SINGLE, true).expect("set_beep"); // Play a beep, (frequency 0–15, on_time 0–15, off_time 0–7, volume_db, mode, mix) → ()
    sleep(Duration::from_millis(1500));
    dac.stop_beep().expect("stop_beep"); // Stop the beep generator, () → ()

    // --- Power down safely ---
    // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
    // only then may the application remove MCLK.
    dac.stop(&mut Delay).expect("stop"); // Power down the DAC, (delay) → ()
    println!("MCLK may now be removed");
}
