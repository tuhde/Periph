//go:build tinygo

// MCP4XXX complete — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
	"machine"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func main() {
	const part = "MCP4661"
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	defAddr, err := adcdac.MCP4XXXDefaultAddress(part)
	if err != nil {
		panic(err)
	}
	conn := connection.NewI2CConnection(i2c, defAddr, nil, nil) // Create I2C connection, (i2c, addr) → (*I2CConnection)

	// MCP4661: dual 8-bit potentiometer with EEPROM (the non-volatile calls need such a part).
	pot, err := adcdac.NewMCP4XXXFull(conn, part, 10000, 75, nil, 1) // Create MCP4XXX driver, (connection, part, rAB=10000 Ω, rWiper=75 Ω, hvc=nil, hvcSettleMs=1) → (*MCP4XXXFull, error)
	if err != nil {
		panic(err)
	}
	must(0, pot.Init()) // Bus check, () → error
	// reads wipers, TCON and STATUS, no writes

	// --- Part properties ---
	fmt.Println(pot.Channels()) // Number of networks, () → int 1-2
	// property of the selected part
	fmt.Println(pot.Steps()) // Number of wiper positions, () → int 129 or 257
	// max code + 1
	fmt.Println(pot.IsPotentiometer()) // Terminal A on a pin, () → bool
	// true for the X1 parts
	fmt.Println(pot.IsNonvolatile()) // Has EEPROM, () → bool
	// true for 454X / 456X / 464X / 466X

	// --- Wiper position and resistance ---
	must(0, pot.SetWiper(100, 0)) // Set wiper, (code 0-256, channel=0) → error
	// writes [(0 << 4) | D8, code & 0xFF]; code clamped
	fmt.Println(must(pot.GetWiper(0))) // Read wiper, (channel=0) → (int 0-256, error)
	// two-byte read of the wiper register
	must(0, pot.SetBoth(10, 20)) // Set both wipers, (code0, code1) → error
	// dual parts only; two transactions
	must(0, pot.SetResistance(2200, 1)) // Set W-B resistance, (ohms, channel=0) → error
	// nearest code of R = N/max*RAB + RW, clamped
	fmt.Println(must(pot.GetResistance(1))) // Read W-B resistance, (channel=0) → (float64 Ω, error)
	// N/max*RAB + RW
	fmt.Println(pot.GetResistanceStep()) // Resistance of one step, () → float64 Ω
	// RAB / max code

	// --- Ratio and W-A resistance ---
	must(0, pot.SetRatio(0.5, 0)) // Set wiper ratio, (ratio 0.0-1.0, channel=0) → error
	// code = round(ratio * max code)
	fmt.Println(must(pot.GetRatio(0))) // Read wiper ratio, (channel=0) → (float64 0.0-1.0, error)
	// code / max code
	must(0, pot.SetResistanceWA(3300, 0)) // Set W-A resistance, (ohms, channel=0) → error
	// potentiometer parts only
	fmt.Println(must(pot.GetResistanceWA(0))) // Read W-A resistance, (channel=0) → (float64 Ω, error)
	// (max code - N)/max*RAB + RW

	// --- Increment / decrement ---
	fmt.Println(must(pot.Increment(8, 0))) // Step wiper up, (steps, channel=0) → (int, error)
	// one continuous transaction of increment commands, saturating
	fmt.Println(must(pot.Decrement(4, 0))) // Step wiper down, (steps, channel=0) → (int, error)
	// same with decrement commands, saturating at 0

	// --- Shutdown and terminals ---
	must(0, pot.Shutdown(1)) // Shut network down, (channel=0) → error
	// TCON.RxHW = 0: A open, W tied to B; wiper kept
	fmt.Println(must(pot.IsShutdown(1))) // Read shutdown state, (channel=0) → (bool, error)
	// from TCON
	must(0, pot.Resume(1)) // Leave shutdown, (channel=0) → error
	// TCON.RxHW = 1
	must(0, pot.SetTerminals(true, false, true, 0)) // Connect terminals, (a, w, b, channel=0) → error
	// TCON.RxA / RxW / RxB, other bits preserved
	a, w, b, err := pot.GetTerminals(0) // Read terminals, (channel=0) → (a, w, b bool, error)
	// from TCON
	fmt.Println(a, w, b, err)
	must(0, pot.SetTerminals(true, true, true, 0)) // Reconnect all terminals, (a, w, b, channel=0) → error
	// restores the wiper terminal
	fmt.Println(must(pot.GetTCON())) // Read TCON, () → (int 0-0x1FF, error)
	// raw terminal control register
	must(0, pot.SetGeneralCall(true)) // Set General Call, (enabled) → error
	// TCON.GCEN; chip answers address 0x00 commands

	// --- Raw access and model ---
	fmt.Println(must(pot.ReadRegister(0x04))) // Raw read, (address 0-15) → (int 0-0x1FF, error)
	// nine-bit value of any address the part has
	must(0, pot.WriteRegister(0x04, 0x1FF)) // Raw write, (address, value) → error
	// volatile wipers, TCON, EEPROM addresses on NV parts
	pot.SetRAB(10000, 75) // Set resistance model, (rAB Ω, rWiper Ω; negative keeps RW) → ()
	// no I²C traffic; affects conversions only

	// --- Non-volatile parts ---
	fmt.Println(must(pot.ReadStatus())) // Read STATUS, () → (MCP4XXXStatus, error)
	// EEPROM busy, WiperLock per network, write protect
	must(0, pot.WaitReady(20)) // Wait for EEPROM write cycle, (timeoutMs=20 ms) → error
	// polls STATUS.EEWA
	fmt.Println(must(pot.GetNVWiper(0))) // Read EEPROM wiper, (channel=0) → (int, error)
	// power-up value
	must(0, pot.SetNVWiper(64, 0)) // Write EEPROM wiper, (code, channel=0) → error
	// volatile wiper unchanged; waits for the write cycle
	fmt.Println(must(pot.StoreWiper(0))) // Store current wiper, (channel=0) → (int, error)
	// reads the volatile wiper, writes it to EEPROM
	fmt.Println(must(pot.ReadData(0))) // Read EEPROM register, (index 0-9) → (int 0-0x1FF, error)
	// general-purpose 9-bit register
	must(0, pot.WriteData(0, 0x155)) // Write EEPROM register, (index 0-9, value 0-0x1FF) → error
	// uses one of 1 000 000 write cycles
	fmt.Println(must(pot.IsWiperLocked(0))) // Read WiperLock, (channel=0) → (bool, error)
	// STATUS.WLx
	fmt.Println(must(pot.IsWriteProtected())) // Read write protect, () → (bool, error)
	// STATUS.WP

	must(0, pot.Reset()) // Emulate power-on reset, () → error
	// EEPROM wiper (or mid-scale) in every wiper, TCON 0x1FF
}
