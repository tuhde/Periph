//go:build tinygo

// TMP117 demo example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

// PT100-replacement cold-chain container thermometer: maximum averaging gives
// the lowest-noise reading, and the ALERT output fires when the cargo leaves
// the -25 °C to 8 °C safe transport range; each event is reported with the
// boundary that tripped. Set calibrate and referenceC to a reference
// thermometer reading to calibrate once and persist the offset to EEPROM.

const (
	maxAlerts  = 10
	calibrate  = false
	referenceC = 4.0
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP117I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	s, err := temperature.NewTMP117Full(conn) // Create TMP117 Full driver, (conn) → (*TMP117Full, error)
	if err != nil {
		panic(err)
	}

	// --- Lowest-noise continuous conversion ---
	// 64-conversion averaging with a 1 s cycle gives the quietest result the
	// chip can deliver — a cold-chain log needs stability, not speed.
	if err := s.Configure(temperature.TMP117Continuous, 64, 1.0); err != nil { // Configure conversion, (mode, averaging 0|8|32|64, cycleSeconds s) → error
		panic(err)
	}

	// --- One-time calibration against a reference thermometer ---
	// The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
	// the correction survives power cycles. EEPROM endurance is limited — this
	// is a once-per-deployment step, not a loop.
	if calibrate {
		time.Sleep(1100 * time.Millisecond)
		t, _ := s.ReadTemperature()         // Read temperature, () → (float32 °C, error)
		prev, _ := s.GetTemperatureOffset() // Read calibration offset, () → (float32 °C, error)
		offset := float32(referenceC) - t + prev
		_ = s.UnlockEeprom()               // Unlock EEPROM, () → error
		_ = s.SetTemperatureOffset(offset) // Set calibration offset, (celsius °C) → error
		for {
			busy, err := s.IsEepromBusy() // Check EEPROM busy, () → (bool, error)
			if err != nil || !busy {
				break
			}
			time.Sleep(1 * time.Millisecond)
		}
		_ = s.LockEeprom() // Lock EEPROM, () → error
		println("calibrated, offset (m°C)", int32(offset*1000))
	}

	// --- Program the safe transport range ---
	// Alert mode flags either side of the window independently; ALERT is
	// active-low open-drain, pulled up on the board.
	if err := s.SetHighLimit(8.0); err != nil { // Set THIGH_LIMIT, (celsius °C) → error
		panic(err)
	}
	if err := s.SetLowLimit(-25.0); err != nil { // Set TLOW_LIMIT, (celsius °C) → error
		panic(err)
	}
	if err := s.ConfigureAlert(temperature.TMP117AlertWindow, temperature.TMP117AlertActiveLow,
		temperature.TMP117PinAlert); err != nil { // Configure ALERT, (mode, polarity, pinFunction) → error
		panic(err)
	}

	// --- Report which boundary tripped ---
	// The status mask comes from CONFIGURATION's alert flags; reading them
	// clears them in Alert mode, re-arming for the next excursion.
	events := make(chan uint8, maxAlerts)
	if err := s.OnInterrupt(func(status uint8) { // Subscribe to ALERT, (callback) → error
		if status != 0 {
			events <- status
		}
	}); err != nil {
		panic(err)
	}
	now, _ := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	println("monitoring, now (m°C)", int32(now*1000))

	for alerts := 0; alerts < maxAlerts; alerts++ {
		status := <-events
		t, _ := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
		if status&temperature.TMP117SourceHigh != 0 {
			println("too warm - cargo above 8 C (m°C)", int32(t*1000))
		} else if status&temperature.TMP117SourceLow != 0 {
			println("too cold - cargo below -25 C (m°C)", int32(t*1000))
		}
	}

	// --- Stop monitoring after the demo run ---
	if err := s.OffInterrupt(); err != nil { // Unsubscribe, () → error
		panic(err)
	}
}
