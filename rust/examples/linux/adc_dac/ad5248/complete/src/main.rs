use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Ad5248Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x2C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = Ad5248Full::new(dev, addr, 10000.0, 160.0);      // Create AD5248 driver, (i2c, addr, r_ab 10000 Ω, r_wiper 160 Ω) → Ad5248Full

    // --- Wiper position ---
    pot.set_wiper(0, 100).unwrap();                                 // Set wiper, (channel 0-1, code 0-255) → ()
                                                                    // writes [channel << 7, code]; code clamped to 0-255
    println!("{}", pot.get_wiper(0).unwrap());                      // Read wiper, (channel 0-1) → u8 0-255
                                                                    // sends the instruction byte alone, then reads one byte
    pot.set_both(10, 20).unwrap();                                  // Set both wipers, (code1 0-255, code2 0-255) → ()
                                                                    // two transactions, one per channel

    // --- Resistance ---
    pot.set_resistance(1, 2200.0).unwrap();                         // Set W-B resistance, (channel 0-1, ohms) → ()
                                                                    // nearest code of R = code/256*RAB + 2*RW, clamped
    println!("{}", pot.get_resistance(1).unwrap());                 // Read W-B resistance, (channel 0-1) → f32 Ω
                                                                    // code/256*RAB + 2*RW

    // --- Ratio and increment ---
    pot.set_ratio(0, 0.5).unwrap();                                 // Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → ()
                                                                    // code = round(ratio*256) clamped to 255; needs terminal A wired
    println!("{}", pot.get_ratio(0).unwrap());                      // Read wiper ratio, (channel 0-1) → f32 0.0-1.0
                                                                    // code / 256
    println!("{}", pot.increment(0, 5).unwrap());                   // Step wiper, (channel 0-1, steps) → u8 0-255
                                                                    // read-modify-write, clamped to 0-255; negative steps decrement

    // --- Shutdown ---
    pot.shutdown(1).unwrap();                                       // Shut channel down, (channel 0-1) → ()
                                                                    // opens terminal A, shorts wiper to B, RDAC kept
    println!("{}", pot.is_shutdown(1));                             // Query driver-tracked state, (channel 0-1) → bool
                                                                    // the chip cannot be read back
    pot.resume(1).unwrap();                                         // Leave shutdown, (channel 0-1) → ()
                                                                    // rewrites the RDAC with SD = 0

    // --- Model and reset ---
    pot.set_rab(10000.0, Some(160.0));                              // Set resistance model, (r_ab Ω, r_wiper Option<Ω>) → ()
                                                                    // no I²C traffic; affects conversions only
    pot.reset().unwrap();                                           // Reset to power-up preset, () → ()
                                                                    // writes midscale 0x80 to both channels with SD = 0
}
