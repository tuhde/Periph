#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ltc4151Full, CH_ADIN, CH_SENSE, CH_VIN};

esp_app_desc!();

const ADDR: u8 = 0x6A;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Ltc4151Full::new(i2c, ADDR, 0.02).expect("init LTC4151"); // Create LTC4151 driver, (i2c, addr, r_sense=0.02 Ω)

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

    println!("{}", Ltc4151Full::<I2c<esp_hal::Blocking>>::temperature_from_adin(900, 1200)); // NTC example circuit, (adin_code, vin_code) → f32 °C

    loop {
        delay.delay_ms(1000);
    }
}
