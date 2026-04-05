package evo.ga

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._
import evo.infra.Xormix32

/**
 * GA Controller — runs the genetic algorithm loop.
 *
 * Ported from fpga/src/ga.vhd
 *
 * FSM: IDLE -> INIT_BRAM -> RUN_FITNESS -> RUN_TOURNAMENT ->
 *      RUN_VICTOR_COPY -> COPY_PRIOR_BEST -> (loop or done)
 *
 * Instantiates: Xormix32 (global RNG), VictorCopy
 * Interfaces with: BramManager, Tournament, Fitness (external)
 */
class Ga extends Component {
  val io = new Bundle {
    // GA control
    val config       = in(GaConfigBundle())
    val go           = in Bool()
    val done         = out Bool()
    val pause        = in Bool()
    val resume       = in Bool()
    val rng          = out Bits(32 bits)
    val gaState      = out(GaStateBundle())
    val gaStateSend  = out Bool()

    // BramManager interface
    val bmCommand      = out(BramCommand())
    val bmReadIndex    = out UInt(8 bits)
    val bmWriteIndex   = out UInt(8 bits)
    val bmMutationRate = out UInt(8 bits)
    val bmGo           = out Bool()
    val bmDone         = in Bool()

    // Tournament interface
    val tnGo           = out Bool()
    val tnDone         = in Bool()
    val tnWinnerCounts = in(Vec(UInt(8 bits), GaConfig.MAX_POPULATION_SIZE))

    // Fitness interface
    val fnGo                   = out Bool()
    val fnDone                 = in Bool()
    val fnReferenceFitnessSum  = in SInt(16 bits)

    // Debug
    val dbBramDump      = in Bool()
    val dbBramDumpIndex = in UInt(8 bits)
  }

  // --- Internal registers ---
  val pauseQueued               = Reg(Bool()) init False
  val priorBestIndex            = Reg(UInt(8 bits)) init 0
  val priorBestIntervalCounter  = Reg(UInt(8 bits)) init 0
  val priorBestCopyGo           = Reg(Bool()) init False

  val dbBramDumpQueued  = Reg(Bool()) init False
  val dbBramDumpIndexR  = Reg(UInt(8 bits)) init 0
  val dbBramDumpGo      = Reg(Bool()) init False

  val initBramCounter         = Reg(UInt(8 bits)) init 0
  val initBramReadWriteIndex  = Reg(UInt(8 bits)) init 0
  val currentGen              = Reg(UInt(16 bits)) init 0
  val evalCounter             = Reg(UInt(8 bits)) init 0

  val initBramGo = Reg(Bool()) init False

  // --- Victor Copy ---
  val vcGo = Reg(Bool()) init False

  val vc = new VictorCopy
  vc.io.config        := io.config
  vc.io.winnerCounts  := io.tnWinnerCounts
  vc.io.bramManagerDone := io.bmDone
  vc.io.go            := vcGo

  // --- Xormix32 RNG ---
  val rngEnable = Bool()

  val xormix = new Xormix32(streamCount = 1)
  xormix.io.rst    := io.go
  xormix.io.seedX  := io.config.seed
  xormix.io.seedY  := B(0, 32 bits)
  xormix.io.enable := rngEnable

  io.rng := xormix.io.result

  // --- Computed values ---
  val populationSize = (U(1, 8 bits) << io.config.populationSizeExp.resize(8)).resized
  val totalBramsUsed = (populationSize + io.config.modelHistorySize + io.config.referenceCount).resized

  // --- GA state output ---
  io.gaState.currentGen      := 0
  io.gaState.referenceFitness := 0

  // --- Default output pulses ---
  io.done        := False
  io.gaStateSend := False
  io.fnGo        := False
  io.tnGo        := False

  // --- BM mux (combinational) ---
  // Priority: vcBmGo > initBramGo > dbBramDumpGo > priorBestCopyGo
  when(vc.io.bramManagerGo) {
    io.bmCommand      := vc.io.command
    io.bmReadIndex    := vc.io.readIndex
    io.bmWriteIndex   := vc.io.writeIndex
    io.bmMutationRate := vc.io.mutationRate
    io.bmGo           := vc.io.bramManagerGo
  } elsewhen(initBramGo) {
    io.bmCommand      := BramCommand.COPY_AND_MUTATE
    io.bmReadIndex    := initBramReadWriteIndex
    io.bmWriteIndex   := initBramReadWriteIndex
    io.bmMutationRate := 255  // max mutation for init
    io.bmGo           := initBramGo
  } elsewhen(dbBramDumpGo) {
    io.bmCommand      := BramCommand.DUMP
    io.bmReadIndex    := dbBramDumpIndexR
    io.bmWriteIndex   := 0
    io.bmMutationRate := 0
    io.bmGo           := dbBramDumpGo
  } otherwise {
    // Prior best copy
    io.bmCommand      := BramCommand.COPY_AND_MUTATE
    io.bmReadIndex    := 0
    io.bmWriteIndex   := priorBestIndex
    io.bmMutationRate := 0  // no mutation, just copy
    io.bmGo           := priorBestCopyGo
  }

  // --- Pulse register defaults ---
  initBramGo       := False
  priorBestCopyGo  := False
  dbBramDumpGo     := False
  vcGo             := False

  // --- Queue pause ---
  when(io.pause) {
    pauseQueued := True
  }

  // --- Queue bram dump ---
  when(io.dbBramDump) {
    dbBramDumpQueued  := True
    dbBramDumpIndexR  := io.dbBramDumpIndex
  }

  // --- FSM ---
  val fsm = new StateMachine {
    val idleS         = new State with EntryPoint
    val pausedS       = new State
    val initBramS     = new State
    val runFitnessS   = new State
    val runTournamentS = new State
    val runVictorCopyS = new State
    val copyPriorBestS = new State
    val dbBramDumpS    = new State

    rngEnable := !(isActive(idleS) || isActive(pausedS))

    idleS.whenIsActive {
      when(io.go) {
        initBramCounter := 0
        priorBestIndex  := populationSize
        goto(initBramS)
      }
    }

    initBramS.whenIsActive {
      when(io.bmDone) {
        when(initBramCounter === totalBramsUsed) {
          initBramCounter := 0
          io.fnGo := True
          goto(runFitnessS)
        } otherwise {
          initBramGo              := True
          initBramReadWriteIndex  := initBramCounter
          initBramCounter         := initBramCounter + 1
        }
      }
    }

    runFitnessS.whenIsActive {
      when(io.fnDone) {
        io.tnGo := True
        goto(runTournamentS)
      }
    }

    runTournamentS.whenIsActive {
      when(io.tnDone) {
        vcGo := True
        goto(runVictorCopyS)
      }
    }

    runVictorCopyS.whenIsActive {
      when(vc.io.done) {
        when(pauseQueued) {
          pauseQueued := False
          io.done := True
          goto(pausedS)
        } otherwise {
          // Check prior best interval
          when(io.config.modelHistoryInterval - 1 === priorBestIntervalCounter) {
            priorBestIntervalCounter := 0
            when(priorBestIndex === populationSize + io.config.modelHistorySize - 1) {
              priorBestIndex := populationSize
            } otherwise {
              priorBestIndex := priorBestIndex + 1
            }
            priorBestCopyGo := True
            goto(copyPriorBestS)
          } otherwise {
            priorBestIntervalCounter := priorBestIntervalCounter + 1
            io.fnGo := True
            goto(runFitnessS)
          }
        }
      }
    }

    copyPriorBestS.whenIsActive {
      when(io.bmDone) {
        // Record ga_state if eval interval hit
        when(io.config.evalInterval =/= 0 && evalCounter === io.config.evalInterval - 1) {
          evalCounter := 0
          io.gaState.currentGen      := currentGen
          io.gaState.referenceFitness := io.fnReferenceFitnessSum
          io.gaStateSend := True
        } otherwise {
          evalCounter := evalCounter + 1
        }

        when(currentGen === io.config.maxGen - 1 && !io.config.runUntilStopCmd) {
          currentGen := 0
          io.done := True
          goto(idleS)
        } otherwise {
          currentGen := currentGen + 1
          io.fnGo := True
          when(dbBramDumpQueued) {
            dbBramDumpQueued := False
            dbBramDumpGo := True
            goto(dbBramDumpS)
          } otherwise {
            goto(runFitnessS)
          }
        }
      }
    }

    dbBramDumpS.whenIsActive {
      when(io.bmDone) {
        goto(runFitnessS)
      }
    }

    pausedS.whenIsActive {
      when(io.resume) {
        // Replicate the transition logic of RUN_VICTOR_COPY_S
        when(io.config.modelHistoryInterval - 1 === priorBestIntervalCounter) {
          priorBestIntervalCounter := 0
          when(priorBestIndex === populationSize + io.config.modelHistorySize - 1) {
            priorBestIndex := populationSize
          } otherwise {
            priorBestIndex := priorBestIndex + 1
          }
          priorBestCopyGo := True
          goto(copyPriorBestS)
        } otherwise {
          priorBestIntervalCounter := priorBestIntervalCounter + 1
          io.fnGo := True
          goto(runFitnessS)
        }
      } elsewhen(io.go) {
        initBramCounter := 0
        priorBestIndex  := populationSize
        goto(initBramS)
      }
    }
  }
}
