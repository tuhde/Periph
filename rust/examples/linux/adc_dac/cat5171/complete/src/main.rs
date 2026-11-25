// Complete example: exercises every method of CAT5171Full.

use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{CAT5171Full, CAT5171_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = CAT5171Full::new(dev, CAT5171_I2C_ADDRESS, 100000.0, 50.0); // Create CAT5171 Full driver, (i2c, addr=0x2C, r_ab=100000.0 Ω, r_wiper=50.0 Ω)
    pot.set_wiper(100).expect("set_wiper"); // Set wiper position, (code 0–255) → ()
    // writes [instruction, code] with RS = 0, SD = 0
    println!("{}", pot.get_wiper().expect("get_wiper")); // Read wiper position, () → u8 0–255
    // bare one-byte read; no instruction byte is sent
    pot.set_resistance(50000.0).expect("set_resistance"); // Set W–B resistance, (ohms Ω) → ()
    // inverts R = D/256·RAB + RW to the nearest code
    println!("{}", pot.get_resistance().expect("get_resistance")); // Read W–B resistance, () → f32 Ω
    // evaluates D/256·RAB + RW for the current code
    pot.set_ratio(0.25).expect("set_ratio"); // Set divider ratio, (ratio 0.0–1.0) → ()
    // code = round(ratio × 256), clamped to 255
    println!("{}", pot.get_ratio().expect("get_ratio")); // Read divider ratio, () → f32
    // code / 256
    pot.set_resistance_wa(30000.0).expect("set_resistance_wa"); // Set W–A resistance, (ohms Ω) → ()
    // rheostat between W and A with B open
    println!("{}", pot.get_resistance_wa().expect("get_resistance_wa")); // Read W–A resistance, () → f32 Ω
    // (256 − D)/256·RAB + RW
    println!("{}", pot.increment(5).expect("increment")); // Step the wiper, (steps) → u8 0–255
    // read, add, clamp, write; returns the new code
    pot.midscale().expect("midscale"); // Move wiper to midscale, () → ()
    // RS = 1 instruction; wiper goes to 0x80
    pot.shutdown().expect("shutdown"); // Shut down, () → ()
    // SD = 1: A opens, wiper shorts to B, WCR kept
    println!("{}", pot.is_shutdown()); // Query shutdown state, () → bool
    // driver-tracked; the chip cannot report it
    sleep(Duration::from_secs(1));
    pot.resume().expect("resume"); // Leave shutdown, () → ()
    // rewrites the kept code with SD = 0
    pot.set_rab(50000.0, Some(120.0)); // Change resistance model, (r_ab Ω, r_wiper=None Ω) → ()
    // affects conversions only; no I²C traffic
    pot.reset().expect("reset"); // Midscale reset, () → ()
    // RS = 1, SD = 0; wiper returns to 0x80
}
