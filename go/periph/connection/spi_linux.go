//go:build linux && !tinygo

// SPIConnection is the Linux implementation of the Connection interface for
// SPI, backed by raw ioctl(SPI_IOC_MESSAGE) against /dev/spidevB.D —
// no cgo, no native library.
package connection

import (
	"bytes"
	"fmt"
	"unsafe"

	"golang.org/x/sys/unix"
)

// SPI ioctl numbers from <linux/spi/spidev.h>.
const (
	spiIocMessage1 = 0x40206b00
	spiNoCS        = 0x40 // SPI_NO_CS mode bit
)

// spiIocTransfer mirrors struct spi_ioc_transfer from <linux/spi/spidev.h>.
// Field order and width must match the kernel's definition exactly.
type spiIocTransfer struct {
	txBuf          uint64
	rxBuf          uint64
	len            uint32
	speedHz        uint32
	delayUsecs     uint16
	bitsPerWord    uint8
	csChange       uint8
	txNmbufs       uint32
	rxNmbufs       uint32
	pad            uint32
}

// SPIConnection is a Linux /dev/spidevB.D-backed implementation of
// Connection. CS is kernel-managed (asserted by spidev around each
// ioctl(SPI_IOC_MESSAGE) call), so the connection does not need a
// separate CS pin.
type SPIConnection struct {
	connectionBase
	fd            int
	mode          uint8
	readBit       byte
	multiByteBit  byte // 0 = chip has no such bit
	cs            OutputPin // GPIO chip select (SPI_NO_CS); nil = kernel-managed CS
	held          bool      // Select() active: CS low across calls
}

// NewSPIConnection opens /dev/spidevBUS.DEVICE and configures it for the
// given SPI mode (0–3), 8 bits per word, at maxSpeedHz. CS idles high; the
// kernel asserts it around each transfer. intPin and enPin may be nil if
// the device's INT/EN lines are not wired.
//
// Uses the default register-addressing convention (readBit 0x80, no
// multi-byte bit). Use NewSPIConnectionWithConvention for chips needing a
// different convention (e.g. ADXL345-style, with an explicit multi-byte bit).
func NewSPIConnection(busNum, deviceNum int, mode uint8, maxSpeedHz uint32, intPin InputPin, enPin OutputPin) (*SPIConnection, error) {
	return NewSPIConnectionWithConvention(busNum, deviceNum, mode, maxSpeedHz, 0x80, 0, intPin, enPin)
}

// NewSPIConnectionWithConvention is like NewSPIConnection but additionally
// takes the chip's register-addressing convention: readBit is ORed into the
// command byte for a read (0 if the chip has no such bit), multiByteBit is
// ORed in for multi-byte (burst) transfers when length > 1 (0 if the chip
// has no such bit and always auto-increments).
func NewSPIConnectionWithConvention(busNum, deviceNum int, mode uint8, maxSpeedHz uint32,
	readBit, multiByteBit byte, intPin InputPin, enPin OutputPin) (*SPIConnection, error) {
	if maxSpeedHz == 0 {
		maxSpeedHz = 1_000_000
	}
	if mode > 3 {
		mode = 0
	}
	path := fmt.Sprintf("/dev/spidev%d.%d", busNum, deviceNum)
	fd, err := unix.Open(path, unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	// SPI mode (0–3).
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), 0x40216b01, uintptr(unsafe.Pointer(&mode))); errno != 0 { // SPI_IOC_WR_MODE
		_ = unix.Close(fd)
		return nil, fmt.Errorf("ioctl(SPI_IOC_WR_MODE): %w", errno)
	}
	// 8 bits per word.
	bpw := uint8(8)
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), 0x40216b03, uintptr(unsafe.Pointer(&bpw))); errno != 0 { // SPI_IOC_WR_BITS_PER_WORD
		_ = unix.Close(fd)
		return nil, fmt.Errorf("ioctl(SPI_IOC_WR_BITS_PER_WORD): %w", errno)
	}
	// Max speed.
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), 0x40246b04, uintptr(unsafe.Pointer(&maxSpeedHz))); errno != 0 { // SPI_IOC_WR_MAX_SPEED_HZ
		_ = unix.Close(fd)
		return nil, fmt.Errorf("ioctl(SPI_IOC_WR_MAX_SPEED_HZ): %w", errno)
	}
	return &SPIConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		fd:              fd,
		mode:            mode,
		readBit:         readBit,
		multiByteBit:    multiByteBit,
	}, nil
}

// NewSPIConnectionGPIOCS is like NewSPIConnection but disables the kernel's
// chip select (SPI_NO_CS) and drives cs itself, which enables the
// SPIExtensions (IdleClocks, Select/Deselect) needed by SD cards. cs must be
// an output wired to the device's CS and is driven high immediately.
func NewSPIConnectionGPIOCS(busNum, deviceNum int, mode uint8, maxSpeedHz uint32, cs OutputPin,
	intPin InputPin, enPin OutputPin) (*SPIConnection, error) {
	t, err := NewSPIConnection(busNum, deviceNum, mode, maxSpeedHz, intPin, enPin)
	if err != nil {
		return nil, err
	}
	noCS := t.mode | spiNoCS
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), 0x40216b01, uintptr(unsafe.Pointer(&noCS))); errno != 0 { // SPI_IOC_WR_MODE
		_ = t.Close()
		return nil, fmt.Errorf("ioctl(SPI_IOC_WR_MODE, SPI_NO_CS): %w", errno)
	}
	t.mode = noCS
	t.cs = cs
	if err := cs.Set(true); err != nil {
		_ = t.Close()
		return nil, err
	}
	return t, nil
}

// IdleClocks clocks n bits of 0xFF with CS deasserted. It needs a GPIO chip
// select (NewSPIConnectionGPIOCS); otherwise it returns ErrSPIExtensionUnsupported.
func (t *SPIConnection) IdleClocks(n int) error {
	if t.cs == nil {
		return ErrSPIExtensionUnsupported
	}
	if err := t.cs.Set(true); err != nil {
		return err
	}
	tx := bytes.Repeat([]byte{0xFF}, (n+7)/8)
	err := t.xfer(tx, make([]byte, len(tx)))
	if t.held {
		if e := t.cs.Set(false); err == nil {
			err = e
		}
	}
	return err
}

// SetSpeed changes the spidev clock (SPI_IOC_WR_MAX_SPEED_HZ).
func (t *SPIConnection) SetSpeed(hz uint32) error {
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), 0x40046b04, uintptr(unsafe.Pointer(&hz))); errno != 0 {
		return fmt.Errorf("ioctl(SPI_IOC_WR_MAX_SPEED_HZ): %w", errno)
	}
	return nil
}

// Select asserts the GPIO chip select and holds it low until Deselect.
// It needs NewSPIConnectionGPIOCS; otherwise it returns ErrSPIExtensionUnsupported.
func (t *SPIConnection) Select() error {
	if t.cs == nil {
		return ErrSPIExtensionUnsupported
	}
	if err := t.cs.Set(false); err != nil {
		return err
	}
	t.held = true
	return nil
}

// Deselect releases the chip select asserted by Select.
func (t *SPIConnection) Deselect() error {
	t.held = false
	if t.cs == nil {
		return nil
	}
	return t.cs.Set(true)
}

// ReadInto fills buf with len(buf) bytes read from the device, clocking 0xFF on MOSI.
func (t *SPIConnection) ReadInto(buf []byte) error {
	if !t.IsEnabled() {
		for i := range buf {
			buf[i] = 0
		}
		return nil
	}
	if len(buf) == 0 {
		return nil
	}
	tx := bytes.Repeat([]byte{0xFF}, len(buf))
	return t.xferCS(tx, buf)
}

// xferCS runs xfer with the GPIO chip select (if any) asserted around it.
func (t *SPIConnection) xferCS(tx, rx []byte) error {
	if t.cs != nil && !t.held {
		if err := t.cs.Set(false); err != nil {
			return err
		}
	}
	err := t.xfer(tx, rx)
	if t.cs != nil && !t.held {
		if e := t.cs.Set(true); err == nil {
			err = e
		}
	}
	return err
}

// Close releases the underlying /dev/spidevB.D file descriptor.
func (t *SPIConnection) Close() error {
	if t.fd < 0 {
		return nil
	}
	err := unix.Close(t.fd)
	t.fd = -1
	return err
}

// xfer runs a single SPI_IOC_MESSAGE(1) transfer with the given tx and
// rx buffers. tx and rx must have the same length. rx may be nil if no
// read data is needed.
func (t *SPIConnection) xfer(tx, rx []byte) error {
	if len(tx) != len(rx) && rx != nil {
		return fmt.Errorf("spi: tx/rx length mismatch (%d vs %d)", len(tx), len(rx))
	}
	if len(tx) == 0 {
		return nil
	}
	var txPtr, rxPtr uintptr
	if len(tx) > 0 {
		txPtr = uintptr(unsafe.Pointer(&tx[0]))
	}
	if len(rx) > 0 {
		rxPtr = uintptr(unsafe.Pointer(&rx[0]))
	}
	xfer := spiIocTransfer{
		txBuf:       uint64(txPtr),
		rxBuf:       uint64(rxPtr),
		len:         uint32(len(tx)),
		bitsPerWord: 8,
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), uintptr(spiIocMessage1), uintptr(unsafe.Pointer(&xfer))); errno != 0 {
		return fmt.Errorf("spi xfer: %w", errno)
	}
	return nil
}

// Write sends bytes to the device with a full-duplex transfer (rx is
// discarded).
func (t *SPIConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	dummy := make([]byte, len(data))
	return t.xferCS(data, dummy)
}

// Read clocks out n bytes from the device, sending dummy 0x00 bytes
// on the MOSI line.
func (t *SPIConnection) Read(n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return []byte{}, nil
	}
	tx := bytes.Repeat([]byte{0xFF}, n) // MOSI idles high (SD cards read other levels as commands)
	rx := make([]byte, n)
	if err := t.xferCS(tx, rx); err != nil {
		return nil, err
	}
	return rx, nil
}

// WriteRead sends data then reads n bytes in a single CS assertion:
// one full-duplex transfer of len(data)+n bytes, with the chip's
// response during the command phase discarded.
func (t *SPIConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return nil, nil
	}
	tx := bytes.Repeat([]byte{0xFF}, len(data)+n)
	copy(tx, data)
	rx := make([]byte, len(data)+n)
	if err := t.xferCS(tx, rx); err != nil {
		return nil, err
	}
	return rx[len(data):], nil
}

// ReadReg reads length bytes starting at register reg, building the SPI
// command byte from the connection's configured convention.
func (t *SPIConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	cmd := byte(reg) | t.readBit
	if length > 1 && t.multiByteBit != 0 {
		cmd |= t.multiByteBit
	}
	return t.WriteRead([]byte{cmd}, length)
}

// WriteReg writes data to register reg, building the SPI command byte from
// the connection's configured convention.
func (t *SPIConnection) WriteReg(reg uint32, data []byte) error {
	cmd := byte(reg) &^ t.readBit
	if len(data) > 1 && t.multiByteBit != 0 {
		cmd |= t.multiByteBit
	}
	return t.Write(append([]byte{cmd}, data...))
}
