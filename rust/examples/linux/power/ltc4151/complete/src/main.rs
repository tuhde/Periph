use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::{Ltc4151Full, CH_ADIN, CH_SENSE, CH_VIN};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Ltc4151Full::new(dev, addr, 0.02).expect("init LTC4151"); // Create LTC4151 driver, (i2c, addr, r_sense=0.02 Ω)

    println!("{}", chip.voltage().unwrap());                            // Supply voltage, () → f32 V
                                                                        // VIN code × 25 mV
    println!("{}", chip.current().unwrap());                            // Load current, () → f32 A
                                                                        // sense voltage / r_sense
    println!("{}", chip.power().unwrap());                              // Load power, () → f32 W
                                                                        // one block read of SENSE and VIN
    println!("{}", chip.sense_voltage().unwrap());                      // SENSE+ − SENSE−, () → f32 V
    println!("{}", chip.adin_voltage().unwrap());                       // ADIN pin voltage, () → f32 V
    println!("{:?}", chip.read_all().unwrap());                         // Consistent read of all channels, () → Ltc4151Readings
                                                                        // one block read of registers 0x00–0x05
    println!("{}", chip.read_raw(CH_VIN).unwrap());                     // Raw 12-bit code, (channel) → u16
                                                                        // CH_SENSE = 0, CH_VIN = 1, CH_ADIN = 2

    chip.set_sense_resistor(0.01).unwrap();                             // Change sense resistor, (ohms) → Result<()>
    println!("{}", chip.get_sense_resistor());                          // Sense resistor, () → f32 Ω
    chip.set_sense_resistor(0.02).unwrap();

    println!("{}", chip.snapshot(CH_SENSE, &mut delay).unwrap());       // One snapshot conversion, (channel, delay) → Result<f32> V
                                                                        // returns to continuous scan; Err(Timeout) after 100 ms
    println!("{}", chip.is_snapshot_mode().unwrap());                   // Snapshot mode active, () → bool
    println!("{}", chip.is_busy(CH_ADIN).unwrap());                     // Channel busy, (channel) → bool
    chip.set_continuous().unwrap();                                     // Back to continuous scan, ()

    chip.set_stuck_bus_timer(true).unwrap();                            // Stuck-bus reset timer, (enabled)
    chip.set_page_rw(true).unwrap();                                    // I²C page read/write, (enabled)
    println!("0x{:02X}", chip.read_control().unwrap());                 // Read CONTROL, () → u8
    chip.write_control(0x0C).unwrap();                                  // Write CONTROL, (value)
                                                                        // test mode bit 4 is always masked to 0

    println!("{}", Ltc4151Full::<I2cdev>::temperature_from_adin(900, 1200)); // NTC example circuit, (adin_code, vin_code) → f32 °C
    sleep(Duration::from_millis(10));
}
