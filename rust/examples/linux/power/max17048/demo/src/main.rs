use linux_embedded_hal::I2cdev;
use periph::chips::power::Max17048Full;
use periph::chips::power::max17048::SOURCE_RESET;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Max17048Full::new(dev, addr, 1).expect("init MAX17048");     // Create MAX17048 driver, (i2c, addr, cells)

    println!("version 0x{:04X}, id 0x{:02X}", chip.version().unwrap(), chip.chip_id().unwrap());
    chip.clear_status(SOURCE_RESET).unwrap();                           // Clear the reset indicator, (mask) → Result<()>

    // Battery fuel gauge: one line per second for 30 s
    for _ in 0..30 {
        println!("{:.3} V  {:.1} %  {:+.1} %/h", chip.voltage().unwrap(), chip.percent().unwrap(), chip.charge_rate().unwrap());
        sleep(Duration::from_millis(1000));
    }

    chip.set_alert_soc(10).unwrap();                                    // Low-SOC alert at 10 %, (percent) → Result<()>
    chip.set_voltage_alert(3.3, 4.25).unwrap();                         // Voltage window 3.3-4.25 V, (min_v, max_v) → Result<()>
    // No callbacks in Rust: poll the alert sources (or call this from the ALRT falling-edge ISR)
    println!("poll_interrupt: 0x{:04X}", chip.poll_interrupt().unwrap()); // () → Result<u16>

    for celsius in [5.0f32, 35.0, 20.0] {
        chip.compensate_temperature(celsius).unwrap();                  // RCOMP for the temperature, (celsius) → Result<()>
        println!("RCOMP at {} C: 0x{:02X}", celsius, chip.get_rcomp().unwrap());
    }

    chip.disable_hibernate().unwrap();                                  // Never hibernate, () → Result<()>
    println!("hibernating: {}", chip.is_hibernating());
    chip.set_hibernate_thresholds(26.624, 0.06).unwrap();               // Back to the defaults

    chip.enable_sleep(true).unwrap();                                   // Sleep round trip, (enabled) → Result<()>
    chip.sleep().unwrap();
    sleep(Duration::from_millis(1000));
    chip.wake().unwrap();
    chip.enable_sleep(false).unwrap();
    println!("after wake: {:.3} V", chip.voltage().unwrap());
}
