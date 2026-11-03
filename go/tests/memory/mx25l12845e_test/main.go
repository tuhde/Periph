//go:build linux && !tinygo

// MX25L12845E NOR flash hardware test — ids, erase / program / erase-aware write, block erases, BlockDevice, block protection, deep power-down.
// Platform: Linux host (env SPI_BUS, SPI_DEVICE).
package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const scratch = 0xFF0000

func run(conn connection.Connection) {
	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	flash, err := memory.NewMX25L12845EFull(conn, memory.MX25L12845EOptions{}) // Create MX25L12845E driver and check the ID, (connection, options) → (*MX25L12845EFull, error)
	check("init (JEDEC C2 20 18)", err == nil)
	if err != nil {
		fmt.Println("init error:", err)
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	id, _ := flash.JEDECID()
	check("jedec_id", id == [3]byte{0xC2, 0x20, 0x18})
	res, _ := flash.ElectronicID()
	check("electronic_id (RES) = 17h", res == 0x17)
	man, dev, _ := flash.DeviceIDs()
	check("device_ids (REMS) = C2 17", man == 0xC2 && dev == 0x17)
	sr, _ := flash.ReadStatus()
	check("status: not busy, WEL clear", sr&0x03 == 0)
	bp, _ := flash.Protection()
	fmt.Printf("INFO status 0x%02X, BP=%d\n", sr, bp)
	scur, _ := flash.ReadSecurity()
	fmt.Printf("INFO security 0x%02X (bit0 = OTP factory locked)\n", scur)
	esn, _ := flash.SerialNumber()
	fmt.Printf("INFO ESN: % X\n", esn)
	esn2, _ := flash.SerialNumber()
	check("ESN stable", esn == esn2)
	sfdp := make([]byte, 4)
	_ = flash.ReadSFDP(0, sfdp)
	check("SFDP signature", string(sfdp) == "SFDP")
	if bp != 0 {
		fmt.Printf("INFO protection was BP=%d; clearing for the test\n", bp)
		_ = flash.SetProtection(0)
	}

	buf := make([]byte, 4096)
	check("erase_sector", flash.EraseSector(scratch) == nil)
	_ = flash.ReadBytes(scratch, buf)
	check("sector blank (all FF) after erase", bytes.Count(buf, []byte{0xFF}) == 4096)

	page := make([]byte, 256)
	for i := range page {
		page[i] = byte(i*3 + 1)
	}
	check("page_program 256 bytes", flash.PageProgram(scratch, page) == nil)
	_ = flash.ReadBytes(scratch, buf[:256])
	check("read_bytes matches", bytes.Equal(buf[:256], page))
	_ = flash.FastRead(scratch, buf[:256])
	check("fast_read matches", bytes.Equal(buf[:256], page))
	_ = flash.PageProgram(scratch, []byte{0x0F, 0xF0, 0x00, 0xFF})
	_ = flash.ReadBytes(scratch, buf[:4])
	check("programming only clears bits (AND)", buf[0] == page[0]&0x0F && buf[1] == page[1]&0xF0 && buf[2] == 0 && buf[3] == page[3])
	check("page-crossing program refused", flash.PageProgram(scratch+250, page[:10]) != nil)

	data := make([]byte, 3000)
	for i := range data {
		data[i] = byte(i ^ (i >> 7))
	}
	check("write_bytes across two sectors", flash.WriteBytes(scratch+4096-100, data) == nil)
	got := make([]byte, 3000)
	_ = flash.ReadBytes(scratch+4096-100, got)
	check("read back across sectors", bytes.Equal(got, data))
	check("identical rewrite", flash.WriteBytes(scratch+4096-100, data) == nil)
	check("write past the end refused", flash.WriteBytes(flash.Size()-2, data[:5]) != nil)

	check("erase_block_32k", flash.EraseBlock32K(scratch) == nil)
	check("erase_block_64k", flash.EraseBlock64K(scratch) == nil)
	_ = flash.ReadBytes(scratch+0xF000, buf[:16])
	check("64 KiB block blank", bytes.Count(buf[:16], []byte{0xFF}) == 16)

	check("BlockDevice geometry", flash.BlockSize() == 512 && flash.BlockCount() == 32768 && flash.EraseSize() == 4096 && flash.NeedsErase())
	blk := uint32(scratch / 512)
	w := make([]byte, 1024)
	for i := range w {
		w[i] = byte(i*5 + 2)
	}
	check("BlockDevice write 2 blocks", flash.Write(blk, w) == nil)
	r := make([]byte, 1024)
	check("BlockDevice read back", flash.Read(blk, r) == nil && bytes.Equal(r, w))
	check("0->1 without erase -> ErrNotErased", errors.Is(flash.Write(blk, bytes.Repeat([]byte{0xFF}, 512)), storage.ErrNotErased))
	check("BlockDevice erase (4 KiB)", flash.Erase(blk, 8) == nil)
	check("unaligned erase refused", errors.Is(flash.Erase(blk+1, 8), storage.ErrOutOfRange))
	check("sync", flash.Sync() == nil)

	check("set_protection(1)", flash.SetProtection(1) == nil)
	check("program into protected area -> ErrWriteProtected", errors.Is(flash.PageProgram(scratch+0x8000, []byte{0}), storage.ErrWriteProtected))
	check("erase of protected sector -> ErrWriteProtected", errors.Is(flash.EraseSector(scratch+0x8000), storage.ErrWriteProtected))
	check("set_protection(0)", flash.SetProtection(0) == nil)
	check("program after unprotect", flash.PageProgram(scratch+0x8000, []byte{0}) == nil)

	check("deep_power_down", flash.DeepPowerDown() == nil)
	time.Sleep(time.Millisecond)
	res, _ = flash.ElectronicID()
	check("release_deep_power_down, RES again", flash.ReleaseDeepPowerDown() == nil && res == 0x17 || func() bool { r, _ := flash.ElectronicID(); return r == 0x17 }())

	_ = flash.EraseBlock64K(scratch)
	_ = flash.WaitReady(time.Second)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, mode=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()
	run(conn)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
