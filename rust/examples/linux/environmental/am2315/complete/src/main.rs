//! Complete example for the AM2315 — every API method of `AM2315Full`.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::environmental::AM2315Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AM2315 driver, (i2c, addr=0x5C) → AM2315
    let mut chip = AM2315Full::new(dev, addr);

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // previous measurement; the driver waits 2 s before every transaction, CRC-16 checked
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.1} C, {:.1} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32 °C>
    println!("temperature {:.1} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Read humidity only, (delay) → Result<f32 %RH>
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read raw words, (delay) → Result<(u16, u16)> — (humidity x10, temperature x10 as sign + magnitude)
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));
    // Real-time value, (delay) → Result<(f32, f32)> — two reads, returns the second
    let (t, rh) = chip.read_fresh(&mut delay).expect("read_fresh");
    println!("fresh {:.1} C, {:.1} %RH", t, rh);
    // Read registers, (start, out, delay) → Result<()> — function 0x03, 1-10 registers, CRC verified
    let mut regs = [0u8; 7];
    chip.read_registers(0x08, &mut regs, &mut delay).expect("read_registers");
    println!("registers 0x08.. {:02X?}", regs);
    // Read device information, (delay) → Result<(u16 model, u8 version, u32 device_id)>
    println!("device info {:?}", chip.read_device_info(&mut delay).expect("read_device_info"));
    // Write user registers, (user1, user2, delay) → Result<()>
    chip.write_user_registers(0x1234, 0xABCD, &mut delay).expect("write_user_registers");
    // Read user registers, (delay) → Result<(u16, u16)>
    println!("user registers {:04X?}", chip.read_user_registers(&mut delay).expect("read_user_registers"));
    // Write registers, (start, data, delay) → Result<()> — only the user registers 0x10-0x13 are allowed
    chip.write_registers(0x10, &[0x00, 0x01], &mut delay).expect("write_registers");
    // Wake the sensor, (delay) → () — dummy write, the NACK is ignored
    chip.wake(&mut delay);
    println!("done");
}
