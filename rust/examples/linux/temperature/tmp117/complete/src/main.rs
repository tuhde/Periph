//! Exercises every method in the TMP117 Full API. EEPROM unlock/lock is shown
//! without any write in between, so no power-on default is changed.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::temperature::{
    Tmp117AlertMode, Tmp117AlertPinFunction, Tmp117AlertPolarity, Tmp117Full, Tmp117Mode,
    TMP117_I2C_ADDRESS, TMP117_SOURCE_HIGH, TMP117_SOURCE_LOW,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tmp117Full::new(dev, TMP117_I2C_ADDRESS).expect("init TMP117"); // Create TMP117 Full driver, (i2c, addr=0x48)
                                                                                     // checks DEVICE_ID bits 11:0 == 0x117

    let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
                                                                  // decodes TEMP_RESULT, 0.0078125 °C two's complement
    println!("temperature {:.4} °C", t);

    sensor.configure(Tmp117Mode::Continuous, 32, 0.5).expect("configure"); // Configure conversion, (mode, averaging 0|8|32|64, cycle_seconds s) → ()
                                                                            // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
    println!("config {:?}", sensor.get_config().expect("get_config")); // Read conversion config, () → Tmp117Config {mode, averaging, cycle_seconds s}
                                                                        // decodes MOD, AVG and CONV from CONFIGURATION

    sensor.configure(Tmp117Mode::Shutdown, 8, 1.0).expect("configure"); // Configure conversion, (mode, averaging 0|8|32|64, cycle_seconds s) → ()
                                                                         // MOD=01 stops conversions; TEMP_RESULT keeps its last value
    println!("shutdown {}", sensor.is_shutdown().expect("is_shutdown")); // Check Shutdown mode, () → bool
                                                                          // reads MOD[1:0] == 01
    sensor.trigger_one_shot().expect("trigger_one_shot"); // Start one conversion, () → ()
                                                          // MOD=11; returns to Shutdown when done
    while !sensor.is_data_ready().expect("is_data_ready") { // Check for a fresh result, () → bool
        sleep(Duration::from_millis(10));                    // reading Data_Ready clears it
    }
    println!("one-shot {:.4} °C", sensor.read_temperature().expect("read_temperature")); // Read temperature, () → f32 °C
                                                                                          // the one-shot result
    sensor.configure(Tmp117Mode::Continuous, 8, 1.0).expect("configure"); // Configure conversion, (mode, averaging 0|8|32|64, cycle_seconds s) → ()
                                                                           // back to the POR default

    sensor.set_high_limit(30.0).expect("set_high_limit"); // Set THIGH_LIMIT, (celsius °C) → ()
                                                          // rounded to the nearest 0.0078125 °C step
    sensor.set_low_limit(10.0).expect("set_low_limit");   // Set TLOW_LIMIT, (celsius °C) → ()
                                                          // rounded to the nearest 0.0078125 °C step
    println!("high {}", sensor.get_high_limit().expect("get_high_limit")); // Read THIGH_LIMIT, () → f32 °C
                                                                            // same format as TEMP_RESULT
    println!("low {}", sensor.get_low_limit().expect("get_low_limit"));    // Read TLOW_LIMIT, () → f32 °C
                                                                            // same format as TEMP_RESULT

    sensor.set_temperature_offset(0.25).expect("set_temperature_offset"); // Set calibration offset, (celsius °C) → ()
                                                                          // added to every result after linearization
    println!("offset {}", sensor.get_temperature_offset().expect("get_temperature_offset")); // Read calibration offset, () → f32 °C
                                                                                              // decodes TEMP_OFFSET
    sensor.set_temperature_offset(0.0).expect("set_temperature_offset"); // Set calibration offset, (celsius °C) → ()
                                                                         // remove the offset again

    sensor.unlock_eeprom().expect("unlock_eeprom"); // Unlock EEPROM, () → ()
                                                    // EUN=1: EEPROM-backed writes now persist
    println!("eeprom busy {}", sensor.is_eeprom_busy().expect("is_eeprom_busy")); // Check EEPROM busy, () → bool
                                                                                  // reads EEPROM_UL.EEPROM_Busy
    sensor.lock_eeprom().expect("lock_eeprom");     // Lock EEPROM, () → ()
                                                    // EUN=0: writes are volatile again
    println!("eeprom1 0x{:04X}", sensor.read_eeprom_scratch(1).expect("read_eeprom_scratch")); // Read EEPROM scratch, (slot 1|2|3) → u16
                                                                                                // slot 1 holds part of the factory unique ID
    sensor.write_eeprom_scratch(2, 0x1234).expect("write_eeprom_scratch"); // Write EEPROM scratch, (slot 2, value u16) → ()
                                                                           // only EEPROM2 is writable; volatile while locked
    println!("eeprom2 0x{:04X}", sensor.read_eeprom_scratch(2).expect("read_eeprom_scratch")); // Read EEPROM scratch, (slot 1|2|3) → u16
                                                                                                // reads back EEPROM2

    sensor
        .configure_alert(Tmp117AlertMode::Alert, Tmp117AlertPolarity::ActiveLow, Tmp117AlertPinFunction::Alert)
        .expect("configure_alert"); // Configure ALERT, (mode, polarity, pin_function) → ()
                                    // sets T/nA, POL and DR/Alert together

    let status = sensor.poll_interrupt().expect("poll_interrupt"); // Read alert flags, () → u8 mask
                                                                   // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
    println!("above high {} below low {}", status & TMP117_SOURCE_HIGH != 0, status & TMP117_SOURCE_LOW != 0);

    sensor.reset(&mut Delay).expect("reset"); // Software reset, (delay) → ()
                                              // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
}
