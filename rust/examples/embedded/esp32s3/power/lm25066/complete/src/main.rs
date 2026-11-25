#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::lm25066::SOURCE_VIN_UV_WARN;
use periph::chips::power::Lm25066Full;

esp_app_desc!();

const ADDR: u8 = 0x40;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Lm25066Full::new(i2c, ADDR, 1.0, false); // Create LM25066 driver, (i2c, addr, r_sense_mohm, current_limit_high)

    println!("{}", chip.voltage_in().unwrap());                          // Input voltage, () → f32 V
    println!("{}", chip.voltage_out().unwrap());                         // Output voltage, () → f32 V
    println!("{}", chip.current().unwrap());                             // Input current, () → f32 A
    println!("{}", chip.power().unwrap());                               // Input power, () → f32 W
    println!("{}", chip.temperature().unwrap());                         // MOSFET temperature, () → f32 °C
    println!("{}", chip.voltage_aux().unwrap());                         // VAUX pin voltage, () → f32 V (0 ... 1.16)
    println!("{:?}", chip.read_block().unwrap());                        // Time-aligned BLOCK_READ, () → Lm25066Block
    println!("{:?}", chip.read_average().unwrap());                      // AVG_BLOCK_READ, () → Lm25066Block
    println!("{:?}", chip.black_box().unwrap());                         // Snapshot at first SMBA, () → Lm25066Block
    println!("{} {}", chip.voltage_in_avg().unwrap(), chip.voltage_out_avg().unwrap()); // Averaged VIN / VOUT, () → f32 V each
    println!("{} {}", chip.current_avg().unwrap(), chip.power_avg().unwrap()); // Averaged IIN / PIN, () → f32 each
    println!("{}", chip.power_peak().unwrap());                          // Peak input power, () → f32 W
    chip.clear_power_peak().unwrap();                                    // Reset the peak register, ()
    chip.set_averaging(6).unwrap();                                      // Average 2^6 samples, (exponent 0-12)
    println!("{}", chip.get_averaging().unwrap());                       // () → u8
    chip.set_vin_uv_warn(Some(10.0)).unwrap();                           // VIN under-voltage warning, (Option<f32> volts)
    chip.set_vin_ov_warn(Some(15.0)).unwrap();                           // VIN over-voltage warning, (Option<f32> volts)
    chip.set_vout_uv_warn(Some(9.0)).unwrap();                           // VOUT under-voltage warning, (Option<f32> volts)
    chip.set_ot_warn(Some(100.0)).unwrap();                              // Over-temperature warning, (Option<f32> celsius)
    chip.set_ot_fault(Some(125.0)).unwrap();                             // Over-temperature fault, (Option<f32> celsius)
    chip.set_iin_warn(Some(20.0)).unwrap();                              // Input over-current warning, (Option<f32> amps)
    chip.set_pin_warn(Some(200.0)).unwrap();                             // Input over-power warning, (Option<f32> watts)
    println!("{:?}", chip.get_limits().unwrap());                        // All thresholds, () → Lm25066Limits (None = disabled)
    println!("{:#04x} {:#06x}", chip.status_byte().unwrap(), chip.status_word().unwrap()); // STATUS_BYTE / STATUS_WORD, () → u8 / u16
    println!("{:#04x} {:#04x}", chip.status_vout().unwrap(), chip.status_input().unwrap()); // STATUS_VOUT / STATUS_INPUT, () → u8 each
    println!("{:#04x} {:#04x} {:#04x}", chip.status_temperature().unwrap(), chip.status_cml().unwrap(), chip.status_mfr().unwrap()); // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
    println!("{:#06x}", chip.diagnostic_word().unwrap());                // READ_DIAGNOSTIC_WORD, () → u16
    chip.clear_faults().unwrap();                                        // CLEAR_FAULTS (does not turn the MOSFET on), ()
    println!("{}", chip.is_on().unwrap());                               // MOSFET on, () → bool
    // chip.operation_off().unwrap();                                    // CUTS THE LOAD RAIL, ()
    // chip.operation_on().unwrap();                                     // Switch the MOSFET on, ()
    println!("{:#04x}", chip.device_setup().unwrap());                   // DEVICE_SETUP, () → u8
    // chip.configure_device(Some(7), Some(false), None).unwrap();       // Override RETRY/CL/CB pins, (retry_code, current_limit_high, cb_ratio_high)
    // chip.set_gate_mask(0x04).unwrap();                                // DANGEROUS: disable fault shut-off, (mask bits 5/4/2)
    println!("{:#04x} {:#04x}", chip.get_gate_mask().unwrap(), chip.capability().unwrap()); // GATE_MASK / CAPABILITY, () → u8 each
    println!("{:?} {:?} {:?}", chip.manufacturer_id().unwrap(), chip.model().unwrap(), chip.revision().unwrap()); // MFR_ID / MFR_MODEL / MFR_REVISION, () → ASCII byte arrays
    chip.enable_interrupt(SOURCE_VIN_UV_WARN).unwrap();                  // Unmask an alert source, (source)
    chip.disable_interrupt(SOURCE_VIN_UV_WARN).unwrap();                 // Mask an alert source, (source)
    println!("{:#06x}", chip.poll_interrupt().unwrap());                 // Diagnostic word, () → u16 (wire SMBA to an ISR or poll)

    loop {
        delay.delay_ms(1000);
    }
}
