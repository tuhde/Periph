use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{AD5243Full, AD5243_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5243Full::new(dev, AD5243_I2C_ADDRESS, 10000.0, 160.0); // Create AD5243 Full driver, (i2c, addr=0x2F, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

    pot.set_wiper(0, 100).expect("set_wiper"); // Set wiper position, (channel 0–1, code 0–255) → ()
                                               // writes [instruction, code]; the instruction byte selects the channel
    println!("{}", pot.get_wiper(0).expect("get_wiper")); // Read wiper position, (channel 0–1) → u8 0–255
                                                          // sends the instruction alone, then reads one byte
    pot.set_resistance(0, 5000.0).expect("set_resistance"); // Set W–B resistance, (channel 0–1, ohms Ω) → ()
                                                            // inverts R = D/256·RAB + 2·RW to the nearest code
    println!("{}", pot.get_resistance(0).expect("get_resistance")); // Read W–B resistance, (channel 0–1) → f32 Ω
                                                                    // evaluates D/256·RAB + 2·RW for the current code

    pot.set_both(32, 224).expect("set_both"); // Write both wipers, (code1 0–255, code2 0–255) → ()
                                              // two transactions, channel 0 then channel 1
    pot.set_ratio(0, 0.25).expect("set_ratio"); // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → ()
                                                // code = round(ratio × 256), clamped to 255
    println!("{}", pot.get_ratio(0).expect("get_ratio")); // Read divider ratio, (channel 0–1) → f32
                                                          // code / 256
    pot.set_resistance_wa(1, 3000.0).expect("set_resistance_wa"); // Set W–A resistance, (channel 0–1, ohms Ω) → ()
                                                                  // rheostat between W and A with B open
    println!("{}", pot.get_resistance_wa(1).expect("get_resistance_wa")); // Read W–A resistance, (channel 0–1) → f32 Ω
                                                                          // (256 − D)/256·RAB + 2·RW
    println!("{}", pot.increment(0, 5).expect("increment")); // Step the wiper, (channel 0–1, steps) → u8 0–255
                                                             // read, add, clamp, write; returns the new code

    pot.shutdown(1).expect("shutdown"); // Shut channel down, (channel 0–1) → ()
                                        // SD = 1: A opens, wiper shorts to B, RDAC kept
    println!("{}", pot.is_shutdown(1).expect("is_shutdown")); // Query shutdown state, (channel 0–1) → bool
                                                              // driver-tracked; the chip cannot report it
    sleep(Duration::from_secs(1));
    pot.resume(1).expect("resume"); // Leave shutdown, (channel 0–1) → ()
                                    // rewrites the kept code with SD = 0

    pot.set_rab(50000.0, Some(120.0)); // Change resistance model, (r_ab Ω, r_wiper Ω) → ()
                                       // affects conversions only; no I²C traffic
    pot.reset().expect("reset"); // Restore power-up preset, () → ()
                                 // writes 0x80 to both channels with SD = 0
}
