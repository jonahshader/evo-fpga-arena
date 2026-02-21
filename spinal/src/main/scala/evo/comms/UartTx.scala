package evo.comms

import spinal.core._

/**
 * UART Transmitter.
 *
 * Transmits 8 bits of serial data, one start bit, one stop bit, and no parity bit.
 * When transmit is complete, txDone will be driven high for one clock cycle.
 *
 * Ported from fpga/src/imports/uart_tx.vhd
 *
 * @param clksPerBit Clock cycles per UART bit.
 *                   For 10 MHz clock and 115200 baud: 10000000 / 115200 = 87
 */
class UartTx(clksPerBit: Int = 115) extends Component {
  val io = new Bundle {
    val txDv     = in  Bool()
    val txByte   = in  Bits(8 bits)
    val txActive = out Bool()
    val txSerial = out Bool()
    val txDone   = out Bool()
  }

  // --- State enumeration ---
  object TxState extends SpinalEnum(binarySequential) {
    val IDLE, TX_START_BIT, TX_DATA_BITS, TX_STOP_BIT, CLEANUP = newElement()
  }

  // FSM state register
  val state = Reg(TxState()) init TxState.IDLE

  // Clock counter for bit timing
  val clkCount = Reg(UInt(log2Up(clksPerBit) bits)) init 0

  // Bit index for data transmission (0 to 7)
  val bitIndex = Reg(UInt(3 bits)) init 0

  // Transmit data register (latched on txDv)
  val txDataReg = Reg(Bits(8 bits)) init 0

  // Output registers
  val txDoneReg   = Reg(Bool()) init False
  val txActiveReg = Reg(Bool()) init False
  val txSerialReg = Reg(Bool()) init True

  // --- FSM ---
  switch(state) {
    is(TxState.IDLE) {
      txActiveReg := False
      txSerialReg := True
      txDoneReg   := False
      clkCount    := 0
      bitIndex    := 0

      when(io.txDv) {
        txDataReg := io.txByte
        state     := TxState.TX_START_BIT
      }
    }
    is(TxState.TX_START_BIT) {
      txActiveReg := True
      txSerialReg := False  // Start bit = 0

      when(clkCount < clksPerBit - 1) {
        clkCount := clkCount + 1
      } otherwise {
        clkCount := 0
        state    := TxState.TX_DATA_BITS
      }
    }
    is(TxState.TX_DATA_BITS) {
      txActiveReg := True
      txSerialReg := txDataReg(bitIndex)

      when(clkCount < clksPerBit - 1) {
        clkCount := clkCount + 1
      } otherwise {
        clkCount := 0
        when(bitIndex < 7) {
          bitIndex := bitIndex + 1
        } otherwise {
          bitIndex := 0
          state    := TxState.TX_STOP_BIT
        }
      }
    }
    is(TxState.TX_STOP_BIT) {
      txActiveReg := True
      txSerialReg := True  // Stop bit = 1

      when(clkCount < clksPerBit - 1) {
        clkCount := clkCount + 1
      } otherwise {
        txDoneReg := True
        clkCount  := 0
        state     := TxState.CLEANUP
      }
    }
    is(TxState.CLEANUP) {
      txActiveReg := False
      txDoneReg   := True
      state       := TxState.IDLE
    }
  }

  io.txActive := txActiveReg
  io.txSerial := txSerialReg
  io.txDone   := txDoneReg
}
