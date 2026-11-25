// Programmable level trimmer: sweeps the wiper in steps of 16 printing the
// W–B resistance, sets a 22 kΩ rheostat and reports the quantisation error,
// demonstrates shutdown/resume, midscale and reset, and steps the wiper as a
// voltage divider (A at VDD, B at GND) printing the expected output voltage
// at VDD = 3.3 V.

use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{CAT5171Full, CAT5171_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

const VDD: f32 = 3.3;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = CAT5171Full::new(dev, CAT5171_I2C_ADDRESS, 100000.0, 50.0); // Create CAT5171 Full driver, (i2c, addr=0x2C, r_ab=100000.0 Ω, r_wiper=50.0 Ω)

    // --- Sweep the wiper across the full range ---
    // Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
    // LSB below the RAB end.
    for code in (0..256).step_by(16) {
        pot.set_wiper(code).expect("set_wiper"); // Set wiper position, (code 0–255) → ()
        let r = pot.get_resistance().expect("get_resistance"); // Read W–B resistance, () → f32 Ω
        println!("code {:3} -> {:.1} Ω", code, r);
        sleep(Duration::from_millis(200));
    }

    // --- Set a 22 kΩ rheostat ---
    // The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
    // so the readback differs slightly from the request.
    pot.set_resistance(22000.0).expect("set_resistance"); // Set W–B resistance, (ohms Ω) → ()
    let r = pot.get_resistance().expect("get_resistance"); // Read W–B resistance, () → f32 Ω
    println!("rheostat {:.1} Ω, error {:.1} Ω", r, r - 22000.0);

    // --- Shutdown, resume, midscale and reset ---
    // Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
    // so resume restores the setting; midscale and reset both return to 0x80.
    let kept = pot.get_wiper().expect("get_wiper"); // Read wiper position, () → u8 0–255
    pot.shutdown().expect("shutdown"); // Shut down, () → ()
    println!("shutdown: {} kept {}", pot.is_shutdown(), pot.get_wiper().expect("get_wiper") == kept); // Query shutdown state, () → bool
    sleep(Duration::from_secs(1));
    pot.resume().expect("resume"); // Leave shutdown, () → ()
    pot.midscale().expect("midscale"); // Move wiper to midscale, () → ()
    pot.reset().expect("reset"); // Midscale reset, () → ()

    // --- Use the wiper as a voltage divider ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for ratio in [0.0f32, 0.25, 0.5, 0.75, 0.999] {
        pot.set_ratio(ratio).expect("set_ratio"); // Set divider ratio, (ratio 0.0–1.0) → ()
        let actual = pot.get_ratio().expect("get_ratio"); // Read divider ratio, () → f32
        println!("ratio {:.3} -> {:.4} -> {:.3} V", ratio, actual, actual * VDD);
        sleep(Duration::from_millis(500));
    }
}
