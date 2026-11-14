//! Complete example for the Si7021 — every Si7021Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::Si7021Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create Si7021 driver, (i2c, addr=0x40) → Si7021
    let mut chip = Si7021Full::new(dev, addr);

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // RH without hold master, temperature of the same conversion
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32> %RH
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read temperature (new conversion), (delay) → Result<f32> °C
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Temperature of the last RH measurement, () → Result<f32> °C
    // command 0xE0, no new conversion
    println!("previous {:.2} C", chip.read_temperature_previous().expect("previous"));
    // Read raw codes, (delay) → Result<u16>
    // 16-bit codes, status bits included
    println!("raw {} {}", chip.read_raw_humidity(&mut delay).expect("raw rh"), chip.read_raw_temperature(&mut delay).expect("raw t"));

    // Set resolution, (rh_bits, temperature_bits) → Result<()>
    // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    chip.set_resolution(11, 11).expect("set_resolution");
    // Get resolution, () → Result<(u8, u8)>
    println!("resolution {:?}", chip.get_resolution().expect("get_resolution"));
    // Typical conversion time, () → Result<f32> s
    println!("conversion time {:.4} s", chip.conversion_time().expect("conversion_time"));
    chip.set_resolution(12, 14).expect("set_resolution");

    // Switch the heater, (on) → Result<()>
    // about 3.1 mA; readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");
    // Supply low flag, () → Result<bool>
    // true when VDD is below about 1.9 V
    println!("VDD low {}", chip.vdd_low().expect("vdd_low"));
    // Read USER_REG_1, () → Result<u8>
    // 0x3A after reset
    let reg = chip.read_user_register().expect("read_user_register");
    // Write USER_REG_1, (value) → Result<()>
    // reserved bits are preserved
    chip.write_user_register(reg).expect("write_user_register");
    // Use hold-master commands, (on) → ()
    // needs bus-layer support; off polls the NACKed read header
    chip.set_clock_stretching(false);

    // Electronic serial number, () → Result<u64>
    println!("serial number 0x{:016X}", chip.serial_number().expect("serial_number"));
    // Device type, () → Result<u8>
    // 0x15 = Si7021
    println!("device type 0x{:02X}", chip.device_type().expect("device_type"));
    // Firmware revision, () → Result<u8>
    // 0xFF = firmware 1.0
    println!("firmware 0x{:02X}", chip.firmware_revision().expect("firmware_revision"));
    // Compute dew point, (delay) → Result<f32> °C
    // one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Software reset, (delay) → Result<()>
    // 0xFE, waits 15 ms
    chip.software_reset(&mut delay).expect("software_reset");
}
