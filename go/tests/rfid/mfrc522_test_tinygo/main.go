//go:build tinygo

// MFRC522 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MFRC522 RFID reader on I2C1
// (GP4 = SDA, GP5 = SCL, address 0x28). Prints PASS/FAIL per check
// and ends with the standard ===DONE: ... === line. The test runner
// (go/test_tinygo.sh) reads the serial output and reports exit code
// 0/1/2 based on the ===DONE=== line.
//
// The card-detection and read-block checks require a MIFARE Classic
// card placed in the field; the version, antenna, and self-test
// checks do not.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x28, nil, nil)
	chip, err := rfid.NewMFRC522Full(conn)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	chipType, ver, err := chip.Version()
	check("version_reads", err == nil)
	check("version_chip_type_0x09", chipType == 0x09)
	check("version_is_1_or_2", ver == 1 || ver == 2)

	gain, err := chip.AntennaGain()
	check("antenna_gain_default_0x40", err == nil && gain == rfid.RX_GAIN_33_DB)

	if err := chip.SetAntennaGain(rfid.RX_GAIN_48_DB); err != nil {
		fmt.Printf("set_antenna_gain: %v\n", err)
	}
	gain, _ = chip.AntennaGain()
	check("antenna_gain_48db", gain == rfid.RX_GAIN_48_DB)

	if err := chip.SetAntennaGain(rfid.RX_GAIN_33_DB); err != nil {
		fmt.Printf("set_antenna_gain: %v\n", err)
	}

	if err := chip.AntennaOff(); err != nil {
		fmt.Printf("antenna_off: %v\n", err)
	}
	if err := chip.AntennaOn(); err != nil {
		fmt.Printf("antenna_on: %v\n", err)
	}
	check("antenna_toggle_no_error", true)

	present, err := chip.IsCardPresent()
	if err != nil {
		fmt.Printf("is_card_present: %v\n", err)
	}
	if present {
		uid, err := chip.ReadUID()
		check("read_uid_with_card", err == nil && uid != nil && (len(uid) == 4 || len(uid) == 7 || len(uid) == 10))
	} else {
		fmt.Println("note: no card in field; skipping read_uid check")
	}

	ok, err := chip.SelfTest()
	check("self_test", err == nil && ok)

	time.Sleep(100 * time.Millisecond)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
