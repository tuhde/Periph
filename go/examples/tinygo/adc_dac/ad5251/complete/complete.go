//go:build tinygo

// AD5251 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the AD5251Full API.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.AD5251I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewAD5251Full(conn, adcdac.AD5251DefaultRAB, adcdac.AD5251DefaultRWiper) // Create AD5251 Full driver, (conn, rAB=50000.0 Ω, rWiper=75.0 Ω) → (*AD5251Full, error)
	must(err)
	must(pot.SetWiper(0, 40)) // Set wiper position, (channel 0–1, code 0–63) → error
	// writes [instruction, code]; the instruction byte selects the channel
	w, err := pot.GetWiper(0) // Read wiper position, (channel 0–1) → (uint8 0–63, error)
	// sends the instruction alone, then reads one byte
	must(err)
	println(w)
	must(pot.SetResistance(0, 25000.0)) // Set W–B resistance, (channel 0–1, ohms Ω) → error
	// inverts R = D/64·RAB + RW to the nearest code
	r, err := pot.GetResistance(0) // Read W–B resistance, (channel 0–1) → (float32 Ω, error)
	// evaluates D/64·RAB + RW for the current code
	must(err)
	println(r)
	must(pot.SetBoth(8, 56)) // Write both wipers, (code0 0–63, code1 0–63) → error
	// one consecutive-write transaction (RDAC1 then RDAC3)
	must(pot.SetRatio(0, 0.25)) // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → error
	// code = round(ratio × 64), clamped to 63
	ratio, err := pot.GetRatio(0) // Read divider ratio, (channel 0–1) → (float32, error)
	// code / 64
	must(err)
	println(ratio)
	must(pot.SetResistanceWA(1, 15000.0)) // Set W–A resistance, (channel 0–1, ohms Ω) → error
	// rheostat between W and A with B open
	rwa, err := pot.GetResistanceWA(1) // Read W–A resistance, (channel 0–1) → (float32 Ω, error)
	// (64 − D)/64·RAB + RW
	must(err)
	println(rwa)
	must(pot.Increment(0)) // Step up one code, (channel 0–1 or AD5251Both) → error
	// quick command; saturates at 63
	must(pot.Decrement(0)) // Step down one code, (channel 0–1 or AD5251Both) → error
	// quick command; saturates at 0
	must(pot.Double(0)) // Raise by 6 dB, (channel 0–1 or AD5251Both) → error
	// quick command; the code doubles, saturating at 63
	must(pot.Halve(0)) // Lower by 6 dB, (channel 0–1 or AD5251Both) → error
	// quick command; the code is halved
	must(pot.Store(adcdac.AD5251Both)) // Store wipers in EEMEM, (channel 0–1 or AD5251Both) → error
	// quick command, then ACK polling (≈ 26 ms)
	must(pot.SetBoth(1, 2))
	must(pot.Restore(adcdac.AD5251Both)) // Restore wipers from EEMEM, (channel 0–1 or AD5251Both) → error
	// quick command followed by NOP
	must(pot.WriteEEMEM(4, 0x5A)) // Write user EEMEM byte, (index 4–15, value 0–63) → error
	// followed by ACK polling (≈ 26 ms)
	e, err := pot.ReadEEMEM(4) // Read user EEMEM byte, (index 4–15) → (uint8, error)
	// pointer write, then a one-byte read
	must(err)
	println(e)
	tol, err := pot.ReadTolerance(0) // Read stored RAB tolerance, (channel 0–1) → (float32 %, error)
	// sign-magnitude integer byte + decimal byte / 256
	must(err)
	println(tol)
	rab, err := pot.RABActual(0) // Actual RAB of a channel, (channel 0–1) → (float32 Ω, error)
	// rAB × (1 + tolerance / 100)
	must(err)
	println(rab)
	must(pot.Nop()) // Send NOP, () → error
	// quick command 0x80; the chip returns to idle
	pot.SetRAB(50000.0, 120.0) // Change resistance model, (rAB Ω, rWiper Ω) → ()
	// affects conversions only; no I²C traffic
	time.Sleep(time.Second)
	must(pot.Reset()) // Reset from EEMEM, () → error
	// quick command 0xB8, then ACK polling
}
