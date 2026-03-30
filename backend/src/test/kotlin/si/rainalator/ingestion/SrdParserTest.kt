package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.pow

class SrdParserTest {

    private lateinit var parser: SrdParser

    @BeforeEach
    fun setUp() {
        parser = SrdParser()
    }

    private fun loadResource(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!
            .bufferedReader().readText()

    @Nested
    inner class TinyTestFile {

        private lateinit var scan: RadarScan

        @BeforeEach
        fun parse() {
            scan = parser.parse(loadResource("tiny-test.srd"))
        }

        @Test
        fun `parses domain`() {
            assertThat(scan.header.domain).isEqualTo("TEST")
        }

        @Test
        fun `parses source radars`() {
            assertThat(scan.header.sourceRadars).containsExactly("T1")
        }

        @Test
        fun `parses timestamp as UTC`() {
            val expected = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)
            assertThat(scan.header.time).isEqualTo(expected)
        }

        @Test
        fun `parses grid dimensions`() {
            assertThat(scan.header.width).isEqualTo(5)
            assertThat(scan.header.height).isEqualTo(3)
        }

        @Test
        fun `parses cell size`() {
            assertThat(scan.header.cellSizeX).isEqualTo(1.0)
            assertThat(scan.header.cellSizeY).isEqualTo(1.0)
        }

        @Test
        fun `parses projection`() {
            assertThat(scan.header.projection).isEqualTo("LCC")
        }

        @Test
        fun `parses origin coordinates`() {
            assertThat(scan.header.originLon).isEqualTo(14.815)
            assertThat(scan.header.originLat).isEqualTo(46.120)
        }

        @Test
        fun `parses shift`() {
            assertThat(scan.header.shiftX).isEqualTo(-2.0)
            assertThat(scan.header.shiftY).isEqualTo(-1.0)
        }

        @Test
        fun `parses encoding parameters`() {
            assertThat(scan.header.offset).isEqualTo(64)
            assertThat(scan.header.start).isEqualTo(-8.0)
            assertThat(scan.header.slope).isEqualTo(2.0)
            assertThat(scan.header.nodata).isEqualTo(126)
        }

        @Test
        fun `values array has correct size`() {
            assertThat(scan.values).hasSize(5 * 3)
        }

        @Test
        fun `at-sign decodes to zero`() {
            // '@' (code 64) = below threshold → 0.0
            assertThat(scan.valueAt(0, 0)).isEqualTo(0.0f)
        }

        @Test
        fun `character A decodes correctly`() {
            // 'A' (65): dBR = (65-64)*2.0 + (-8.0) = -6.0 → 10^(-0.6) ≈ 0.2512
            val expected = 10.0.pow(-6.0 / 10.0).toFloat()
            assertThat(scan.valueAt(1, 0)).isCloseTo(expected, Offset.offset(0.001f))
        }

        @Test
        fun `character B decodes correctly`() {
            // 'B' (66): dBR = (66-64)*2.0 + (-8.0) = -4.0 → 10^(-0.4) ≈ 0.3981
            val expected = 10.0.pow(-4.0 / 10.0).toFloat()
            assertThat(scan.valueAt(3, 0)).isCloseTo(expected, Offset.offset(0.001f))
        }

        @Test
        fun `character C decodes correctly`() {
            // 'C' (67): dBR = (67-64)*2.0 + (-8.0) = -2.0 → 10^(-0.2) ≈ 0.6310
            val expected = 10.0.pow(-2.0 / 10.0).toFloat()
            assertThat(scan.valueAt(0, 1)).isCloseTo(expected, Offset.offset(0.001f))
        }

        @Test
        fun `character D decodes to 1 mm per h`() {
            // 'D' (68): dBR = (68-64)*2.0 + (-8.0) = 0.0 → 10^(0) = 1.0
            assertThat(scan.valueAt(2, 1)).isCloseTo(1.0f, Offset.offset(0.001f))
        }

        @Test
        fun `nodata character decodes to NaN`() {
            // '~' (126) = nodata
            assertThat(scan.valueAt(2, 2)).isNaN()
        }

        @Test
        fun `data is in row-major order`() {
            // Row 0: @, A, @, B, @
            // Row 1: C, @, D, @, @
            // Row 2: @, @, ~, @, @
            assertThat(scan.valueAt(0, 0)).isEqualTo(0.0f)   // row0, col0: @
            assertThat(scan.valueAt(1, 0)).isNotEqualTo(0.0f) // row0, col1: A
            assertThat(scan.valueAt(0, 1)).isNotEqualTo(0.0f) // row1, col0: C
            assertThat(scan.valueAt(2, 2)).isNaN()             // row2, col2: ~
        }
    }

    @Nested
    inner class RealArsoFile {

        private lateinit var scan: RadarScan

        @BeforeEach
        fun parse() {
            scan = parser.parse(loadResource("sample-si0-rrg.srd"))
        }

        @Test
        fun `parses ARSO domain`() {
            assertThat(scan.header.domain).isEqualTo("SI0")
        }

        @Test
        fun `parses two source radars`() {
            assertThat(scan.header.sourceRadars).containsExactly("SI1", "SI2")
        }

        @Test
        fun `grid is 401x301`() {
            assertThat(scan.header.width).isEqualTo(401)
            assertThat(scan.header.height).isEqualTo(301)
        }

        @Test
        fun `values array has correct size`() {
            assertThat(scan.values).hasSize(401 * 301)
        }

        @Test
        fun `ARSO origin coordinates`() {
            assertThat(scan.header.originLon).isEqualTo(14.815)
            assertThat(scan.header.originLat).isEqualTo(46.120)
        }

        @Test
        fun `ARSO shift parameters`() {
            assertThat(scan.header.shiftX).isEqualTo(-4.0)
            assertThat(scan.header.shiftY).isEqualTo(-6.0)
        }

        @Test
        fun `all values are either zero, NaN, or positive`() {
            scan.values.forEach { v ->
                assertThat(v.isNaN() || v >= 0.0f)
                    .withFailMessage("Unexpected negative value: $v")
                    .isTrue()
            }
        }

        @Test
        fun `contains some rain data`() {
            val nonZeroCount = scan.values.count { !it.isNaN() && it > 0.0f }
            assertThat(nonZeroCount).isGreaterThan(0)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws on empty input`() {
            assertThrows<IllegalArgumentException> {
                parser.parse("")
            }
        }

        @Test
        fun `throws on non-SRD3 format`() {
            assertThrows<IllegalArgumentException> {
                parser.parse("NOT-SRD\nsome data")
            }
        }

        @Test
        fun `throws on missing DATA marker`() {
            val header = """
                SRD-3
                domain    TEST
                ncell     5 3
                offset    64
                start     -8.0
                slope     2.0
                nodata    126
            """.trimIndent()
            assertThrows<IllegalArgumentException> {
                parser.parse(header)
            }
        }
    }
}
