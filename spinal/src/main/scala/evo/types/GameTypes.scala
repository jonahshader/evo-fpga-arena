package evo.types

import spinal.core._
import spinal.lib._

/** Map and tile geometry constants. */
object MapConfig {
  val MAP_TILES_BITS       = 4
  val MAP_MAX_SIZE_TILES   = 1 << MAP_TILES_BITS       // 16
  val TILE_PX_BITS         = 3
  val TILE_PX              = 1 << TILE_PX_BITS          // 8
  val MAP_MAX_SIZE_PX_BITS = MAP_TILES_BITS + TILE_PX_BITS // 7
  val MAP_MAX_SIZE_PX      = 1 << MAP_MAX_SIZE_PX_BITS  // 128

  val MAP_MAX_SPAWNS = MAP_MAX_SIZE_TILES * MAP_MAX_SIZE_TILES / 2 // 128
}

/** Player geometry and scoring constants. */
object PlayerConfig {
  import MapConfig._
  val PLAYER_WIDTH       = TILE_PX - 2    // 6
  val PLAYER_HEIGHT      = TILE_PX - 2    // 6
  val PLAYER_KILL_HEIGHT = PLAYER_HEIGHT / 2 // 3

  val POINTS_PER_COIN = 3
  val POINTS_PER_KILL = 1
  val DEAD_TIMEOUT    = 60
}

/**
 * Fixed-point type used throughout game logic.
 *
 * F4 wraps SpinalHDL's SFix with a consistent format: sfixed(11 downto -4),
 * a 16-bit signed value with 12 integer bits (including sign) and 4 fractional bits.
 * Range: -2048.0 to +2047.9375, step 0.0625.
 *
 * Provides factory methods and an implicit class so arithmetic results can be
 * truncated back to F4 size with `.asF4` instead of the verbose `.raw.resized` pattern.
 */
object F4 {
  val peakExp = 11
  val resExp  = -4
  val fracBits = -resExp  // 4

  /** Create a new F4-sized SFix wire. */
  def apply(): SFix = SFix(peakExp exp, resExp exp)

  /** Create an F4 constant from a Scala Double. */
  def apply(v: Double): SFix = {
    val s = SFix(peakExp exp, resExp exp)
    s := v
    s
  }

  /**
   * Convert a raw integer to the Double representation.
   * Mirrors the VHDL `from_raw`: the integer is in units of the LSB.
   * e.g., fromRaw(25) = 25 * 2^(-4) = 1.5625
   */
  def fromRaw(value: Int): Double = value.toDouble / (1 << fracBits)

  /** Create an F4 from a hardware SInt (integer pixel value). */
  def fromInt(v: SInt): SFix = {
    val s = SFix(peakExp exp, resExp exp)
    s.raw := (v << fracBits).resized
    s
  }

  /** Extract the integer part of an F4 as SInt (truncate toward negative infinity). */
  def toInt(s: SFix): SInt = s.raw >> fracBits

  /** Implicit class adding idiomatic operations to SFix arithmetic results. */
  implicit class SFixOps(val s: SFix) extends AnyVal {
    /** Truncate an arithmetic result back to F4 size. */
    def asF4: SFix = {
      val r = SFix(peakExp exp, resExp exp)
      r.raw := s.raw.resized
      r
    }

    /** Negate an SFix value. */
    def negated: SFix = {
      val r = SFix(s.maxExp exp, s.minExp exp)
      r.raw := -s.raw
      r
    }
  }
}

/** Tile type — 3-bit enum with explicit binary encoding matching the VHDL. */
object TileType extends SpinalEnum(binarySequential) {
  val NOTHING, GROUND, AIR, SPRING, WATER_BODY, WATER_TOP, ICE, COIN = newElement()

  def isSolid(tile: SpinalEnumCraft[TileType.type]): Bool =
    tile === GROUND || tile === SPRING || tile === ICE

  def isWater(tile: SpinalEnumCraft[TileType.type]): Bool =
    tile === WATER_BODY || tile === WATER_TOP
}

/** Physics constants, expressed as Doubles via F4.fromRaw (matching VHDL from_raw). */
object PhysicsConstants {
  val JUMP_VEL          = F4.fromRaw(25)   // 1.5625
  val JUMP_MIDAIR_ACCEL = F4.fromRaw(1)    // 0.0625
  val SPRING_VEL        = F4.fromRaw(38)   // 2.375
  val MOVE_ACCEL        = F4.fromRaw(3)    // 0.1875
  val MOVE_ACCEL_WATER  = F4.fromRaw(2)    // 0.125
  val MOVE_ACCEL_ICE    = F4.fromRaw(1)    // 0.0625
  val MOVE_MAX_VEL      = F4.fromRaw(10)   // 0.625
  val GRAVITY           = F4.fromRaw(-2)   // -0.125
  val GRAVITY_WATER     = F4.fromRaw(-1)   // -0.0625
  val FALL_MAX_VEL      = F4.fromRaw(-20)  // -1.25
}

/** 2D fixed-point vector. */
case class F4Vec() extends Bundle {
  val x = F4()
  val y = F4()
}

/** Player state. */
case class Player() extends Bundle {
  val pos         = F4Vec()
  val vel         = F4Vec()
  val score       = SInt(16 bits)
  val deadTimeout = UInt(8 bits)
}

/** Tile position in map coordinates. */
case class TilePos() extends Bundle {
  import MapConfig._
  val x = UInt(MAP_TILES_BITS bits)
  val y = UInt(MAP_TILES_BITS bits)
}

/** Player input (3 booleans). */
case class PlayerInput() extends Bundle {
  val left  = Bool()
  val right = Bool()
  val jump  = Bool()
}

/** Full game state. */
case class GameState() extends Bundle {
  val p1      = Player()
  val p2      = Player()
  val coinPos = TilePos()
  val age     = UInt(16 bits)
}

/** Tilemap: 2D tile array + spawn locations + dimensions. */
case class Tilemap() extends Bundle {
  import MapConfig._
  val tiles        = Vec(Vec(TileType(), MAP_MAX_SIZE_TILES), MAP_MAX_SIZE_TILES)
  val spawn        = Vec(TilePos(), MAP_MAX_SPAWNS)
  val numSpawn     = UInt(8 bits)
  val numSpawnBits = UInt(4 bits)
  val width        = UInt((MAP_TILES_BITS + 1) bits)
  val height       = UInt((MAP_TILES_BITS + 1) bits)
}
