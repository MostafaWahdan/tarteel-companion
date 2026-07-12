package com.tarteelcompanion.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutashabihatDifferTest {

    @Test
    fun `identical wordings produce no divergence`() {
        val words = listOf("إِنَّ", "ٱللَّهَ", "غَفُورٌ", "رَّحِيمٌ")
        val (left, right) = MutashabihatDiffer.diff(words, words)
        assertTrue(left.none { it.divergent })
        assertTrue(right.none { it.divergent })
    }

    @Test
    fun `single substituted word is the only divergence`() {
        val a = listOf("إِنَّ", "ٱللَّهَ", "غَفُورٌ", "رَّحِيمٌ")
        val b = listOf("إِنَّ", "ٱللَّهَ", "عَزِيزٌ", "رَّحِيمٌ")
        val (left, right) = MutashabihatDiffer.diff(a, b)

        assertEquals(listOf(false, false, true, false), left.map { it.divergent })
        assertEquals(listOf(false, false, true, false), right.map { it.divergent })
    }

    @Test
    fun `inserted word marks only the insertion`() {
        val a = listOf("وَمَا", "ٱللَّهُ", "بِغَٰفِلٍ")
        val b = listOf("وَمَا", "رَبُّكَ", "ٱللَّهُ", "بِغَٰفِلٍ")
        val (left, right) = MutashabihatDiffer.diff(a, b)

        assertTrue(left.none { it.divergent })
        assertEquals(listOf(false, true, false, false), right.map { it.divergent })
    }

    @Test
    fun `diacritic-only differences count as identical`() {
        // Same skeleton, different harakat — huffaz-relevant wording is identical.
        val a = listOf("يَعْمَلُونَ")
        val b = listOf("تَعْمَلُونَ")
        val c = listOf("يُعْمَلُونَ") // differs from a only in damma vs fatha

        val (l1, _) = MutashabihatDiffer.diff(a, c)
        assertFalse("harakat-only difference must not diverge", l1[0].divergent)

        val (l2, _) = MutashabihatDiffer.diff(a, b) // ya vs ta: real skeleton change
        assertTrue(l2[0].divergent)
    }

    @Test
    fun `trailing ayah number digits are ignored`() {
        val a = listOf("ٱلرَّحِيمِ ١")
        val b = listOf("ٱلرَّحِيمِ ٤")
        val (left, _) = MutashabihatDiffer.diff(a, b)
        assertFalse(left[0].divergent)
    }

    @Test
    fun `normalization strips annotation marks and folds alef variants`() {
        assertEquals(MutashabihatDiffer.normalize("ٱلْحَمْدُ"), MutashabihatDiffer.normalize("الحمد"))
        assertEquals(MutashabihatDiffer.normalize("أَنزَلَ"), MutashabihatDiffer.normalize("انزل"))
    }
}
