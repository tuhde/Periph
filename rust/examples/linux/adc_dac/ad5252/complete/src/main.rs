use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{AD5252Full, AD5252_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5252Full::new(dev, AD5252_I2C_ADDRESS, 10000.0, 75.0); // Create AD5252 Full driver, (i2c, addr=0x2C, r_ab=10000.0 Ω, r_wiper=75.0 Ω)

    pot.set_wiper(0, 100).expect("set_wiper"); // Set wiper position, (channel 0–1, code 0–255) → ()
                                               // writes [instruction, code]; the instruction byte selects the channel
    println!("{}", pot.get_wiper(0).expect("get_wiper")); // Read wiper position, (channel 0–1) → u8 0–255
                                                          // sends the instruction alone, then reads one byte
    pot.set_resistance(0, 5000.0).expect("set_resistance"); // Set W–B resistance, (channel 0–1, ohms Ω) → ()
                                                            // inverts R = D/256·RAB + RW to the nearest code
    println!("{}", pot.get_resistance(0).expect("get_resistance")); // Read W–B resistance, (channel 0–1) → f32 Ω
                                                                    // evaluates D/256·RAB + RW for the current code

    pot.set_both(32, 224).expect("set_both"); // Write both wipers, (code0 0–255, code1 0–255) → ()
                                              // one consecutive-write transaction (RDAC1 then RDAC3)
    pot.set_ratio(0, 0.25).expect("set_ratio"); // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → ()
                                                // code = round(ratio × 256), clamped to 255
    println!("{}", pot.get_ratio(0).expect("get_ratio")); // Read divider ratio, (channel 0–1) → f32
                                                          // code / 256
    pot.set_resistance_wa(1, 3000.0).expect("set_resistance_wa"); // Set W–A resistance, (channel 0–1, ohms Ω) → ()
                                                                  // rheostat between W and A with B open
    println!("{}", pot.get_resistance_wa(1).expect("get_resistance_wa")); // Read W–A resistance, (channel 0–1) → f32 Ω
                                                                          // (256 − D)/256·RAB + RW
    pot.increment(Some(0)).expect("increment"); // Step up one code, (channel Option 0–1, None = both) → ()
                                                // quick command; saturates at 255
    pot.decrement(Some(0)).expect("decrement"); // Step down one code, (channel Option 0–1, None = both) → ()
                                                // quick command; saturates at 0
    pot.double(Some(0)).expect("double"); // Raise by 6 dB, (channel Option 0–1, None = both) → ()
                                          // quick command; the code doubles, saturating at 255
    pot.halve(Some(0)).expect("halve"); // Lower by 6 dB, (channel Option 0–1, None = both) → ()
                                        // quick command; the code is halved

    pot.store(None, &mut Delay).expect("store"); // Store wipers in EEMEM, (channel Option 0–1, delay) → ()
                                                 // quick command, then ACK polling (≈ 26 ms)
    pot.set_both(1, 2).expect("set_both");
    pot.restore(None).expect("restore"); // Restore wipers from EEMEM, (channel Option 0–1) → ()
                                         // quick command followed by NOP
    println!("{} {}", pot.get_wiper(0).expect("get_wiper"), pot.get_wiper(1).expect("get_wiper"));
    pot.write_eemem(4, 0x5A, &mut Delay).expect("write_eemem"); // Write user EEMEM byte, (index 4–15, value, delay) → ()
                                                                // followed by ACK polling (≈ 26 ms)
    println!("{}", pot.read_eemem(4).expect("read_eemem")); // Read user EEMEM byte, (index 4–15) → u8
                                                            // pointer write, then a one-byte read
    println!("{}", pot.read_tolerance(0).expect("read_tolerance")); // Read stored RAB tolerance, (channel 0–1) → f32 %
                                                                    // sign-magnitude integer byte + decimal byte / 256
    println!("{}", pot.rab_actual(0).expect("rab_actual")); // Actual RAB of a channel, (channel 0–1) → f32 Ω
                                                            // r_ab × (1 + tolerance / 100)
    pot.nop().expect("nop"); // Send NOP, () → ()
                             // quick command 0x80; the chip returns to idle

    pot.set_rab(50000.0, Some(120.0)); // Change resistance model, (r_ab Ω, r_wiper Ω) → ()
                                       // affects conversions only; no I²C traffic
    sleep(Duration::from_secs(1));
    pot.reset(&mut Delay).expect("reset"); // Reset from EEMEM, (delay) → ()
                                           // quick command 0xB8, then ACK polling
}
