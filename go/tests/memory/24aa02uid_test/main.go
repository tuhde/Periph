//go:build linux && !tinygo

// 24AA02UID hardware test — Linux host.
//
// Drives every method in the EEPROM24AA02UIDFull API and reports
// PASS/FAIL per check. Prints the standard ===DONE: ... === summary
// line at the end. Exits 0 on full pass, 1 on any failure.
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
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

	chip, err := memory.NewEEPROM24AA02UIDFull(tr)
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
	checkEq := func(label string, got, expected []byte) {
		if bytes.Equal(got, expected) {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Printf("FAIL %s: got % X, expected % X\n", label, got, expected)
			failed++
		}
	}

	uid, err := chip.ReadUID()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_uid:", err)
	}
	check("read_uid_length", len(uid) == 4)

	mfr, err := chip.ReadManufacturerCode()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_manufacturer_code:", err)
	}
	check("read_manufacturer_code", mfr == 0x29)

	dev, err := chip.ReadDeviceCode()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_device_code:", err)
	}
	check("read_device_code", dev == 0x41)

	if err := chip.WriteUserByte(0x10, 0x5A); err != nil {
		fmt.Fprintln(os.Stderr, "write_byte:", err)
	}
	got, err := chip.ReadUserByte(0x10)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_byte:", err)
	}
	check("write_byte_read_byte_round_trip", got == 0x5A)

	if err := chip.WritePage(0x40, []byte{0x11, 0x22, 0x33, 0x44}); err != nil {
		fmt.Fprintln(os.Stderr, "write_page:", err)
	}
	pageRead, err := chip.Read(0x40, 4)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read page:", err)
	}
	checkEq("write_page_read_back", pageRead, []byte{0x11, 0x22, 0x33, 0x44})

	if err := chip.Write(0x06, []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}); err != nil {
		fmt.Fprintln(os.Stderr, "write cross page:", err)
	}
	crossRead, err := chip.Read(0x06, 6)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read cross:", err)
	}
	checkEq("cross_page_write_read_back", crossRead, []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF})

	rangeRead, err := chip.Read(0x50, 16)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read range:", err)
	}
	check("sequential_read_length", len(rangeRead) == 16)

	uid2, err := chip.ReadUID()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_uid 2:", err)
	}
	checkEq("uid_unchanged_after_writes", uid2[:], uid[:])

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
