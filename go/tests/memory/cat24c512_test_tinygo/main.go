//go:build tinygo

// CAT24C512 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a CAT24C512 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"bytes"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
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
	conn := connection.NewI2CConnectionWithWidth(i2c, 0x50, 2, nil, nil)
	chip, err := memory.NewCAT24C512Full(conn)
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

	check("size", chip.Size() == 65536)
	check("page_size", chip.PageSize() == 128)

	check("write_byte", chip.WriteByteAt(0x0010, 0x5A) == nil)
	v, err := chip.ReadByteAt(0x0010)
	check("write_byte/read_byte round-trip", err == nil && v == 0x5A)
	check("wait_ready after write", chip.WaitReady(10*time.Millisecond))

	data := make([]byte, 200)
	for i := range data {
		data[i] = byte(i)
	}
	check("page-split write", chip.Write(0x0070, data) == nil)
	back, err := chip.Read(0x0070, len(data))
	check("page-split write/read round-trip", err == nil && bytes.Equal(back, data))

	page := make([]byte, 128)
	for i := range page {
		page[i] = byte(i)
	}
	check("write_page", chip.WritePage(0x0300, page) == nil)
	pb, err := chip.Read(0x0300, 128)
	check("write_page round-trip", err == nil && bytes.Equal(pb, page))
	check("write_page crossing rejected", chip.WritePage(0x037C, make([]byte, 8)) != nil)

	check("fill", chip.Fill(0x0100, 100, 0xC3) == nil)
	f, err := chip.Read(0x0100, 100)
	check("fill content", err == nil && bytes.Equal(f, bytes.Repeat([]byte{0xC3}, 100)))
	check("fill zero", chip.Fill(0x0100, 100, 0) == nil)
	f, err = chip.Read(0x0100, 100)
	check("fill zero content", err == nil && bytes.Equal(f, make([]byte, 100)))

	check("last bytes write", chip.Write(0xFFFE, []byte{0x12, 0x34}) == nil)
	tail, err := chip.Read(0xFFFE, 2)
	check("last bytes round-trip", err == nil && bytes.Equal(tail, []byte{0x12, 0x34}))
	_, err = chip.Read(0xFFFF, 2)
	check("wrap rejected", err != nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
