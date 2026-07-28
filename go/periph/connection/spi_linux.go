//go:build linux && !tinygo

// SPIConnection is the Linux implementation of the Connection interface for
// SPI, backed by raw ioctl(SPI_IOC_MESSAGE) against /dev/spidevB.D —
// no cgo, no native library.
package connection

import (
	"fmt"
	"unsafe"

	"golang.org/x/sys/unix"
)

// SPI ioctl numbers from <linux/spi/spidev.h>.
const (
	spiIocMessage1 = 0x40206b00
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
	fd   int
	mode uint8
}

// NewSPIConnection opens /dev/spidevBUS.DEVICE and configures it for
// SPI mode 0, 8 bits per word, at maxSpeedHz. CS idles high; the
// kernel asserts it around each transfer. intPin and enPin may be nil if
// the device's INT/EN lines are not wired.
func NewSPIConnection(busNum, deviceNum int, maxSpeedHz uint32, intPin InputPin, enPin OutputPin) (*SPIConnection, error) {
	if maxSpeedHz == 0 {
		maxSpeedHz = 1_000_000
	}
	path := fmt.Sprintf("/dev/spidev%d.%d", busNum, deviceNum)
	fd, err := unix.Open(path, unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	// SPI mode 0 (CPOL=0, CPHA=0).
	mode := uint8(0)
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
	}, nil
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
	return t.xfer(data, dummy)
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
	tx := make([]byte, n)
	rx := make([]byte, n)
	if err := t.xfer(tx, rx); err != nil {
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
	tx := make([]byte, len(data)+n)
	copy(tx, data)
	rx := make([]byte, len(data)+n)
	if err := t.xfer(tx, rx); err != nil {
		return nil, err
	}
	return rx[len(data):], nil
}
