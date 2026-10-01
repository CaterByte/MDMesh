package com.mdmesh.kiosk.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskBrandTest {

    @Test
    fun `parseColor accepts short, long and alpha forms`() {
        assertEquals(0xFF0957C3.toInt(), KioskBrand.parseColor("#0957c3"))
        assertEquals(0xFF0957C3.toInt(), KioskBrand.parseColor(" 0957C3 "))
        assertEquals(0xFFFFFFFF.toInt(), KioskBrand.parseColor("#fff"))
        assertEquals(0x800957C3.toInt(), KioskBrand.parseColor("#800957c3"))
    }

    @Test
    fun `parseColor rejects junk`() {
        assertNull(KioskBrand.parseColor(null))
        assertNull(KioskBrand.parseColor(""))
        assertNull(KioskBrand.parseColor("#12345"))
        assertNull(KioskBrand.parseColor("blue"))
        assertNull(KioskBrand.parseColor("#gg0000"))
    }

    @Test
    fun `blank bar spec is the signature bar, none hides it`() {
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar(null))
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar("  "))
        assertTrue(KioskBrand.parseBar("none").isEmpty())
        assertTrue(KioskBrand.parseBar("NONE").isEmpty())
    }

    @Test
    fun `explicit hard stops are parsed and the last one is stretched to the end`() {
        val bar = KioskBrand.parseBar("#0957c3:40,#593c90:64,#aa205d:84,#fa052a:100")
        assertEquals(KioskBrand.SIGNATURE_BAR, bar)
        val stretched = KioskBrand.parseBar("#000000:50%, #ffffff:90%")
        assertEquals(listOf(BarStop(0xFF000000.toInt(), 0.5f), BarStop(0xFFFFFFFF.toInt(), 1f)), stretched)
    }

    @Test
    fun `colours without ends get equal widths`() {
        val bar = KioskBrand.parseBar("#000000,#ffffff")
        assertEquals(listOf(BarStop(0xFF000000.toInt(), 0.5f), BarStop(0xFFFFFFFF.toInt(), 1f)), bar)
    }

    @Test
    fun `malformed bar falls back to the signature bar`() {
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar("#000000:60,#ffffff:40"))
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar("#000000:50,#nothex:100"))
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar("#000000:50,#ffffff"))
        assertEquals(KioskBrand.SIGNATURE_BAR, KioskBrand.parseBar("#000000:0,#ffffff:100"))
    }

    @Test
    fun `defaults are the light MeinConnect theme`() {
        val t = KioskBrand.resolve(null, "", null, null, null, "  ", null, null)
        assertEquals(KioskBrand.SOFT, t.background)
        assertEquals(KioskBrand.INK, t.text)
        assertEquals(KioskBrand.BLUE, t.accent)
        assertEquals(KioskBrand.PAPER, t.onAccent)
        assertFalse(t.isDark)
        assertEquals(KioskBrand.SIGNATURE_BAR, t.brandBar)
        assertEquals(56, t.iconSizeDp)
        assertNull(t.title)
    }

    @Test
    fun `dark background flips the text default and keeps explicit values`() {
        val t = KioskBrand.resolve("#111418", null, "#3b82f6", "none", "large", "Küche Nord", null, null)
        assertTrue(t.isDark)
        assertEquals(KioskBrand.PAPER, t.text)
        assertEquals(0xFF3B82F6.toInt(), t.accent)
        assertTrue(t.brandBar.isEmpty())
        assertEquals(96, t.iconSizeDp)
        assertEquals("Küche Nord", t.title)
    }

    @Test
    fun `only https image urls are used`() {
        val t = KioskBrand.resolve(
            backgroundColor = null, textColor = null, accentColor = null, brandBar = null, iconSize = null,
            title = null, logoUrl = "http://x.test/logo.png", backgroundImageUrl = " https://x.test/bg.jpg ",
        )
        assertNull(t.logoUrl)
        assertEquals("https://x.test/bg.jpg", t.backgroundImageUrl)
    }

    @Test
    fun `light accent gets dark text on top`() {
        val t = KioskBrand.resolve(null, null, "#ffd54f", null, null, null, null, null)
        assertEquals(KioskBrand.INK, t.onAccent)
    }

    @Test
    fun `blend and luminance behave at the extremes`() {
        assertEquals(KioskBrand.INK, KioskBrand.blend(KioskBrand.INK, KioskBrand.PAPER, 0f))
        assertEquals(KioskBrand.PAPER, KioskBrand.blend(KioskBrand.INK, KioskBrand.PAPER, 1f))
        assertEquals(1.0, KioskBrand.luminance(KioskBrand.PAPER), 1e-9)
        assertEquals(0.0, KioskBrand.luminance(0xFF000000.toInt()), 1e-9)
    }
}
