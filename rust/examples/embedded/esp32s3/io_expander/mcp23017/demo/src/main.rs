#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Mcp23017Full;

esp_app_desc!();

const ADDR: u8 = 0x20;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Mcp23017Full::new(i2c, ADDR).expect("init MCP23017"); // Create MCP23017 full driver, (i2c, ADDR=0x20) → Result

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

        delay.delay_ms(100);
    }
}
