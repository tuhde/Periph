//go:build linux && !tinygo

package discovery

import (
	"fmt"
	"unsafe"

	"golang.org/x/sys/unix"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ioctl numbers and constants from <linux/i2c-dev.h> / <linux/i2c.h>.
const (
	i2cSlave          = 0x0703
	i2cFuncs          = 0x0705
	i2cSmbus          = 0x0720
	i2cSmbusWrite     = 0
	i2cSmbusRead      = 1
	i2cSmbusQuick     = 0
	i2cSmbusByte      = 1
	i2cFuncSmbusQuick = 0x00010000
)

// i2cSmbusIoctlData mirrors struct i2c_smbus_ioctl_data.
type i2cSmbusIoctlData struct {
	readWrite uint8
	command   uint8
	size      uint32
	data      uintptr // *union i2c_smbus_data
}

// LinuxBus is a /dev/i2c-N bus opened for discovery.
type LinuxBus struct {
	n     int
	fd    int
	funcs uint
}

// Open opens /dev/i2c-N for discovery. Close the returned bus with its Close method.
func Open(bus int) (*LinuxBus, error) {
	path := fmt.Sprintf("/dev/i2c-%d", bus)
	fd, err := unix.Open(path, unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	var funcs uint
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(i2cFuncs), uintptr(unsafe.Pointer(&funcs))); errno != 0 {
		_ = unix.Close(fd)
		return nil, fmt.Errorf("ioctl(I2C_FUNCS): %w", errno)
	}
	return &LinuxBus{n: bus, fd: fd, funcs: funcs}, nil
}

// Close releases the /dev/i2c-N file descriptor.
func (b *LinuxBus) Close() error { return unix.Close(b.fd) }

func (b *LinuxBus) QuickWriteSupported() bool { return b.funcs&i2cFuncSmbusQuick != 0 }

func (b *LinuxBus) Probe(addr uint8, readByte bool) error {
	// EBUSY here means a kernel driver already owns the address.
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(b.fd), uintptr(i2cSlave), uintptr(addr)); errno != 0 {
		return errno
	}
	args := i2cSmbusIoctlData{readWrite: i2cSmbusWrite, command: 0, size: i2cSmbusQuick}
	var scratch [34]byte // union i2c_smbus_data
	if readByte {
		args = i2cSmbusIoctlData{readWrite: i2cSmbusRead, command: 0, size: i2cSmbusByte, data: uintptr(unsafe.Pointer(&scratch[0]))}
	}
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(b.fd), uintptr(i2cSmbus), uintptr(unsafe.Pointer(&args))); errno != 0 {
		return errno
	}
	return nil
}

// ReadRegister goes through the existing I2CConnection (RegisterConnection layer).
func (b *LinuxBus) ReadRegister(addr uint8, reg uint32, regBytes, length int) ([]byte, error) {
	conn, err := connection.NewI2CConnectionWithWidth(b.n, addr, uint8(regBytes), nil, nil)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	return conn.ReadReg(reg, length)
}

// Scan returns the sorted 7-bit addresses that respond on /dev/i2c-N (0x08-0x77).
func Scan(bus int) ([]uint8, error) {
	b, err := Open(bus)
	if err != nil {
		return nil, err
	}
	defer b.Close()
	return ScanBus(b)
}

// Discover scans /dev/i2c-N and names the chips that are connected; see DiscoverBus.
func Discover(bus int, active bool) ([]Device, error) {
	b, err := Open(bus)
	if err != nil {
		return nil, err
	}
	defer b.Close()
	return DiscoverBus(b, registry, active)
}
