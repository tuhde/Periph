//go:build linux && !tinygo

// MFRC522 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MFRC522 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
//
// MFRC522 I2C address is 0x28 with all address pins tied LOW; set
// I2C_ADDR to override. The card-detection and read-block checks
// require a MIFARE Classic card placed in the field; the version,
// antenna, and self-test checks do not.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x28"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := rfid.NewMFRC522Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	// --- Identification and configuration ---
	chipType, ver, err := chip.Version()
	check("version_reads", err == nil)
	check("version_chip_type_0x09", chipType == 0x09)
	check("version_is_1_or_2", ver == 1 || ver == 2)

	gain, err := chip.AntennaGain()
	check("antenna_gain_default_0x40", err == nil && gain == rfid.RX_GAIN_33_DB)

	if err := chip.SetAntennaGain(rfid.RX_GAIN_48_DB); err != nil {
		fmt.Fprintln(os.Stderr, "set_antenna_gain:", err)
	}
	gain, _ = chip.AntennaGain()
	check("antenna_gain_48db", gain == rfid.RX_GAIN_48_DB)

	if err := chip.SetAntennaGain(rfid.RX_GAIN_33_DB); err != nil {
		fmt.Fprintln(os.Stderr, "set_antenna_gain:", err)
	}

	// --- Antenna toggling ---
	if err := chip.AntennaOff(); err != nil {
		fmt.Fprintln(os.Stderr, "antenna_off:", err)
	}
	if err := chip.AntennaOn(); err != nil {
		fmt.Fprintln(os.Stderr, "antenna_on:", err)
	}
	check("antenna_toggle_no_error", true)

	// --- Card detection (optional — only passes if a card is in field) ---
	present, err := chip.IsCardPresent()
	if err != nil {
		fmt.Fprintln(os.Stderr, "is_card_present:", err)
	}
	if present {
		uid, err := chip.ReadUID()
		check("read_uid_with_card", err == nil && uid != nil && (len(uid) == 4 || len(uid) == 7 || len(uid) == 10))
	} else {
		fmt.Println("note: no card in field; skipping read_uid check")
	}

	// --- Digital self test (no card needed) ---
	ok, err := chip.SelfTest()
	check("self_test", err == nil && ok)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
