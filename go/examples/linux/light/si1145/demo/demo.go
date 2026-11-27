//go:build linux && !tinygo

// Si1145 demo example — Linux host.
//
// Proximity switch with UV monitor: identity and calibration, 10 s of readings, LED current
// and photodiode comparison, gain and range, autonomous mode with interrupt events and a
// threshold output, auxiliary channels, pause and reset.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := light.NewSi1145Full(conn) // Create Si1145 driver, (connection) → (*Si1145Full, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, HW_KEY, UV + ALS + PS1, () → error

	// --- Identity and calibration ---
	// PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
	// factory calibration bytes.
	part, rev, seq, err := chip.ReadID() // Identity registers, () → (partID, revID, seqID, error)
	chk(err)
	fmt.Printf("PART_ID 0x%02X  REV_ID 0x%02X  SEQ_ID 0x%02X\n", part, rev, seq)
	cal, err := chip.ReadCalibration() // GET_CAL, () → ([12]uint8, error)
	chk(err)
	fmt.Printf("calibration % X\n", cal)
	resp, err := chip.ReadResponse() // RESPONSE, () → (Si1145Response, error)
	chk(err)
	fmt.Printf("response %+v\n", resp)

	// --- Proximity, UV index and light for 10 s ---
	// Proximity counts rise as a hand approaches the LED/sensor window.
	for i := 0; i < 10; i++ {
		r, err := chip.Read() // Forced PS + ALS, () → (Si1145Reading, error)
		chk(err)
		fmt.Printf("%2d s  prox %5d  UV %5.2f  vis %5d  ir %5d\n", i, r.Proximity, r.UVIndex, r.Visible, r.Infrared)
		time.Sleep(time.Second)
	}

	// --- LED current and photodiode ---
	// Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
	// photodiode a fraction of the large one.
	fmt.Println("Hold a hand above the sensor")
	time.Sleep(3 * time.Second)
	for _, level := range []uint8{1, 4, 8, 15} {
		chk(chip.SetLEDCurrent(1, level)) // LED drive, (led, level) → error
		p, err := chip.ReadProximity(1)   // Forced PS, (channel) → (uint16, error)
		chk(err)
		fmt.Printf("LED level %2d -> %d\n", level, p)
	}
	chk(chip.SetLEDCurrent(1, 3))
	for _, large := range []bool{false, true} {
		chk(chip.SetPSPhotodiode(1, large)) // PS photodiode, (channel, large) → error
		p, err := chip.ReadProximity(1)
		chk(err)
		fmt.Printf("large photodiode %v -> %d\n", large, p)
	}
	chk(chip.SetPSLED(1, 0x01)) // PS LED select, (channel, leds) → error

	// --- Gain and high range ---
	// Each gain step doubles the integration time; the high range divides the sensitivity
	// by 14.5 for direct sunlight.
	for _, gain := range []uint8{0, 2, 4} {
		chk(chip.SetGain(gain, gain, 0)) // ADC gains, (visible, infrared, proximity) → error
		vis, err := chip.ReadVisible()   // Forced ALS, () → (uint16, error)
		chk(err)
		ir, err := chip.ReadInfrared() // Forced ALS, () → (uint16, error)
		chk(err)
		fmt.Printf("gain %d  vis %d  ir %d\n", gain, vis, ir)
	}
	chk(chip.SetGain(0, 0, 0))
	chk(chip.SetHighRange(true, true, false)) // Signal range, (visible, infrared, proximity) → error
	vis, err := chip.ReadVisible()
	chk(err)
	uv, err := chip.ReadUVIndex() // Forced ALS, () → (float64, error)
	chk(err)
	fmt.Printf("high range  vis %d  UV %.2f\n", vis, uv)
	chk(chip.SetHighRange(false, false, false))
	chk(chip.SetAlignment(false, false, false, false, false)) // Result alignment, (...) → error
	chk(chip.SetUVCoefficients(light.Si1145UCoefDefault))     // UCOEF0..3, (ucoef) → error

	// --- Autonomous mode with interrupt events and a proximity switch ---
	// PS + ALS every 100 ms; each finished PS1 measurement raises INT (or is polled).
	const threshold = 2000
	chk(chip.SetChannels(true, true, true, true, false, false, false)) // CHLIST, (...) → error
	events := 0
	chk(chip.EnableInterrupt(light.Si1145SourcePS1))  // INT_CFG + IRQ_ENABLE, (source) → error
	chk(chip.OnInterrupt(func(s uint8) { events++ })) // Subscribe, (callback) → error
	chk(chip.StartAutonomous(0.1, true, true))        // Autonomous mode, (intervalS, proximity, als) → error
	near := false
	for i := 0; i < 50; i++ {
		raw, err := chip.ReadRaw() // Data registers, () → (Si1145Raw, error)
		chk(err)
		if (raw.PS1 > threshold) != near {
			near = raw.PS1 > threshold
			fmt.Printf("output %v (prox %d)\n", near, raw.PS1)
		}
		time.Sleep(100 * time.Millisecond)
	}
	chk(chip.Pause())        // PSALS_PAUSE, () → error
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	fmt.Println(events, "interrupt events")
	chk(chip.DisableInterrupt(light.Si1145SourcePS1)) // IRQ_ENABLE clear, (source) → error
	_, err = chip.PollInterrupt()                     // IRQ_STATUS, () → (uint8, error)
	chk(err)

	// --- Auxiliary channels ---
	temp, err := chip.ReadTemperatureRaw() // AUX temperature, () → (uint16, error)
	chk(err)
	vdd, err := chip.ReadVDDRaw() // AUX VDD, () → (uint16, error)
	chk(err)
	fmt.Println("temperature raw", temp, "VDD raw", vdd)
	chk(chip.Force(false, true)) // ALS_FORCE without waiting, (proximity, als) → error
	time.Sleep(50 * time.Millisecond)
	raw, err := chip.ReadRaw()
	chk(err)
	fmt.Printf("raw %+v\n", raw)

	// --- Address and reset ---
	chk(chip.SetI2CAddress(0x60)) // I2C_ADDR + BUSADDR, (address) → error
	chk(chip.Reset())             // RESET + HW_KEY + defaults, () → error
	r, err := chip.Read()
	chk(err)
	fmt.Printf("after reset %+v\n", r)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
