use linux_embedded_hal::I2cdev;
use periph::chips::power::lm25066::{SOURCE_IIN_WARN, SOURCE_VIN_UV_WARN};
use periph::chips::power::Lm25066Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lm25066Full::new(dev, addr, 1.0, false); // Create LM25066 driver, (i2c, addr, r_sense_mohm, current_limit_high)

    println!("{:?} {:?} {:?}", chip.manufacturer_id().unwrap(), chip.model().unwrap(), chip.revision().unwrap()); // Identify the chip, () → ASCII byte arrays
    chip.clear_faults().unwrap();                                        // Release SMBA after power-up, ()
    // Hot-swap rail monitor: one line per second for a minute
    for i in 0..60 {
        if i == 30 {
            chip.set_averaging(6).unwrap();                              // 64-sample averaging, (exponent)
            chip.set_vin_uv_warn(Some(10.0)).unwrap();                   // VIN under-voltage warning at 10 V, (volts)
            chip.set_iin_warn(Some(20.0)).unwrap();                      // Input over-current warning at 20 A, (amps)
            chip.enable_interrupt(SOURCE_VIN_UV_WARN | SOURCE_IIN_WARN).unwrap(); // Unmask both alerts, (source)
        }
        println!("VIN {:.2} V  VOUT {:.2} V  IIN {:.3} A (avg {:.3})  PIN {:.2} W  T {:.1} C",
            chip.voltage_in().unwrap(), chip.voltage_out().unwrap(), chip.current().unwrap(),
            chip.current_avg().unwrap(), chip.power().unwrap(), chip.temperature().unwrap());
        sleep(Duration::from_millis(1000));
    }
    println!("peak power {:.2} W", chip.power_peak().unwrap());          // Peak input power, () → f32 W
    println!("diagnostic {:#06x}", chip.poll_interrupt().unwrap());      // Alert flags, () → u16
    chip.clear_faults().unwrap();                                        // Finish servicing, ()
    // chip.operation_off().unwrap();   // CUTS THE LOAD RAIL - only with explicit opt-in
    // chip.operation_on().unwrap();
}
