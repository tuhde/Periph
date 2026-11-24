//! Demo for the HTU31D — calibrated hygrometer: dew point, data rates, averaging noise, heater and DRDY.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTU31DFull;

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

    // Create HTU31D driver, (i2c, addr=0x40) → HTU31D
    let mut chip = HTU31DFull::new(dev, addr);
    // Soft reset, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identify the chip and check its health ---
    // The serial number is unique per device; the diagnostic byte reports NVM
    // and out-of-range errors.
    // Read the serial number, () → Result<u32>
    println!("serial 0x{:06X}", chip.serial_number().expect("serial"));
    // Read the diagnostic byte, () → Result<u8>
    println!("diagnostic 0x{:02X}", chip.diagnostic().expect("diagnostic"));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    // The dew point shows how close the air is to condensing on a surface.
    for _ in 0..15 {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Compute dew point, (delay) → Result<f32 °C>
        let dew = chip.dew_point(&mut delay).expect("dew");
        println!("{:.2} C  {:.1} %RH  dew {:.2} C", t, rh, dew);
        sleep_ms!(2000);
    }

    // --- Compare the four oversampling settings ---
    // Higher OSR gives finer resolution at the cost of a longer conversion.
    for osr in 0..4u8 {
        // Set oversampling, (osr_rh 0-3, osr_t 0-3) → Result<()>
        chip.set_resolution(osr, osr).expect("resolution");
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("OSR {:?} typ {:.2} ms: {:.2} C {:.1} %RH", chip.get_resolution(), chip.conversion_time() * 1000.0, t, rh);
    }
    chip.set_resolution(3, 3).expect("resolution");

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    let before = chip.read(&mut delay).expect("read");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    println!("heater on: {} diagnostic 0x{:02X}", chip.is_heater_on().expect("is_heater_on"), chip.diagnostic().expect("diagnostic"));
    sleep_ms!(10000);
    chip.set_heater(false).expect("heater off");
    let after = chip.read(&mut delay).expect("read");
    println!("before {:?} after {:?}", before, after);

    // --- Non-blocking conversion ---
    // Start the conversion, do other work, then collect the result.
    // Start a conversion, () → Result<()>
    chip.start_conversion().expect("start");
    sleep_ms!(30);
    // Read last conversion result, () → Result<(f32 °C, f32 %RH)>
    println!("result {:?}", chip.read_result().expect("result"));

    // --- Reset ---
    // Soft reset, (delay) → Result<()>
    chip.software_reset(&mut delay).expect("reset");
}
