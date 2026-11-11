//! PCA6408A demo — LED scanner with button input.
//!
//! Hardware:
//!   P0–P3: four active-low LEDs (anode to VDD(P) through 220 Ω; 0 lights the LED)
//!   P4–P7: four push buttons to GND with external pull-ups (the chip has none)
//!
//! Runs a scanner pattern on the LEDs. Pressing a button changes INPUT; the
//! loop reads the changed-pin mask with poll_interrupt(), prints it with the
//! port state and holds the LED matching the pressed button (P4→P0, …) for
//! one second.

use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pca6408aFull;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca6408aFull::new(dev, addr).expect("init PCA6408A");       // Create PCA6408A driver, (i2c, addr=0x20) → Result

    // --- Configure LEDs and buttons ---
    // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs
    // and rely on external pull-ups (the chip has none), so a pressed button
    // (to GND) reads 0.
    chip.write_port(0xFF).expect("write_port");                            // Turn all LEDs off, (mask=0xFF) → Result<(), E>
    chip.set_direction(0xF0).expect("set_direction");                      // P0–P3 outputs, P4–P7 inputs, (mask) → Result<(), E>

    // --- Scanner loop ---
    // Any button edge changes INPUT; the loop reads the changed mask with
    // poll_interrupt() (the INT line would trigger this read) and holds the LED
    // matching the pressed button for one second.
    let mut hold_steps = 0u32;
    let mut hold_mask = 0x0Fu8;
    for step in 0..40u32 {
        let changed = chip.poll_interrupt().expect("poll_interrupt");      // Read INPUT, get changed mask, () → Result<u8, E>
        if changed != 0 {
            let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>
            println!("button changed=0x{:02X} port=0x{:02X}", changed, port);
            hold_mask = !((changed >> 4) & 0x0F) & 0x0F;
            hold_steps = 10;
        }
        let pos = step % 4;
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else { !(1u8 << pos) & 0x0F };
        chip.write_port(0xF0 | leds).expect("write_port");                 // Write LED outputs, (mask) → Result<(), E>
        let port = chip.read_port().expect("read_port");                   // Read all 8 pins, () → Result<u8, E>
        println!("  pos={}  port=0x{:02X}", pos, port);
        sleep(Duration::from_millis(100));
    }

    // --- Shut down ---
    chip.write_port(0xFF).expect("write_port");                            // Turn all LEDs off, (mask=0xFF) → Result<(), E>
}
