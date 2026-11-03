//go:build tinygo

// MX25L12845E NOR flash complete example — exercises every method of MX25L12845EFull.
// Platform: TinyGo / Raspberry Pi Pico W (machine.SPI0: GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS).
package main

import (
	"bytes"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const scratch = 0xFF0000

func run(conn connection.Connection) {
	flash, err := memory.NewMX25L12845EFull(conn, memory.MX25L12845EOptions{}) // Create MX25L12845E driver and check the ID, (connection, options) → (*MX25L12845EFull, error)
	if err != nil {
		panic(err)
	}

	id, _ := flash.JEDECID()         // Read JEDEC ID, () → ([3]byte, error)
	res, _ := flash.ElectronicID()   // Read the old-style electronic ID (RES), () → (byte, error)
	man, dev, _ := flash.DeviceIDs() // Read manufacturer + device ID (REMS), () → (byte, byte, error)
	fmt.Printf("JEDEC % X, RES %02X, REMS %02X %02X\n", id, res, man, dev)

	sr, _ := flash.ReadStatus()     // Read the status register, () → (byte, error)
	bp, _ := flash.Protection()     // Read BP3:0, () → (byte, error)
	scur, _ := flash.ReadSecurity() // Read the security register, () → (byte, error)
	fmt.Printf("status 0x%02X, BP=%d, security 0x%02X\n", sr, bp, scur)
	esn, _ := flash.SerialNumber() // Read the serial-number OTP area, () → ([16]byte, error)
	// the factory ESN when security bit 0 is set, blank (FF) otherwise
	fmt.Printf("ESN: % X\n", esn)
	sfdp := make([]byte, 16)
	_ = flash.ReadSFDP(0, sfdp) // Read the SFDP table, (address, buf) → error
	// starts with the signature "SFDP"
	fmt.Printf("SFDP: % X\n", sfdp)

	_ = flash.EraseSector(scratch) // Erase one 4 KiB sector, (address) → error
	// takes 60 ms typ, 300 ms max
	page := make([]byte, 256)
	for i := range page {
		page[i] = byte(i)
	}
	_ = flash.PageProgram(scratch, page) // Program one page (must not cross a page boundary), (address, data) → error
	// 1.4 ms typ for 256 bytes; bits only go 1 -> 0
	back := make([]byte, 256)
	_ = flash.FastRead(scratch, back) // FAST_READ, (address, buf) → error
	// same data as ReadBytes, for clocks above 50 MHz
	fmt.Println("page program + fast_read:", bytes.Equal(page, back))

	_ = flash.EraseBlock32K(scratch) // Erase a 32 KiB block, (address) → error
	// 0.5 s typ
	_ = flash.EraseBlock64K(scratch) // Erase a 64 KiB block, (address) → error
	// 0.7 s typ
	blank := make([]byte, 16)
	_ = flash.ReadBytes(scratch, blank) // Read bytes, (address, buf) → error
	fmt.Printf("after block erase: % X\n", blank)
	// ChipErase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

	text := []byte("erase-aware write")
	_ = flash.WriteBytes(scratch+100, text) // Write bytes, erasing the sector only when needed, (address, data) → error
	_ = flash.WriteBytes(scratch+100, text) // the same bytes again: nothing is programmed or erased
	got := make([]byte, len(text))
	_ = flash.ReadBytes(scratch+100, got)
	fmt.Printf("text: %s\n", got)

	_ = flash.DeepPowerDown() // Enter deep power-down, () → error
	// only RDP / RES are accepted afterwards
	_ = flash.ReleaseDeepPowerDown() // Leave deep power-down (RDP, waits tRES2), () → error
	busy, _ := flash.IsBusy()        // WIP bit, () → (bool, error)
	fmt.Println("busy:", busy)
	_ = flash.WaitReady(time.Second) // Wait for WIP = 0, (timeout) → error

	info := flash.Info() // Geometry and capabilities, () → storage.BlockInfo
	// 512-byte blocks, 4 KiB erase unit, needs_erase
	fmt.Printf("%+v\n", info)
	// SetProtection(), WriteStatus(), ProgramOTP() and LockOTP() change non-volatile state; see the spec before using them.

	_ = flash.EraseSector(scratch)
	fmt.Println("done")
}

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}
	time.Sleep(2 * time.Second)
	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	run(conn)
}
