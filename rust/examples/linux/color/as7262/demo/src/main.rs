//! Demo example for the AS7262.

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
    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    // Reset and configure defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");
    println!("type {:#x}, hw {:?}, fw {:?}",
        chip.device_type(&mut delay).expect("type"),            // Device type, (delay) → Result<u8>
        chip.hardware_version(&mut delay).expect("hw"),         // Hardware version, (delay) → Result<(u8, u8)>
        chip.firmware_version(&mut delay).expect("fw"));        // Firmware version, (delay) → Result<(u8, u8, u8)>
    // Read device temperature, (delay) → Result<f32 °C>
    println!("chip {:.0} C", chip.temperature(&mut delay).expect("temperature"));

    for gain in [1.0f32, 3.7, 16.0, 64.0] {
        // Set gain, (gain, delay) → Result<()>
        chip.set_gain(gain, &mut delay).expect("set_gain");
        // Get gain, (delay) → Result<f32>
        println!("gain {}", chip.get_gain(&mut delay).expect("get_gain"));
        // Read calibrated, (delay) → Result<Channels<f32>>
        println!(" cal {:?}", chip.read(&mut delay).expect("read"));
        // Read raw, (delay) → Result<Channels<u16>>
        println!(" raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));
    }
    chip.set_gain(16.0, &mut delay).expect("set_gain");

    for ms in [50.0f32, 500.0] {
        // Set integration time, (ms, delay) → Result<()>
        chip.set_integration_time(ms, &mut delay).expect("set_integration_time");
        chip.read(&mut delay).expect("read");
        // Get integration time, (delay) → Result<f32 ms>
        println!("integration {} ms", chip.get_integration_time(&mut delay).expect("get_integration_time"));
    }
    chip.set_integration_time(165.2, &mut delay).expect("set_integration_time");

    // Set bank mode, (mode, delay) → Result<()>
    chip.set_mode(3, &mut delay).expect("set_mode");
    let base = chip.read(&mut delay).expect("read").to_array();
    // Indicator LED, (on, current_ma, delay) → Result<()>
    chip.set_indicator_led(true, 4.0, &mut delay).expect("indicator");
    // Driver LED, (on, current_ma, delay) → Result<()>
    chip.set_led(true, 25.0, &mut delay).expect("led");
    let lit = chip.read(&mut delay).expect("read").to_array();
    chip.set_led(false, 25.0, &mut delay).expect("led");
    chip.set_indicator_led(false, 4.0, &mut delay).expect("indicator");
    let names = ["violet", "blue", "green", "yellow", "orange", "red"];
    let mut best = 0;
    for i in 0..6 {
        if lit[i] - base[i] > lit[best] - base[best] { best = i; }
    }
    println!("reflectance dominant: {}", names[best]);

    // Start one-shot, (delay) → Result<()>
    chip.measure(&mut delay).expect("measure");
    // Data ready, (delay) → Result<bool>
    while !chip.data_ready(&mut delay).expect("data_ready") { sleep_ms!(50); }
    // Last results / one channel, (delay) → Result<..>
    println!("{:?} {:?} {}", chip.read_calibrated(&mut delay).expect("cal"),
        chip.read_raw_last(&mut delay).expect("raw"), chip.read_channel(0, &mut delay).expect("ch"));

    // Continuous mode, (mode, delay) → Result<()>
    chip.set_continuous(2, &mut delay).expect("set_continuous");
    // Enable INT, (enabled, delay) → Result<()>
    chip.set_interrupt(true, &mut delay).expect("set_interrupt");
    for _ in 0..10 {
        sleep_ms!(500);
        // Poll DATA_RDY, (delay) → Result<u8>
        if chip.poll_interrupt(&mut delay).expect("poll_interrupt") == 1 {
            println!("{:?}", chip.read_calibrated(&mut delay).expect("cal"));
        }
    }
    chip.set_interrupt(false, &mut delay).expect("set_interrupt");
    chip.set_mode(3, &mut delay).expect("set_mode");
    // Write / read virtual register, (reg[, value], delay) → Result<..>
    chip.write_virtual(0x05, 0x3B, &mut delay).expect("write_virtual");
    println!("{}", chip.read_virtual(0x05, &mut delay).expect("read_virtual"));
    // Soft reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
    println!("done");
    #[allow(clippy::empty_loop)]
    loop { sleep_ms!(1000); }
}
