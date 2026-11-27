//go:build tinygo

// Si1145 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = fmt.Sprint
var _ = time.Second

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60) → (*I2CConnection)

	chip, err := light.NewSi1145Full(conn) // Create Si1145 driver, (connection) → (*Si1145Full, error)
	// Fixed address 0x60 (older silicon: 0x5A); stores the connection only, no bus traffic yet.
	chk(err)
	chk(chip.Init()) // Check PART_ID, HW_KEY, UV + ALS + PS1, () → error
	// Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

	part, rev, seq, err := chip.ReadID() // Identity registers, () → (partID, revID, seqID uint8, error)
	// PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
	chk(err)
	fmt.Printf("PART_ID 0x%02X REV_ID 0x%02X SEQ_ID 0x%02X\n", part, rev, seq)
	resp, err := chip.ReadResponse() // RESPONSE decoded, () → (Si1145Response {Counter, Error}, error)
	// 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).
	chk(err)
	fmt.Printf("%+v\n", resp)

	r, err := chip.Read() // Forced PS + ALS, () → (Si1145Reading, error)
	// PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
	chk(err)
	fmt.Printf("%+v\n", r)
	p, err := chip.ReadProximity(1) // Forced PS, (channel) → (uint16, error)
	// PS1 only; PS2/PS3 exist on the Si1146/47.
	chk(err)
	fmt.Println("proximity", p)
	uv, err := chip.ReadUVIndex() // Forced ALS, () → (float64, error)
	// UV index with the default UV coefficients 7B 6B 01 00.
	chk(err)
	fmt.Printf("UV %.2f\n", uv)
	vis, err := chip.ReadVisible() // Forced ALS, () → (uint16, error)
	// Raw visible ambient light.
	chk(err)
	ir, err := chip.ReadInfrared() // Forced ALS, () → (uint16, error)
	// Raw infrared ambient light.
	chk(err)
	fmt.Println("visible", vis, "infrared", ir)
	raw, err := chip.ReadRaw() // Data registers, () → (Si1145Raw {Visible, Infrared, PS1, PS2, PS3, Aux}, error)
	// No new measurement; the latest values of 0x22–0x2D.
	chk(err)
	fmt.Printf("%+v\n", raw)
	temp, err := chip.ReadTemperatureRaw() // AUX temperature, () → (uint16, error)
	// Relative die temperature; CHLIST is switched to EN_AUX and restored.
	chk(err)
	vdd, err := chip.ReadVDDRaw() // AUX VDD, () → (uint16, error)
	// Supply voltage on the auxiliary channel.
	chk(err)
	fmt.Println("temperature raw", temp, "VDD raw", vdd)

	chk(chip.SetChannels(true, true, true, true, false, false, false)) // CHLIST, (uv, visible, infrared, ps1, ps2, ps3, aux) → error
	// UV and AUX share AUX_DATA, so only one of them can be enabled.
	chk(chip.SetLEDCurrent(1, 8)) // LED drive, (led, level) → error
	// Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
	chk(chip.SetPSLED(1, 0x01)) // PS LED select, (channel, leds) → error
	// PS1 drives LED1; the Si1145 has no LED2/LED3.
	chk(chip.SetPSPhotodiode(1, true)) // PS photodiode, (channel, large) → error
	// Large IR photodiode (0x03); false selects the small one.
	chk(chip.SetGain(0, 0, 0)) // ADC gains, (visible, infrared, proximity) → error
	// Integration time × 2^n, recovery counts at the one's complement of the gain.
	chk(chip.SetHighRange(false, false, false)) // Signal range, (visible, infrared, proximity) → error
	// true divides the sensitivity by 14.5 (direct sunlight).
	chk(chip.SetAlignment(false, false, false, false, false)) // Result alignment, (visible, infrared, ps1, ps2, ps3) → error
	// true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
	chk(chip.SetUVCoefficients(light.Si1145UCoefDefault)) // UCOEF0..3, (ucoef [4]uint8) → error
	// 7B 6B 01 00 make AUX_DATA the UV index × 100.
	cal, err := chip.ReadCalibration() // GET_CAL, () → ([12]uint8, error)
	// Factory calibration bytes; overwrites the data registers.
	chk(err)
	fmt.Printf("calibration % X\n", cal)

	_, err = chip.PollInterrupt() // IRQ_STATUS, () → (uint8 sources, error)
	// Reads and clears pending flags.
	chk(err)
	chk(chip.StartAutonomous(0.1, true, true)) // Autonomous mode, (intervalS, proximity, als) → error
	// MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
	time.Sleep(500 * time.Millisecond)
	src, err := chip.PollInterrupt() // IRQ_STATUS, () → (uint8 sources, error)
	// SOURCE_ALS | SOURCE_PS1 when both finished at least once.
	chk(err)
	fmt.Printf("sources 0x%02X\n", src)
	chk(chip.Pause()) // PSALS_PAUSE, () → error
	// Stops the autonomous loop.
	chk(chip.Force(true, false)) // Forced measurement without waiting, (proximity, als) → error
	// PS_FORCE; the result arrives when IRQ_STATUS flags PS1.
	time.Sleep(50 * time.Millisecond)

	events := make(chan uint8, 8)
	chk(chip.EnableInterrupt(light.Si1145SourceALS | light.Si1145SourcePS1)) // INT_CFG + IRQ_ENABLE, (source) → error
	// INT pin (active low) driven for ALS and PS1 completions.
	chk(chip.OnInterrupt(func(s uint8) { events <- s })) // Subscribe, (callback) → error
	// Falling edge on the connection's IntPin, otherwise a polling goroutine.
	chk(chip.Force(true, true))
	time.Sleep(200 * time.Millisecond)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	fmt.Println(len(events), "events")
	chk(chip.DisableInterrupt(light.Si1145SourceCmd)) // IRQ_ENABLE clear, (source) → error
	// CMD_INT is not used by default.

	chk(chip.SetI2CAddress(0x60)) // I2C_ADDR + BUSADDR, (address) → error
	// Volatile; here the address stays 0x60, otherwise reconnect on the new address.
	chk(chip.Reset()) // RESET + HW_KEY + defaults, () → error
	// Software reset, 10 ms wait, then the Minimal configuration again.
}
