//go:build linux && !tinygo

// I2CTransport is the Linux implementation of the Transport interface,
// backed by raw ioctl(I2C_RDWR) against /dev/i2c-N — no cgo, no native
// library. Mirrors the JVM transport's hand-laid-out structs in FFM.
package transport

import (
	"fmt"
	"unsafe"

	"golang.org/x/sys/unix"
)

// I2C ioctl numbers and flag bits from <linux/i2c-dev.h>.
const (
	i2cSlave     = 0x0703
	i2cRDWR      = 0x0707
	i2cMReadFlag = 0x0001
)

// i2cMsg mirrors struct i2c_msg from <linux/i2c.h>. Field order and width
// must match the kernel's definition exactly — the kernel reinterprets
// our byte buffer as this struct.
type i2cMsg struct {
	addr  uint16
	flags uint16
	len   uint16
	buf   uintptr // *byte
}

// i2cRdwrIoctlData mirrors struct i2c_rdwr_ioctl_data from <linux/i2c-dev.h>.
type i2cRdwrIoctlData struct {
	msgs uintptr // *i2cMsg
	nmsg uint32
}

// I2CTransport is a Linux /dev/i2c-N-backed implementation of Transport.
// The address is fixed at construction — one I2CTransport instance
// represents one device on the bus.
type I2CTransport struct {
	fd   int
	addr uint16
}

// NewI2CTransport opens /dev/i2c-N (N = bus) and binds the resulting file
// descriptor to the given 7-bit device address via I2C_SLAVE.
func NewI2CTransport(bus int, addr uint8) (*I2CTransport, error) {
	path := fmt.Sprintf("/dev/i2c-%d", bus)
	fd, err := unix.Open(path, unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(i2cSlave), uintptr(addr)); errno != 0 {
		_ = unix.Close(fd)
		return nil, fmt.Errorf("ioctl(I2C_SLAVE, 0x%02X): %w", addr, errno)
	}
	return &I2CTransport{fd: fd, addr: uint16(addr)}, nil
}

// Close releases the underlying /dev/i2c-N file descriptor.
func (t *I2CTransport) Close() error {
	if t.fd < 0 {
		return nil
	}
	err := unix.Close(t.fd)
	t.fd = -1
	return err
}

// Write sends a single write transaction (no repeated start) to the device.
func (t *I2CTransport) Write(data []byte) error {
	if len(data) == 0 {
		return nil
	}
	msg := i2cMsg{
		addr:  t.addr,
		flags: 0,
		len:   uint16(len(data)),
		buf:   uintptr(unsafe.Pointer(&data[0])),
	}
	data0 := i2cRdwrIoctlData{
		msgs: uintptr(unsafe.Pointer(&msg)),
		nmsg: 1,
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), uintptr(i2cRDWR), uintptr(unsafe.Pointer(&data0))); errno != 0 {
		return fmt.Errorf("i2c write: %w", errno)
	}
	return nil
}

// Read sends a single read transaction (no repeated start) to the device.
func (t *I2CTransport) Read(n int) ([]byte, error) {
	if n == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, n)
	msg := i2cMsg{
		addr:  t.addr,
		flags: i2cMReadFlag,
		len:   uint16(n),
		buf:   uintptr(unsafe.Pointer(&buf[0])),
	}
	data := i2cRdwrIoctlData{
		msgs: uintptr(unsafe.Pointer(&msg)),
		nmsg: 1,
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), uintptr(i2cRDWR), uintptr(unsafe.Pointer(&data))); errno != 0 {
		return nil, fmt.Errorf("i2c read: %w", errno)
	}
	return buf, nil
}

// WriteRead issues a combined write-then-read transaction in a single
// I2C_RDWR call, which produces a repeated START between the two phases.
func (t *I2CTransport) WriteRead(writeData []byte, n int) ([]byte, error) {
	if n == 0 {
		return []byte{}, nil
	}
	readBuf := make([]byte, n)
	var writePtr uintptr
	if len(writeData) > 0 {
		writePtr = uintptr(unsafe.Pointer(&writeData[0]))
	}
	msgs := [2]i2cMsg{
		{addr: t.addr, flags: 0, len: uint16(len(writeData)), buf: writePtr},
		{addr: t.addr, flags: i2cMReadFlag, len: uint16(n), buf: uintptr(unsafe.Pointer(&readBuf[0]))},
	}
	data := i2cRdwrIoctlData{
		msgs: uintptr(unsafe.Pointer(&msgs[0])),
		nmsg: 2,
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(t.fd), uintptr(i2cRDWR), uintptr(unsafe.Pointer(&data))); errno != 0 {
		return nil, fmt.Errorf("i2c write_read: %w", errno)
	}
	return readBuf, nil
}

// FileDescriptor returns the underlying /dev/i2c-N file descriptor. Intended
// for tests that want to inspect or close the descriptor directly.
func (t *I2CTransport) FileDescriptor() int {
	return t.fd
}
