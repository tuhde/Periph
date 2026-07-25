//! MCP23017 demo — Knight Rider scanner with button override.
//!
//! Hardware:
//!   GPA0–GPA6: seven LEDs (anode → VCC via 220Ω, cathode → pin; active-high)
//!   GPB0–GPB6: seven push buttons (pin → GND when pressed; pull-ups enabled)
//!
//! Runs a Knight Rider scanning pattern on PORTA. Pressing a button overrides
//! the scanner and lights the matching LED. The loop reads GPIOB every 100 ms,
//! builds the output mask from the button state (inverted, since active-low),
//! ORs it with the scanner position unless a button is pressed, then writes
//! to OLATA.

use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Mcp23017Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Mcp23017Full::new(dev, addr).expect("init MCP23017"); // Create MCP23017 full driver, (i2c, addr=0x20) → Result

    // Enable pull-ups on PORTB inputs (GPB0–GPB6) so idle buttons read high.
    chip.configure_pullup(1, 0b01111111).expect("configure_pullup"); // Enable pull-ups, (port=1, mask) → Result<(), E>

    println!("Running — press buttons GPB0–GPB6 to light corresponding LEDs");

    let mut position: u8 = 0;
    let mut direction: i8 = 1;

    loop {
        let portb = chip.read_port(1).expect("read_port");          // Read all 8 pins, (port=1) → Result<u8, E>
                                                                // GPB0–GPB6 buttons: pressed = 0 (active-low pull-down)

        let buttons = portb & 0x7F;        // mask GPA7 (output-only)
        let pressed = (!buttons) & 0x7F;   // invert: pressed button = bit 1

        let scanner: u8 = 1 << position;

        let output: u8 = if pressed != 0 {
            pressed | (1 << 7)   // keep GPA7 high (output-only)
        } else {
            scanner | (1 << 7)
        };

        chip.write_port(0, output).expect("write_port");             // Write all 8 pins, (port=0, mask) → Result<(), E>

        let led_str: String = (0..7)
            .map(|i| if (output >> i) & 1 == 1 { '*' } else { ' ' })
            .collect();
        println!("PORTA=0x{:02X}  [{}]  buttons=0x{:02X}", output, led_str, buttons);

        position = ((position as i16) + direction as i16) as u8;
        if position == 6 { direction = -1; }
        if position == 0 { direction =  1; }

        sleep(Duration::from_millis(100));
    }
}