//! Complete example for the HS40xx — every Full API method.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::hs40xx::{AlertThresholds, HS40xxFull, SOURCE_RH_HIGH, SOURCE_TEMP_HIGH};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x54);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HS40xx driver, (i2c, addr=0x54) → HS40xx
    let mut chip = HS40xxFull::new(dev, addr);

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{} C, {} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32 °C>
    // no-hold 0xF3, 3 bytes
    println!("{}", chip.read_temperature(&mut delay).expect("read_temperature"));
    // Read humidity only, (delay) → Result<f32 %RH>
    println!("{}", chip.read_humidity(&mut delay).expect("read_humidity"));
    // Read with clock stretching, (delay) → Result<(f32, f32)>
    // 0xE5; needs a stretching controller and SCL >= 200 kHz
    println!("{:?}", chip.read_hold(&mut delay).expect("read_hold"));
    // Read raw values, (delay) → Result<(u16, u16)>
    // (humidity, temperature); 14-bit, CRC verified
    println!("{:?}", chip.read_raw(&mut delay).expect("read_raw"));
    // Set resolution, (humidity_bits, temperature_bits, delay) → Result<()>
    // 8, 10, 12 or 14 bit; None leaves a channel unchanged
    chip.set_resolution(Some(12), Some(12), &mut delay).expect("set_resolution");
    // Get resolution, (delay) → Result<(u8, u8)>
    // (humidity_bits, temperature_bits)
    println!("{:?}", chip.get_resolution(&mut delay).expect("get_resolution"));
    // Read a register, (reg, delay) → Result<u8>
    println!("{}", chip.read_register(0x00, &mut delay).expect("read_register"));
    // Write a register, (reg, value) → Result<()>
    // 0x0F = 14 bit for both channels (default)
    chip.write_register(0x00, 0x0F).expect("write_register");
    // Set alert thresholds, (&AlertThresholds) → Result<()>
    // %RH / °C; None leaves a threshold unchanged
    chip.set_alert_thresholds(&AlertThresholds { rh_high: Some(60.0), rh_high_reset: Some(55.0), temp_high: Some(40.0), temp_high_reset: Some(38.0), ..Default::default() }).expect("set_alert_thresholds");
    // Set ALERT polarity, (active_high, delay) → Result<()>
    chip.set_alert_polarity(true, &mut delay).expect("set_alert_polarity");
    // Enable alert sources, (source, delay) → Result<()>
    chip.enable_interrupt(SOURCE_RH_HIGH | SOURCE_TEMP_HIGH, &mut delay).expect("enable_interrupt");
    // Start periodic mode, (period_s, delay) → Result<()>
    // 0.5, 1 or 2.5 s
    chip.start_periodic(1.0, &mut delay).expect("start_periodic");
    sleep_ms!(1500);
    // Fetch the latest periodic result, (delay) → Result<Periodic>
    println!("{:?}", chip.read_periodic(&mut delay).expect("read_periodic"));
    // Read alert status, (delay) → Result<u8>
    // SOURCE_* bitmask
    println!("{}", chip.poll_interrupt(&mut delay).expect("poll_interrupt"));
    // Disable alert sources, (source, delay) → Result<()>
    chip.disable_interrupt(SOURCE_RH_HIGH | SOURCE_TEMP_HIGH, &mut delay).expect("disable_interrupt");
    // Stop periodic mode, () → Result<()>
    chip.stop_periodic().expect("stop_periodic");
    // Read the 32-bit sensor ID, (delay) → Result<u32>
    println!("{:#x}", chip.read_id(&mut delay).expect("read_id"));
    // Compute dew point, (delay) → Result<f32 °C>
    // one measurement, Magnus formula
    println!("{}", chip.dew_point(&mut delay).expect("dew_point"));
    // Soft reset, (delay) → Result<()>
    // stops periodic mode, waits 500 ms
    chip.soft_reset(&mut delay).expect("soft_reset");
}
