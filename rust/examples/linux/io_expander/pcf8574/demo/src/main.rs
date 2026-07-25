/// PCF8574 demo — button-controlled LED mirror.
///
/// Hardware:
/// * P0–P3: LEDs (anode → VCC, cathode → pin; active-low)
/// * P4–P7: push buttons (pin → GND when pressed; internal pull-up = high when idle)
///
/// Reads the button nibble (P4–P7) every 200 ms, inverts it (pressed = 0 → LED on = 0),
/// and writes the result to the output nibble (P0–P3). Prints the raw port byte and
/// decoded states to stdout so the quasi-bidirectional read-back is visible.
use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pcf8574Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pcf8574Full::new(dev, addr).expect("init PCF8574");     // Create PCF8574 full driver, (i2c, addr) → Result

    // --- Configure output/input nibbles ---
    // P0–P3 as outputs (LEDs, active-low); P4–P7 as inputs (buttons, internal pull-up).
    // Writing 0xF0 keeps P4–P7 high (input mode) and drives P0–P3 low (LEDs on).
    chip.write_port(0xF0).expect("write_port");                        // Write all 8 pins, (mask) → Result<(), E>

    println!("Running — press buttons on P4–P7 to mirror to LEDs on P0–P3");

    loop {
        let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>

        // --- Mirror button inputs to LED outputs ---
        // Buttons are in bits 4–7; pressed = 0 (pulled to GND by button).
        // LEDs are active-low; invert: pressed (0) → LED on (bit = 0 in output).
        let buttons  = (port >> 4) & 0x0F;
        let led_bits = (!buttons) & 0x0F;
        chip.write_port(0xF0 | led_bits).expect("write_port");         // Write all 8 pins, (mask) → Result<(), E>

        let btn_str: String = (0..4).map(|i| if (buttons >> i) & 1 == 0 { 'X' } else { '.' }).collect();
        let led_str: String = (0..4).map(|i| if (led_bits >> i) & 1 == 1 { '*' } else { ' ' }).collect();
        println!("port=0x{:02X}  buttons=[{}]  LEDs=[{}]", port, btn_str, led_str);

        sleep(Duration::from_millis(200));
    }
}
