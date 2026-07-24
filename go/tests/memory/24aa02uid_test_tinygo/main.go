//go:build tinygo

// 24AA02UID hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a 24AA02UID on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"bytes"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x50)
	chip, err := memory.NewEEPROM24AA02UIDFull(tr)
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
	checkEq := func(label string, got, expected []byte) {
		if bytes.Equal(got, expected) {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s: got % X, expected % X\n", label, got, expected)
			failed++
		}
	}

	uid, err := chip.ReadUID()
	if err != nil {
		fmt.Printf("read_uid: %v\n", err)
	}
	check("read_uid_length", len(uid) == 4)

	mfr, err := chip.ReadManufacturerCode()
	if err != nil {
		fmt.Printf("read_manufacturer_code: %v\n", err)
	}
	check("read_manufacturer_code", mfr == 0x29)

	dev, err := chip.ReadDeviceCode()
	if err != nil {
		fmt.Printf("read_device_code: %v\n", err)
	}
	check("read_device_code", dev == 0x41)

	if err := chip.WriteUserByte(0x10, 0x5A); err != nil {
		fmt.Printf("write_byte: %v\n", err)
	}
	got, err := chip.ReadUserByte(0x10)
	if err != nil {
		fmt.Printf("read_byte: %v\n", err)
	}
	check("write_byte_read_byte_round_trip", got == 0x5A)

	if err := chip.WritePage(0x40, []byte{0x11, 0x22, 0x33, 0x44}); err != nil {
		fmt.Printf("write_page: %v\n", err)
	}
	pageRead, err := chip.Read(0x40, 4)
	if err != nil {
		fmt.Printf("read page: %v\n", err)
	}
	checkEq("write_page_read_back", pageRead, []byte{0x11, 0x22, 0x33, 0x44})

	if err := chip.Write(0x06, []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}); err != nil {
		fmt.Printf("write cross page: %v\n", err)
	}
	crossRead, err := chip.Read(0x06, 6)
	if err != nil {
		fmt.Printf("read cross: %v\n", err)
	}
	checkEq("cross_page_write_read_back", crossRead, []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF})

	uid2, err := chip.ReadUID()
	if err != nil {
		fmt.Printf("read_uid 2: %v\n", err)
	}
	checkEq("uid_unchanged_after_writes", uid2[:], uid[:])

	time.Sleep(50 * time.Millisecond)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
