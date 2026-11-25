use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Mcp23008Full;
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Mcp23008Full::new(dev, addr).expect("init MCP23008");   // Create MCP23008 driver, (i2c, addr=0x20) → Result
                                                                       // clears OLAT; all pins start as inputs

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n) → ExPin
                                                                       // GP0; all eight pins are bidirectional
    p0.into_output().expect("into_output");                            // Make it an output, () → Result<(), E>
                                                                       // latches OLAT, then clears the IODIR bit
    p0.set_high().expect("set_high");                                  // Set high, () → Result<(), E>
                                                                       // one OLAT write via the shadow
    let set = p0.is_set_high().expect("is_set_high");                  // Read the latch, () → Result<bool, E>
                                                                       // from the shadow register, no bus transaction
    println!("GP0 latch: {}", set);
    let level = p0.is_high().expect("is_high");                        // Read actual level, () → Result<bool, E>
                                                                       // reads GPIO
    println!("GP0 level: {}", level);

    chip.write_port(0x05).expect("write_port");                        // Write the output latch, (mask) → Result<(), E>
                                                                       // whole-port OLAT write
    let port = chip.read_port().expect("read_port");                   // Read all 8 pins, () → Result<u8, E>
                                                                       // GPIO register
    println!("GPIO=0x{:02X}", port);

    chip.configure_pullup(0xF0).expect("configure_pullup");            // Pull-ups on GP4–GP7, (mask) → Result<(), E>
                                                                       // writes GPPU; only effective on inputs
    chip.configure_polarity(0x00).expect("configure_polarity");        // Input polarity, (mask) → Result<(), E>
                                                                       // 1 bits invert the GPIO read
    chip.set_default_value(0x00).expect("set_default_value");          // Set DEFVAL, (mask) → Result<(), E>
                                                                       // reference for default-compare interrupts
    chip.set_interrupt_control(0x00).expect("set_interrupt_control");  // Set INTCON, (mask) → Result<(), E>
                                                                       // 0 = compare against the previous pin value
    chip.configure_int_pin(false, false).expect("configure_int_pin");  // Configure INT, (open_drain, active_high) → Result<(), E>
                                                                       // push-pull, active-low (IOCON ODR/INTPOL)
    chip.set_sequential(true).expect("set_sequential");                // Sequential access, (enabled) → Result<(), E>
                                                                       // IOCON SEQOP = 0 (driver default)
    chip.enable_pin_interrupt(0xF0).expect("enable_pin_interrupt");    // Arm interrupt-on-change, (mask) → Result<(), E>
                                                                       // GP4–GP7
    let flags = chip.poll_interrupt().expect("poll_interrupt");        // Read INTF and clear, () → Result<u8, E>
                                                                       // INTCAP is read and discarded
    let cap = chip.read_capture().expect("read_capture");              // Read captured port, () → Result<u8, E>
                                                                       // port snapshot at the interrupt
    println!("INTF=0x{:02X} INTCAP=0x{:02X}", flags, cap);

    let iodir = chip.read_register(0x00).expect("read_register");      // Read raw register, (reg) → Result<u8, E>
                                                                       // escape hatch for debugging
    println!("IODIR=0x{:02X}", iodir);
    chip.write_register(0x02, 0x10).expect("write_register");          // Write raw register, (reg, value) → Result<(), E>
                                                                       // GPINTEN: arm GP4
    chip.reset().expect("reset");                                      // Restore reset values, () → Result<(), E>
                                                                       // rewrites all registers, clears shadows
}
