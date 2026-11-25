//! PCA9536 demo — blinking LEDs with polled button input.
//!
//! Hardware:
//!   P0–P1: two active-low LEDs (anode to VCC through 220 Ω; 0 lights the LED, sinks up to 25 mA)
//!   P2–P3: two push buttons to GND (the chip's built-in weak pull-up holds an
//!          open input high, so no external resistor is needed)
//!
//! The LEDs blink alternately. The chip has no INT pin, so the loop reads the
//! changed-pin mask with poll_changes(), prints it with the port state and
//! holds the LED matching the pressed button (P2→P0, P3→P1) for one second.

use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pca9536Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x41);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca9536Full::new(dev, addr).expect("init PCA9536");       // Create PCA9536 driver, (i2c, addr=0x41) → Result

    // --- Configure LEDs and buttons ---
    // P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs
    // and rely on the built-in weak pull-up, so a pressed button (to GND)
    // reads 0.
    chip.write_port(0x0F).expect("write_port");                            // Turn all LEDs off, (mask=0x0F) → Result<(), E>
    chip.set_direction(0x0C).expect("set_direction");                      // P0–P1 outputs, P2–P3 inputs, (mask) → Result<(), E>

    // --- Blink loop ---
    // Any button edge changes INPUT; the loop reads the changed mask with
    // poll_changes() and holds the LED matching the pressed button for one
    // second.
    let mut hold_steps = 0u32;
    let mut hold_mask = 0x03u8;
    for step in 0..40u32 {
        let changed = chip.poll_changes().expect("poll_changes");          // Read INPUT, get changed mask, () → Result<u8, E>
        if changed != 0 {
            let port = chip.read_port().expect("read_port");               // Read all 4 pins, () → Result<u8, E>
            println!("button changed=0x{:X} port=0x{:X}", changed, port);
            hold_mask = !((changed >> 2) & 0x03) & 0x03;
            hold_steps = 10;
        }
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else if step % 2 == 0 { 0x02 } else { 0x01 };
        chip.write_port(0x0C | leds).expect("write_port");                 // Write LED outputs, (mask) → Result<(), E>
        sleep(Duration::from_millis(100));
    }

    // --- Shut down ---
    chip.write_port(0x0F).expect("write_port");                            // Turn all LEDs off, (mask=0x0F) → Result<(), E>
}
