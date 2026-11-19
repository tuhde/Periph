//! Complete example for the AS7262.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::AS7262Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x49);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AS7262 driver, (i2c, addr=0x49) → AS7262
    let mut chip = AS7262Full::new(dev, addr);
    // Reset and configure defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // Read calibrated channels, (delay) → Result<Channels<f32>>   (one one-shot conversion)
    println!("{:?}", chip.read(&mut delay).expect("read"));
    // Read raw counts, (delay) → Result<Channels<u16>>
    println!("{:?}", chip.read_raw(&mut delay).expect("read_raw"));
    // Read device temperature, (delay) → Result<f32 °C>
    println!("{}", chip.temperature(&mut delay).expect("temperature"));
    // Start a one-shot conversion, (delay) → Result<()>   (does not wait)
    chip.measure(&mut delay).expect("measure");
    // Check data ready, (delay) → Result<bool>
    while !chip.data_ready(&mut delay).expect("data_ready") { sleep_ms!(50); }
    // Read last calibrated results, (delay) → Result<Channels<f32>>
    println!("{:?}", chip.read_calibrated(&mut delay).expect("read_calibrated"));
    // Read last raw counts, (delay) → Result<Channels<u16>>
    println!("{:?}", chip.read_raw_last(&mut delay).expect("read_raw_last"));
    // Read one channel, (index 0..5, delay) → Result<f32>   (V, B, G, Y, O, R)
    println!("{}", chip.read_channel(5, &mut delay).expect("read_channel"));
    // Read one raw channel, (index 0..5, delay) → Result<u16>
    println!("{}", chip.read_channel_raw(5, &mut delay).expect("read_channel_raw"));
    // Set gain, (gain, delay) → Result<()>   (index 0-3 or 1, 3.7, 16, 64)
    chip.set_gain(16.0, &mut delay).expect("set_gain");
    // Get gain, (delay) → Result<f32>
    println!("{}", chip.get_gain(&mut delay).expect("get_gain"));
    // Set integration time, (ms, delay) → Result<()>   (2.8 - 714 ms)
    chip.set_integration_time(165.2, &mut delay).expect("set_integration_time");
    // Get integration time, (delay) → Result<f32 ms>
    println!("{}", chip.get_integration_time(&mut delay).expect("get_integration_time"));
    // Set bank mode, (mode, delay) → Result<()>   (0, 1, 2 continuous; 3 one-shot)
    chip.set_mode(3, &mut delay).expect("set_mode");
    // Get bank mode, (delay) → Result<u8>
    println!("{}", chip.get_mode(&mut delay).expect("get_mode"));
    // Continuous conversions, (mode, delay) → Result<()>
    chip.set_continuous(2, &mut delay).expect("set_continuous");
    chip.set_mode(3, &mut delay).expect("set_mode");
    // Enable/disable INT pin, (enabled, delay) → Result<()>
    chip.set_interrupt(false, &mut delay).expect("set_interrupt");
    // Driver LED, (on, current_ma, delay) → Result<()>   (12.5, 25, 50 or 100 mA)
    chip.set_led(true, 25.0, &mut delay).expect("set_led");
    chip.set_led(false, 25.0, &mut delay).expect("set_led");
    // Indicator LED, (on, current_ma, delay) → Result<()>   (1, 2, 4 or 8 mA)
    chip.set_indicator_led(true, 4.0, &mut delay).expect("set_indicator_led");
    chip.set_indicator_led(false, 4.0, &mut delay).expect("set_indicator_led");
    // Hardware version, (delay) → Result<(u8 type, u8 version)>
    println!("{:?}", chip.hardware_version(&mut delay).expect("hardware_version"));
    // Firmware version, (delay) → Result<(u8 major, u8 minor, u8 sub)>
    println!("{:?}", chip.firmware_version(&mut delay).expect("firmware_version"));
    // Device type, (delay) → Result<u8>   (0x40)
    println!("{:#x}", chip.device_type(&mut delay).expect("device_type"));
    // Read a virtual register, (reg, delay) → Result<u8>
    println!("{}", chip.read_virtual(0x05, &mut delay).expect("read_virtual"));
    // Write a virtual register, (reg, value, delay) → Result<()>
    chip.write_virtual(0x05, 0x3B, &mut delay).expect("write_virtual");
    // Poll DATA_RDY (wire the INT pin yourself), (delay) → Result<u8>
    println!("{}", chip.poll_interrupt(&mut delay).expect("poll_interrupt"));
    // Soft reset, (delay) → Result<()>   (restores defaults)
    chip.soft_reset(&mut delay).expect("soft_reset");
    println!("done");
    #[allow(clippy::empty_loop)]
    loop { sleep_ms!(1000); }
}
