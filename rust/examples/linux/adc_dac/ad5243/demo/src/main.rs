// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 2.2 kΩ rheostat and reports the
// quantisation error; demonstrates shutdown/resume and reset.

use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{AD5243Full, AD5243_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

const VDD: f32 = 3.3;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5243Full::new(dev, AD5243_I2C_ADDRESS, 10000.0, 160.0); // Create AD5243 Full driver, (i2c, addr=0x2F, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for ratio in [0.0f32, 0.25, 0.5, 0.75, 0.999] {
        pot.set_ratio(0, ratio).expect("set_ratio"); // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → ()
        let code = pot.get_wiper(0).expect("get_wiper"); // Read wiper position, (channel 0–1) → u8 0–255
        let actual = pot.get_ratio(0).expect("get_ratio"); // Read divider ratio, (channel 0–1) → f32
        println!("ratio {:.3} -> code {:3} ({:.4}) -> {:.3} V", ratio, code, actual, actual * VDD);
        sleep(Duration::from_millis(500));
    }

    // --- Use channel 1 as a 2.2 kΩ rheostat ---
    // The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
    // the readback differs slightly from the request.
    pot.set_resistance(1, 2200.0).expect("set_resistance"); // Set W–B resistance, (channel 0–1, ohms Ω) → ()
    let r = pot.get_resistance(1).expect("get_resistance"); // Read W–B resistance, (channel 0–1) → f32 Ω
    println!("rheostat {:.1} Ω, error {:.1} Ω", r, r - 2200.0);

    // --- Shut channel 1 down and bring it back ---
    // Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
    // its value, so resume restores the 2.2 kΩ setting.
    pot.shutdown(1).expect("shutdown"); // Shut channel down, (channel 0–1) → ()
    println!("shutdown: {}", pot.is_shutdown(1).expect("is_shutdown")); // Query shutdown state, (channel 0–1) → bool
    sleep(Duration::from_secs(1));
    pot.resume(1).expect("resume"); // Leave shutdown, (channel 0–1) → ()

    // --- Restore the power-up state ---
    // The chip has no reset pin, so reset() rewrites midscale to both channels.
    pot.reset().expect("reset"); // Restore power-up preset, () → ()
}
