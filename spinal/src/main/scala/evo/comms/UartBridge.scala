package evo.comms

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._

/**
 * Bridges UART RX/TX to AXI-Stream (byte-wide).
 *
 * RX path: UartRx → 1-deep register → master Axi4Stream (dataWidth=1)
 * TX path: slave Axi4Stream (dataWidth=1) → UartTx
 *
 * @param clksPerBit Clock cycles per UART bit.
 */
class UartBridge(clksPerBit: Int = 868) extends Component {
  val axisConfig = Axi4StreamConfig(dataWidth = 1, useLast = true)

  val io = new Bundle {
    val rxSerial = in  Bool()
    val txSerial = out Bool()
    val rxStream = master(Axi4Stream(axisConfig))
    val txStream = slave(Axi4Stream(axisConfig))
  }

  // --- RX path: UART → AXI-Stream ---
  val uartRx = new UartRx(clksPerBit)
  uartRx.io.rxSerial := io.rxSerial

  val rxValid = RegInit(False)
  val rxByte  = Reg(Bits(8 bits)) init 0

  when(uartRx.io.rxDv) {
    rxValid := True
    rxByte  := uartRx.io.rxByte
  }
  when(io.rxStream.fire) {
    rxValid := False
  }

  io.rxStream.valid        := rxValid
  io.rxStream.payload.data := rxByte
  io.rxStream.payload.last := False

  // --- TX path: AXI-Stream → UART ---
  val uartTx = new UartTx(clksPerBit)

  val txReady = RegInit(True)
  when(io.txStream.fire) {
    txReady := False
  }
  when(uartTx.io.txDone) {
    txReady := True
  }

  uartTx.io.txByte := io.txStream.payload.data
  uartTx.io.txDv   := io.txStream.fire
  io.txStream.ready := txReady && !uartTx.io.txActive

  io.txSerial := uartTx.io.txSerial
}
