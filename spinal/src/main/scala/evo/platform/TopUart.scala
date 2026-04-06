package evo.platform

import spinal.core._
import spinal.lib._
import evo.comms._
import evo.Neuroevolution

/**
 * UART-based top level: UartBridge + ProtocolRx/Tx + Neuroevolution.
 *
 * Replaces the VHDL core.vhd + top.vhd with a transport-agnostic protocol layer.
 *
 * @param clksPerBit Clock cycles per UART bit (default: 100MHz / 115200 = 868).
 */
class TopUart(clksPerBit: Int = 868) extends Component {
  val io = new Bundle {
    val rxSerial = in  Bool()
    val txSerial = out Bool()
  }

  // --- Instantiate ---
  val uartBridge = new UartBridge(clksPerBit)
  val protocolRx = new ProtocolRx
  val protocolTx = new ProtocolTx
  val ne         = new Neuroevolution

  // --- UART ↔ Protocol ---
  protocolRx.io.stream << uartBridge.io.rxStream
  uartBridge.io.txStream << protocolTx.io.stream

  // --- UART pins ---
  uartBridge.io.rxSerial := io.rxSerial
  io.txSerial := uartBridge.io.txSerial

  // --- Protocol RX → Neuroevolution ---
  ne.io.config          := protocolRx.io.config
  ne.io.tilemap         := protocolRx.io.tilemap
  ne.io.trainingGo      := protocolRx.io.cmd.trainingGo
  ne.io.trainingPause   := protocolRx.io.cmd.trainingPause
  ne.io.trainingResume  := protocolRx.io.cmd.trainingResume
  ne.io.inferenceGo     := protocolRx.io.cmd.inferenceGo
  ne.io.inferenceStop   := protocolRx.io.cmd.inferenceStop
  ne.io.humanInput      := protocolRx.io.humanInput
  ne.io.humanInputValid := protocolRx.io.humanInputValid
  ne.io.playAgainstNn   := protocolRx.io.cmd.playAgainstNn
  ne.io.dbBramDump      := protocolRx.io.cmd.dbBramDump
  ne.io.dbBramDumpIndex := protocolRx.io.cmd.dbBramDumpIndex

  // --- Neuroevolution → Protocol TX ---
  protocolTx.io.stateChange          := ne.io.announceNewState
  protocolTx.io.neState              := ne.io.state
  protocolTx.io.gaState              := ne.io.gaState
  protocolTx.io.gaStateSend          := ne.io.gaStateSend
  protocolTx.io.gameState            := ne.io.pgGs
  protocolTx.io.gameStateSend        := ne.io.transmitGs
  protocolTx.io.bramDumpParam        := ne.io.dbBramDumpParam
  protocolTx.io.bramDumpParamIndex   := ne.io.dbBramDumpParamIndex
  protocolTx.io.bramDumpParamValid   := ne.io.dbBramDumpParamValid

  // Test echo loops back through protocol layer
  protocolTx.io.testGo := protocolRx.io.cmd.testGo

  // Expose internal signals for simulation verification
  val dbgProtocolTxReady = out Bool()
  dbgProtocolTxReady := protocolTx.io.ready
}
