//! MCP23008 demo — LED scanner with interrupt-driven button input.
//!
//! Hardware:
//!   GP0–GP3: four LEDs (anode through 220 Ω, cathode to GND; active-high)
//!   GP4–GP7: four push buttons to GND (pull-ups enabled)
//!
//! Runs a scanner pattern on the LEDs. Pressing a button raises an
//! interrupt-on-change; the loop polls INTF, prints it with the INTCAP
//! snapshot and holds the LED matching the pressed button (GP4→GP0, …) for
//! one second.

use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Mcp23008Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Mcp23008Full::new(dev, addr).expect("init MCP23008");   // Create MCP23008 driver, (i2c, addr=0x20) → Result

    // --- Configure LEDs and buttons ---
    // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups
    // on, so a pressed button (to GND) reads 0.
    for n in 0..4 {
        chip.pin(n).into_output().expect("into_output");               // Make LED pin an output, () → Result<(), E>
    }
    chip.configure_pullup(0xF0).expect("configure_pullup");            // Pull-ups on GP4–GP7, (mask) → Result<(), E>

    // --- Arm interrupt-on-change on the buttons ---
    // Any button edge latches INTF; the loop below reads it with
    // poll_interrupt() and the snapshot with read_capture().
    chip.enable_pin_interrupt(0xF0).expect("enable_pin_interrupt");    // Arm GP4–GP7, (mask) → Result<(), E>

    // --- Scanner loop ---
    let mut hold_steps = 0u32;
    let mut hold_mask = 0u8;
    for step in 0..40u32 {
        let flags = chip.poll_interrupt().expect("poll_interrupt");    // Read INTF and clear, () → Result<u8, E>
        if flags != 0 {
            let captured = chip.read_capture().expect("read_capture"); // Read port snapshot, () → Result<u8, E>
            println!("button INTF=0x{:02X} INTCAP=0x{:02X}", flags, captured);
            hold_mask = (flags >> 4) & 0x0F;
            hold_steps = 10;
        }
        let pos = step % 4;
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else { 1 << pos };
        chip.write_port(leds).expect("write_port");                    // Write LED outputs, (mask) → Result<(), E>
        let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>
        println!("  pos={}  port=0x{:02X}", pos, port);
        sleep(Duration::from_millis(100));
    }

    // --- Shut down ---
    chip.write_port(0x00).expect("write_port");                        // Clear all outputs, (mask=0x00) → Result<(), E>
}
