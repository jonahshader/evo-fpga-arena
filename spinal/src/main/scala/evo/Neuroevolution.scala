package evo

import spinal.core._
import spinal.lib._
import evo.types._
import evo.ga.{Ga, Fitness, Tournament}
import evo.infra.BramManager
import evo.nn.NeuralNetwork
import evo.game.PlayAGame

/**
 * Neuroevolution top-level — wires all sub-modules together.
 *
 * Ported from fpga/src/neuroevolution.vhd
 *
 * Contains:
 * - 3-state FSM: IDLE / TRAINING / PLAYING
 * - BRAM control mux (GA priority over Fitness)
 * - P2 player mux (human vs NN2)
 * - Frame limit gating (disabled in PLAYING mode)
 *
 * Sub-modules: Ga, Fitness, Tournament, BramManager, PlayAGame, NeuralNetwork x2
 */
class Neuroevolution extends Component {
  val io = new Bundle {
    val config           = in(GaConfigBundle())
    val tilemap          = in(Tilemap())

    // Controls
    val trainingGo       = in Bool()
    val trainingPause    = in Bool()
    val trainingResume   = in Bool()
    val inferenceGo      = in Bool()
    val inferenceStop    = in Bool()

    // From comms_rx
    val humanInput       = in(PlayerInput())
    val humanInputValid  = in Bool()
    val playAgainstNn    = in Bool()
    val dbBramDump       = in Bool()
    val dbBramDumpIndex  = in UInt(8 bits)

    // To comms_tx
    val announceNewState      = out Bool()
    val state                 = out(NeState())
    val pgGs                  = out(GameState())
    val transmitGs            = out Bool()
    val gaState               = out(GaStateBundle())
    val gaStateSend           = out Bool()
    val dbBramDumpParam       = out Bits(BramConfig.BRAM_WIDTH bits)
    val dbBramDumpParamIndex  = out UInt(BramConfig.BRAM_ADDR_BITS bits)
    val dbBramDumpParamValid  = out Bool()

    // Debug: expose game-level signals for system test diagnostics
    val dbGameDone  = out Bool()
    val dbGameScore = out SInt(16 bits)
  }

  // --- Sub-module instantiations ---
  val ga          = new Ga
  val fitness     = new Fitness
  val tournament  = new Tournament
  val bramManager = new BramManager
  val playagame   = new PlayAGame
  val nn1         = new NeuralNetwork
  val nn2         = new NeuralNetwork

  // --- State machine ---
  val stateReg         = Reg(NeState()) init NeState.IDLE
  val announceNewState = Reg(Bool()) init False

  io.state            := stateReg
  io.announceNewState := announceNewState

  announceNewState := False

  switch(stateReg) {
    is(NeState.IDLE) {
      when(io.trainingGo || io.trainingResume) {
        stateReg         := NeState.TRAINING
        announceNewState := True
      } elsewhen(io.inferenceGo) {
        stateReg         := NeState.PLAYING
        announceNewState := True
      }
    }
    is(NeState.TRAINING) {
      when(ga.io.done) {
        stateReg         := NeState.IDLE
        announceNewState := True
      }
    }
    is(NeState.PLAYING) {
      when(io.inferenceStop) {
        stateReg         := NeState.IDLE
        announceNewState := True
      }
    }
  }

  // --- Frame limit: disabled in PLAYING mode ---
  playagame.io.frameLimit := Mux(stateReg === NeState.PLAYING, U(0, 16 bits), io.config.frameLimit)

  // --- Config fan-out ---
  ga.io.config         := io.config
  fitness.io.gaConfig  := io.config
  tournament.io.ga_config := io.config

  // --- RNG fan-out (from GA's Xormix32) ---
  bramManager.io.rng := ga.io.rng
  tournament.io.rng  := ga.io.rng
  fitness.io.rng     := ga.io.rng

  // --- GA control ---
  ga.io.go             := io.trainingGo
  ga.io.pause          := io.trainingPause
  ga.io.resume         := io.trainingResume
  ga.io.dbBramDump     := io.dbBramDump
  ga.io.dbBramDumpIndex := io.dbBramDumpIndex

  // --- GA ↔ Fitness ---
  fitness.io.fitnessGo := ga.io.fnGo
  ga.io.fnDone         := fitness.io.fitnessDone
  ga.io.fnReferenceFitnessSum := fitness.io.referenceFitnessSum

  // --- GA ↔ Tournament ---
  tournament.io.go := ga.io.tnGo
  ga.io.tnDone     := tournament.io.done
  ga.io.tnWinnerCounts := tournament.io.winner_counts

  // --- Fitness → Tournament (population fitness) ---
  tournament.io.input_population_fitness := fitness.io.outputPopulationFitness

  // --- GA ↔ BramManager ---
  ga.io.bmDone := bramManager.io.done

  // --- BRAM control mux (GA priority over Fitness) ---
  when(ga.io.bmGo) {
    bramManager.io.command      := ga.io.bmCommand
    bramManager.io.readIndex    := ga.io.bmReadIndex
    bramManager.io.writeIndex   := ga.io.bmWriteIndex
    bramManager.io.mutationRate := ga.io.bmMutationRate
    bramManager.io.go           := ga.io.bmGo
  } otherwise {
    bramManager.io.command      := fitness.io.bmCommand
    bramManager.io.readIndex    := fitness.io.bmReadIndex
    bramManager.io.writeIndex   := 0
    bramManager.io.mutationRate := 0
    bramManager.io.go           := fitness.io.bmGo
  }

  // --- Fitness ↔ BramManager ---
  fitness.io.bmDone := bramManager.io.done

  // --- BRAM param broadcast to NNs ---
  nn1.io.param      := bramManager.io.param
  nn1.io.paramIndex := bramManager.io.paramIndex
  nn1.io.paramValid := bramManager.io.paramValidNn1

  nn2.io.param      := bramManager.io.param
  nn2.io.paramIndex := bramManager.io.paramIndex
  nn2.io.paramValid := bramManager.io.paramValidNn2

  // --- NN perspectives ---
  nn1.io.p1Perspective := True
  nn2.io.p1Perspective := False

  // --- NN ↔ PlayAGame ---
  nn1.io.gs := playagame.io.gs
  nn2.io.gs := playagame.io.gs

  nn1.io.go := playagame.io.p1RequestInput
  nn2.io.go := playagame.io.p2RequestInput

  playagame.io.p1Input      := nn1.io.action
  playagame.io.p1InputValid := nn1.io.done

  // --- P2 player mux ---
  when(stateReg === NeState.PLAYING && io.playAgainstNn) {
    playagame.io.p2Input      := io.humanInput
    playagame.io.p2InputValid := io.humanInputValid
  } otherwise {
    playagame.io.p2Input      := nn2.io.action
    playagame.io.p2InputValid := Mux(stateReg === NeState.PLAYING, io.humanInputValid, nn2.io.done)
  }

  // --- Fitness ↔ PlayAGame ---
  playagame.io.gameGo    := fitness.io.initPlayagame || io.inferenceGo
  playagame.io.swapStart := fitness.io.swapStart
  playagame.io.seed      := fitness.io.seed
  fitness.io.playagameDone := playagame.io.gameDone
  fitness.io.gameScore     := playagame.io.scoreOutput


  // --- Tilemap ---
  playagame.io.tilemap := io.tilemap

  // --- Game state output ---
  io.pgGs      := playagame.io.gs
  io.transmitGs := (stateReg === NeState.PLAYING) && playagame.io.frameEndPulse

  // --- GA state output ---
  io.gaState     := ga.io.gaState
  io.gaStateSend := ga.io.gaStateSend

  // --- Debug BRAM dump ---
  io.dbBramDumpParam      := bramManager.io.param
  io.dbBramDumpParamIndex := bramManager.io.paramIndex
  io.dbBramDumpParamValid := bramManager.io.paramValidDump

  // --- Debug game signals ---
  io.dbGameDone  := playagame.io.gameDone
  io.dbGameScore := playagame.io.scoreOutput
}
