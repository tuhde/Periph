//go:build tinygo

// MCP23008 complete example — TinyGo / Raspberry Pi Pico W. Exercises every method of the Full API.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	devAddr := uint8(0x20)

	conn := connection.NewI2CConnection(i2c, devAddr, nil, nil) // Create I2C connection, (i2c, addr=0x20, intPin=nil, enPin=nil) → *I2CConnection

	chip, err := ioexpander.NewMCP23008Full(conn, devAddr) // Create MCP23008 driver, (connection, addr=0x20) → (*MCP23008Full, error)
	// clears OLAT; all pins start as inputs
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get Full pin proxy, (n=0) → MCP23008FullPin
	// GP0; all eight pins are bidirectional
	_ = p0.Mode(ioexpander.MCP23008Output) // Set pin direction, (m PinMode) → error
	// latches OLAT, then clears the IODIR bit
	_ = p0.Write(true) // Write the output latch, (high bool) → error
	// sets the OLAT bit via the shadow
	_ = p0.Toggle() // Invert the output latch, () → error
	// one OLAT write, no read-modify-write
	level, _ := p0.Read() // Read actual level, () → (bool, error)
	// reads GPIO (actual pin level)
	fmt.Println("GP0 level:", level)

	_ = chip.Pin(5).Mode(ioexpander.MCP23008InputPullup) // Input with pull-up, (m PinMode) → error
	// sets the pin's GPPU bit
	_ = chip.Pin(6).SetPullUp(true) // Enable pull-up, (on bool) → error
	// read-modify-write of GPPU via the shadow

	_ = chip.WritePort(0, 0x05) // Write the output latch, (port=0, mask) → error
	// whole-port OLAT write
	port, _ := chip.ReadPort(0) // Read all 8 pins, (port=0) → (uint8, error)
	// GPIO register
	fmt.Printf("GPIO=0x%02X\n", port)

	_ = chip.ConfigurePullup(0xF0) // Pull-ups on GP4–GP7, (mask) → error
	// writes GPPU; only effective on inputs
	_ = chip.ConfigurePolarity(0x00) // Input polarity, (mask) → error
	// 1 bits invert the GPIO read
	_ = chip.SetDefaultValue(0x00) // Set DEFVAL, (mask) → error
	// reference for default-compare interrupts
	_ = chip.ConfigureIntPin(false, false) // Configure INT output, (openDrain, activeHigh bool) → error
	// push-pull, active-low (IOCON ODR/INTPOL)
	_ = chip.SetSequential(true) // Sequential access, (enabled bool) → error
	// IOCON SEQOP = 0 (driver default)
	iodir, _ := chip.ReadRegister(0x00) // Read raw register, (reg) → (uint8, error)
	// escape hatch for debugging
	fmt.Printf("IODIR=0x%02X\n", iodir)
	_ = chip.WriteRegister(0x02, 0x10) // Write raw register, (reg, value) → error
	// GPINTEN: arm GP4

	_ = chip.EnablePinInterrupt(0xF0) // Arm interrupt-on-change, (mask) → error
	// GP4–GP7
	_ = chip.OnInterrupt(func(status uint8) {
		fmt.Printf("interrupt, INTF=0x%02X\n", status)
	}, ioexpander.IntVsDefault) // Subscribe to INT, (callback, mode IntMode) → error
	// INT line if wired, else 5 ms polling
	_ = chip.Pin(4).Watch(connection.Falling, func(p ioexpander.MCP23008FullPin) {
		fmt.Println("GP4 falling")
	}) // Watch GP4, (trigger, handler) → error
	// arms GP4 in GPINTEN, edge filtered in software
	flags, _ := chip.PollInterrupt() // Read INTF and clear, () → (uint8, error)
	// INTCAP is read and discarded
	capture, _ := chip.ReadCapture() // Read captured port, () → (uint8, error)
	// port snapshot at the interrupt
	fmt.Printf("INTF=0x%02X INTCAP=0x%02X\n", flags, capture)
	_ = chip.Pin(4).Unwatch() // Stop watching, () → error
	// clears its GPINTEN bit
	_ = chip.OffInterrupt() // Unsubscribe, () → error
	// stops delivery
	_ = chip.Reset() // Restore reset values, () → error
	// rewrites all registers, clears shadows
}
