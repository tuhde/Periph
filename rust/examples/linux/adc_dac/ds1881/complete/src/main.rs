// Complete example: exercises every method of DS1881Full.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS1881Full, DS1881_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut amp = DS1881Full::new(dev, DS1881_I2C_ADDRESS); // Create DS1881 Full driver, (i2c, addr=0x28)
    amp.init(&mut Delay).expect("init"); // Apply defaults, (delay) → ()
    // waits 1 ms, rewrites the config only if it differs

    amp.set_attenuation(0, 12.0).expect("set_attenuation"); // Set attenuation, (channel 0–1, db dB) → ()
    // nearest position, one command byte
    println!("{}", amp.get_attenuation(0).expect("get_attenuation")); // Read attenuation, (channel 0–1) → f32 dB
    // three-byte round-robin read, position in dB
    amp.set_position(1, 30).expect("set_position"); // Set raw wiper position, (channel 0–1, position 0–63) → ()
    // position 63 is mute in Option 1
    println!("{}", amp.get_position(1).expect("get_position")); // Read wiper position, (channel 0–1) → u8
    // low six bits of the read-back byte
    amp.mute(1).expect("mute"); // Mute channel, (channel 0–1) → ()
    // writes the mute position (63)

    amp.set_both(5, 25).expect("set_both"); // Set both wipers, (position0 0–63, position1 0–63) → ()
    // one transaction, both wipers update together
    amp.set_attenuation_both(20.0, None).expect("set_attenuation_both"); // Set both attenuations, (db0 dB, db1=None dB) → ()
    // db1 defaults to db0 (stereo volume)
    println!("{:?}", amp.read_all().expect("read_all")); // Read everything, () → (u8, u8, u8) (position0, position1, config)
    // one three-byte round-robin read

    amp.configure(2, true, false).expect("configure"); // Configure, (option 1–2, zero_crossing bool, nonvolatile bool) → ()
    // writes the config byte only if it changed
    println!("{:?}", amp.get_config().expect("get_config")); // Read configuration, () → Ds1881Config {option, zero_crossing, nonvolatile, raw}
    // fresh read; refreshes the driver's table
    println!("{} {}", amp.position_to_db(13), amp.max_position()); // Table lookup, (position) → f32 dB; Last non-mute position, () → u8
    // Option 2: 13 → 14 dB, max position 32
    println!("{}", amp.db_to_position(39.0)); // Nearest position, (db dB) → u8
    // 70 dB and above map to mute
    amp.set_option(1).expect("set_option"); // Select option, (option 1–2) → ()
    // positions are not converted
    amp.set_zero_crossing(false).expect("set_zero_crossing"); // Set zero-crossing, (enabled bool) → ()
    // ZC bit of the configuration
    amp.set_zero_crossing(true).expect("set_zero_crossing"); // Set zero-crossing, (enabled bool) → ()
    // clickless updates again
    amp.wait_ready(&mut Delay, 50).expect("wait_ready"); // Acknowledge-poll, (delay, timeout_ms=50 ms) → ()
    // returns once the chip ACKs its address

    amp.set_nonvolatile(true).expect("set_nonvolatile"); // Select EEPROM storage, (enabled bool) → ()
    // V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
    amp.store().expect("store"); // Store positions, () → ()
    // rewrites both wipers and polls for completion
    amp.set_nonvolatile(false).expect("set_nonvolatile"); // Select EEPROM storage, (enabled bool) → ()
    // back to volatile wipers
    sleep(Duration::from_secs(1));
    amp.reset().expect("reset"); // Reset, () → ()
    // volatile, zero-crossing, Option 1, both wipers muted
}
