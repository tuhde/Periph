//go:build tinygo

// TMP117 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

// Exercises every method in the TMP117 Full API. EEPROM unlock/lock is shown
// without any write in between, so no power-on default is changed.

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP117I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	s, err := temperature.NewTMP117Full(conn) // Create TMP117 Full driver, (conn) → (*TMP117Full, error)
	must(err)                                 // checks DEVICE_ID bits 11:0 == 0x117

	t, err := s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)                     // decodes TEMP_RESULT, 0.0078125 °C two's complement
	println("temperature (m°C)", int32(t*1000))

	err = s.Configure(temperature.TMP117Continuous, 32, 0.5) // Configure conversion, (mode, averaging 0|8|32|64, cycleSeconds s) → error
	must(err)                                                // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
	cfg, err := s.GetConfig()                                // Read conversion config, () → (TMP117Config {Mode, Averaging, CycleSeconds s}, error)
	must(err)                                                // decodes MOD, AVG and CONV from CONFIGURATION
	println("mode", uint8(cfg.Mode), "averaging", cfg.Averaging, "cycle ms", int32(cfg.CycleSeconds*1000))

	err = s.Configure(temperature.TMP117Shutdown, 8, 1.0) // Configure conversion, (mode, averaging 0|8|32|64, cycleSeconds s) → error
	must(err)                                             // MOD=01 stops conversions; TEMP_RESULT keeps its last value
	sd, err := s.IsShutdown()                             // Check Shutdown mode, () → (bool, error)
	must(err)                                             // reads MOD[1:0] == 01
	println("shutdown", sd)
	err = s.TriggerOneShot() // Start one conversion, () → error
	must(err)                // MOD=11; returns to Shutdown when done
	for {
		ready, err := s.IsDataReady() // Check for a fresh result, () → (bool, error)
		must(err)                     // reading Data_Ready clears it; loop until the conversion is done
		if ready {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	t, err = s.ReadTemperature() // Read temperature, () → (float32 °C, error)
	must(err)                    // the one-shot result
	println("one-shot (m°C)", int32(t*1000))
	err = s.Configure(temperature.TMP117Continuous, 8, 1.0) // Configure conversion, (mode, averaging 0|8|32|64, cycleSeconds s) → error
	must(err)                                               // back to the POR default

	err = s.SetHighLimit(30.0)  // Set THIGH_LIMIT, (celsius °C) → error
	must(err)                   // rounded to the nearest 0.0078125 °C step
	err = s.SetLowLimit(10.0)   // Set TLOW_LIMIT, (celsius °C) → error
	must(err)                   // rounded to the nearest 0.0078125 °C step
	hi, err := s.GetHighLimit() // Read THIGH_LIMIT, () → (float32 °C, error)
	must(err)                   // same format as TEMP_RESULT
	println("high (m°C)", int32(hi*1000))
	lo, err := s.GetLowLimit() // Read TLOW_LIMIT, () → (float32 °C, error)
	must(err)                  // same format as TEMP_RESULT
	println("low (m°C)", int32(lo*1000))

	err = s.SetTemperatureOffset(0.25)   // Set calibration offset, (celsius °C) → error
	must(err)                            // added to every result after linearization
	off, err := s.GetTemperatureOffset() // Read calibration offset, () → (float32 °C, error)
	must(err)                            // decodes TEMP_OFFSET
	println("offset (m°C)", int32(off*1000))
	err = s.SetTemperatureOffset(0.0) // Set calibration offset, (celsius °C) → error
	must(err)                         // remove the offset again

	err = s.UnlockEeprom()        // Unlock EEPROM, () → error
	must(err)                     // EUN=1: EEPROM-backed writes now persist
	busy, err := s.IsEepromBusy() // Check EEPROM busy, () → (bool, error)
	must(err)                     // reads EEPROM_UL.EEPROM_Busy
	println("eeprom busy", busy)
	err = s.LockEeprom()              // Lock EEPROM, () → error
	must(err)                         // EUN=0: writes are volatile again
	e1, err := s.ReadEepromScratch(1) // Read EEPROM scratch, (slot 1|2|3) → (uint16, error)
	must(err)                         // slot 1 holds part of the factory unique ID
	println("eeprom1", e1)
	err = s.WriteEepromScratch(2, 0x1234) // Write EEPROM scratch, (slot 2, value uint16) → error
	must(err)                             // only EEPROM2 is writable; volatile while locked
	e2, err := s.ReadEepromScratch(2)     // Read EEPROM scratch, (slot 1|2|3) → (uint16, error)
	must(err)                             // reads back EEPROM2
	println("eeprom2", e2)

	err = s.ConfigureAlert(temperature.TMP117AlertWindow, temperature.TMP117AlertActiveLow,
		temperature.TMP117PinAlert) // Configure ALERT, (mode, polarity, pinFunction) → error
	must(err) // sets T/nA, POL and DR/Alert together

	st, err := s.PollInterrupt() // Read alert flags, () → (uint8 mask, error)
	must(err)                    // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
	println("above high", st&temperature.TMP117SourceHigh != 0, "below low", st&temperature.TMP117SourceLow != 0)

	err = s.OnInterrupt(func(status uint8) { println("alert, status mask", status) }) // Subscribe to ALERT, (callback) → error
	must(err)                                                                         // callback receives the PollInterrupt mask
	time.Sleep(5000 * time.Millisecond)
	err = s.OffInterrupt() // Unsubscribe, () → error
	must(err)              // detaches the edge handler or stops the polling goroutine

	err = s.Reset() // Software reset, () → error
	must(err)       // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
}
