package io.embodit.spatialoffice.core.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The formula evaluator, exercised through the workbook the way the grid uses it. */
class FormulaTest {

    /** A sheet with A1..A5 = 10, 20, 30, "text", (empty) and B1 = TRUE; formulas are typed into column D. */
    private fun book(): XlsxWorkbook {
        val wb = XlsxWorkbook.blank()
        wb.setCell(0, 0, 0, "10")
        wb.setCell(0, 1, 0, "20")
        wb.setCell(0, 2, 0, "30")
        wb.setCell(0, 3, 0, "text")
        wb.setCell(0, 0, 1, "TRUE")
        return wb
    }

    private var nextRow = 0
    private val wb = book()

    private fun ev(formula: String): Cell {
        val r = nextRow++
        wb.setCell(0, r, 3, formula)
        return wb.cell(0, r, 3)
    }

    private fun num(formula: String): Double = ev(formula).let { assertEquals(formula, CellKind.NUMBER, it.kind); it.number!! }
    private fun txt(formula: String): String = ev(formula).display

    @Test fun precedenceAndOperators() {
        assertEquals(7.0, num("=1+2*3"), 0.0)
        assertEquals(9.0, num("=(1+2)*3"), 0.0)
        assertEquals(64.0, num("=2^3^2"), 0.0)       // left-associative
        assertEquals(4.0, num("=-2^2"), 0.0)         // unary minus binds tighter than ^
        assertEquals(-6.0, num("=2*-3"), 0.0)
        assertEquals(0.25, num("=2^-2"), 0.0)
        assertEquals(2.5, num("=10/4"), 0.0)
        assertEquals(1.0, num("= 1 + 2 - 2 "), 0.0)
        assertEquals(1000.0, num("=1E3"), 0.0)
        assertEquals(0.5, num("=.5"), 0.0)
        assertEquals("a2", txt("=\"a\"&1+1"))        // & binds looser than +
        assertEquals("TRUE", txt("=1+1=2"))          // comparison binds loosest
        assertEquals("10 units", txt("=A1&\" units\""))
        assertEquals("say \"hi\"", txt("=\"say \"\"hi\"\"\""))
    }

    @Test fun percent() {
        assertEquals(0.1, num("=10%"), 1e-12)
        assertEquals(100.0, num("=50%*200"), 1e-9)
        assertEquals(2.0, num("=A2*10%"), 1e-9)
        assertEquals(-0.05, num("=-5%"), 1e-12)
        assertEquals(0.001, num("=10%%"), 1e-12)
    }

    @Test fun comparisons() {
        assertEquals("TRUE", txt("=1<2"))
        assertEquals("FALSE", txt("=1>=2"))
        assertEquals("TRUE", txt("=1<>2"))
        assertEquals("TRUE", txt("=\"a\"=\"A\""))    // text compares without case
        assertEquals("TRUE", txt("=\"a\"<\"b\""))
        assertEquals("TRUE", txt("=99<\"a\""))       // numbers sort before text
        assertEquals("TRUE", txt("=A5=0"))           // an empty cell equals zero …
        assertEquals("TRUE", txt("=A5=\"\""))        // … and the empty string
        assertEquals(CellKind.BOOL, ev("=A1>5").kind)
    }

    @Test fun rangesAndAggregates() {
        assertEquals(60.0, num("=SUM(A1:A3)"), 0.0)
        assertEquals(60.0, num("=SUM(A1:A5)"), 0.0)          // text and blanks in a range are skipped
        assertEquals(60.0, num("=SUM(A:A)"), 0.0)            // whole column
        assertEquals(10.0, num("=SUM(1:1)-D1"), 0.0)         // whole row: A1 + D1 (the TRUE in B1 is skipped), minus D1
        assertEquals(63.0, num("=SUM(A1:A3,1,2)"), 0.0)
        assertEquals(61.0, num("=SUM(A1:A3,TRUE)"), 0.0)     // a literal TRUE counts as 1
        assertEquals(62.0, num("=SUM(A1:A3,\"2\")"), 0.0)    // a numeric string given directly counts
        assertEquals(60.0, num("=sum( a1 : a3 )".replace(" : ", ":")), 0.0)
        assertEquals(20.0, num("=AVERAGE(A1:A4)"), 0.0)
        assertEquals(10.0, num("=MIN(A1:A5)"), 0.0)
        assertEquals(30.0, num("=MAX(A1:A5)"), 0.0)
        assertEquals(35.0, num("=MAX(A1:A3,35)"), 0.0)
        assertEquals(3.0, num("=COUNT(A1:A5)"), 0.0)
        assertEquals(4.0, num("=COUNTA(A1:A5)"), 0.0)
        assertEquals(0.0, num("=SUM(F1:G9)"), 0.0)
        assertEquals(0.0, num("=MAX(F1:G9)"), 0.0)
        assertEquals("#DIV/0!", txt("=AVERAGE(F1:G9)"))
        assertEquals(10.0, num("=A1"), 0.0)
        assertEquals(0.0, num("=A5"), 0.0)                   // a reference to an empty cell shows 0
        assertEquals(30.0, num("=\$A\$1+A\$2"), 0.0)
    }

    @Test fun logicalAndRoundingFunctions() {
        assertEquals("big", txt("=IF(A1>5,\"big\",\"small\")"))
        assertEquals("small", txt("=IF(A1>50,\"big\",\"small\")"))
        assertEquals("FALSE", txt("=IF(A1>50,1)"))
        assertEquals(2.0, num("=IF(A1>50,1,IF(A2=20,2,3))"), 0.0)
        assertEquals(1.0, num("=IF(TRUE,1,1/0)"), 0.0)       // the branch not taken is not evaluated
        assertEquals("TRUE", txt("=AND(A1>5,A2>5)"))
        assertEquals("FALSE", txt("=AND(A1>5,A2>50)"))
        assertEquals("TRUE", txt("=OR(A1>50,A2>5)"))
        assertEquals("FALSE", txt("=NOT(B1)"))
        assertEquals("TRUE", txt("=NOT(0)"))
        assertEquals(3.0, num("=ROUND(2.5,0)"), 0.0)         // half away from zero
        assertEquals(-3.0, num("=ROUND(-2.5,0)"), 0.0)
        assertEquals(1.01, num("=ROUND(1.005,2)"), 0.0)
        assertEquals(1200.0, num("=ROUND(1234,-2)"), 0.0)
        assertEquals(3.14, num("=ROUND(3.14159,2)"), 0.0)
        assertEquals(5.0, num("=ABS(-5)"), 0.0)
        assertEquals(-2.0, num("=INT(-1.5)"), 0.0)
        assertEquals(1.0, num("=INT(1.9)"), 0.0)
        assertEquals(12.0, num("=ROUND(AVERAGE(A1:A3)/2,0)+ABS(MIN(-2,A1))"), 0.0)
    }

    @Test fun errors() {
        assertEquals("#DIV/0!", txt("=1/0"))
        assertEquals(CellKind.ERROR, ev("=1/0").kind)
        assertEquals("#VALUE!", txt("=\"x\"+1"))
        assertEquals("#VALUE!", txt("=A4*2"))
        assertEquals("#DIV/0!", txt("=1+1/0"))               // errors propagate through operators …
        assertEquals("#DIV/0!", txt("=SUM(1,1/0)"))          // … and through functions
        assertEquals("#REF!", txt("=NoSuchSheet!A1"))
        assertEquals("#N/A", txt("=#N/A"))
        assertEquals("#NUM!", txt("=10^400"))
        assertEquals("fallback", txt("=IFERROR(1/0,\"fallback\")"))
        // an error stored in a cell reaches a formula that reads it
        val r = nextRow++
        wb.setCell(0, r, 4, "=1/0")
        assertEquals("#DIV/0!", txt("=E${r + 1}+1"))
        assertEquals("#DIV/0!", txt("=SUM(E${r + 1}:E${r + 1})"))
        // unknown function: an error value, but still a formula whose text can be edited
        val u = ev("=NOSUCHFN(A1)")
        assertEquals(CellKind.ERROR, u.kind)
        assertEquals("#NAME?", u.display)
        assertEquals("=NOSUCHFN(A1)", u.input)
        assertTrue(u.isFormula)
        // text that is not a formula at all is kept as text
        val t = ev("=1+")
        assertEquals(CellKind.TEXT, t.kind)
        assertFalse(t.isFormula)
        assertEquals("=1+", t.display)
    }

    @Test fun cyclesEndInAnErrorInsteadOfHanging() {
        val b = XlsxWorkbook.blank()
        b.setCell(0, 0, 0, "=A1+1")                // refers to itself
        assertEquals("#CYCLE!", b.cell(0, 0, 0).display)
        assertEquals(CellKind.ERROR, b.cell(0, 0, 0).kind)
        b.setCell(0, 1, 0, "=B2")                  // A2 → B2 → C2 → A2
        b.setCell(0, 1, 1, "=C2")
        b.setCell(0, 1, 2, "=A2+1")
        b.setCell(0, 2, 0, "=A2*2")                // depends on the cycle
        b.setCell(0, 3, 0, "=5+5")                 // does not
        for (c in 0..2) assertEquals("#CYCLE!", b.cell(0, 1, c).display)
        assertEquals("#CYCLE!", b.cell(0, 2, 0).display)
        assertEquals(10.0, b.cell(0, 3, 0).number!!, 0.0)
        // breaking the cycle heals every cell on it
        b.setCell(0, 1, 2, "7")
        assertEquals(7.0, b.cell(0, 1, 0).number!!, 0.0)
        assertEquals(14.0, b.cell(0, 2, 0).number!!, 0.0)
        // a cycle longer than the evaluator's recursion limit
        val n = 400
        for (i in 0 until n) b.setCell(0, 10 + i, 5, "=F${10 + ((i + 1) % n) + 1}+1")
        assertEquals("#CYCLE!", b.cell(0, 10, 5).display)
        assertEquals("#CYCLE!", b.cell(0, 10 + n - 1, 5).display)
        // a file with a cycle saves and reopens; the cycle error is not written as a cached value
        val re = XlsxWorkbook.open(b.save())
        assertEquals("=A1+1", re.cell(0, 0, 0).input)
        assertEquals("#CYCLE!", re.cell(0, 0, 0).display)
    }

    @Test fun dependentsRecalculateAfterAnEdit() {
        val b = XlsxWorkbook.blank()
        b.setCell(0, 0, 0, "1")
        b.setCell(0, 0, 1, "=A1+1")
        b.setCell(0, 0, 2, "=B1*2")
        b.setCell(0, 0, 3, "=SUM(A1:C1)")
        b.setCell(0, 0, 4, "=IF(D1>10,\"hi\",\"lo\")")
        assertEquals(4.0, b.cell(0, 0, 2).number!!, 0.0)
        assertEquals(7.0, b.cell(0, 0, 3).number!!, 0.0)
        assertEquals("lo", b.cell(0, 0, 4).display)
        b.setCell(0, 0, 0, "5")
        assertEquals(6.0, b.cell(0, 0, 1).number!!, 0.0)
        assertEquals(12.0, b.cell(0, 0, 2).number!!, 0.0)
        assertEquals(23.0, b.cell(0, 0, 3).number!!, 0.0)
        assertEquals("hi", b.cell(0, 0, 4).display)
        // replacing a formula in the middle of the chain
        b.setCell(0, 0, 1, "100")
        assertEquals(200.0, b.cell(0, 0, 2).number!!, 0.0)
        assertEquals(305.0, b.cell(0, 0, 3).number!!, 0.0)
        // a text value where a number was: the dependants show the error, then recover
        b.setCell(0, 0, 0, "oops")
        assertEquals(300.0, b.cell(0, 0, 3).number!!, 0.0)
        b.setCell(0, 0, 1, "=A1+1")
        assertEquals("#VALUE!", b.cell(0, 0, 2).display)
        b.setCell(0, 0, 0, "1")
        assertEquals(7.0, b.cell(0, 0, 3).number!!, 0.0)
    }

    @Test fun longChainsDoNotOverflowTheStack() {
        val b = XlsxWorkbook.blank()
        val n = 3000
        b.setCell(0, 0, 0, "1")
        // written bottom-up so that the first evaluation has to walk the whole chain
        for (i in n - 1 downTo 1) b.setCell(0, i, 0, "=A$i+1")
        assertEquals(n.toDouble(), b.cell(0, n - 1, 0).number!!, 0.0)
        b.setCell(0, 0, 0, "11")
        assertEquals(n + 10.0, b.cell(0, n - 1, 0).number!!, 0.0)
        assertEquals(12.0, b.cell(0, 1, 0).number!!, 0.0)
        val re = XlsxWorkbook.open(b.save())
        assertEquals(n + 10.0, re.cell(0, n - 1, 0).number!!, 0.0)
    }

    @Test fun crossSheetReferences() {
        val wb = Fx.sales()
        assertEquals(56775.0, wb.cell(1, 1, 5).number!!, 1e-9)       // =Sales!N6
        wb.setCell(1, 7, 0, "=SUM(Sales!B2:B5)+'Sales'!\$B\$2+sales!b2")
        assertEquals("=SUM(Sales!B2:B5)+'Sales'!\$B\$2+sales!B2", wb.cell(1, 7, 0).input)
        assertEquals(505.0, wb.cell(1, 7, 0).number!!, 0.0)
        wb.setCell(0, 20, 0, "=Notes!B2+Notes!F6")                   // and back: Notes!F6 itself reads Sales!B2
        assertEquals(740.0, wb.cell(0, 20, 0).number!!, 0.0)
        wb.setCell(0, 1, 1, "121")                                   // Sales!B2
        assertEquals(508.0, wb.cell(1, 7, 0).number!!, 0.0)
        assertEquals(741.0, wb.cell(0, 20, 0).number!!, 0.0)
        // a sheet name with a space needs quotes
        val c = Fx.cached()
        assertEquals(122.0, c.cell(1, 1, 0).number!!, 0.0)           // ='Other Sheet'!A1*2
        c.setCell(0, 9, 0, "='Other Sheet'!A2+Data!B2")
        assertEquals(132.0, c.cell(0, 9, 0).number!!, 0.0)
    }

    @Test fun formulaTextHelpers() {
        assertEquals("SUM(A1:B2)+TRUE", Formula.normalize("sum(a1:b2)+true"))
        assertEquals("IF(A1>0,\"sum(a1)\",'my sheet'!B2)", Formula.normalize("if(a1>0,\"sum(a1)\",'my sheet'!b2)"))
        fun shift(f: String, own: String = "S", at: Int = 2) = Formula.shiftRows(f, Lexer.tokenize(f), own, "S", at)
        assertEquals("A2+A4+\$A\$4", shift("A2+A3+\$A\$3"))
        assertEquals("SUM(A1:A4)", shift("SUM(A1:A3)"))              // a range across the insertion point grows
        assertEquals("SUM(A1:A2)", shift("SUM(A1:A2)"))              // a range that ends above it does not
        assertEquals("SUM(A:A)+SUM(2:4)", shift("SUM(A:A)+SUM(2:3)"))
        assertEquals("Other!A3+S!A4+'S'!A4", shift("Other!A3+S!A3+'S'!A3"))
        assertEquals("A3+S!A4", shift("A3+S!A3", own = "Other"))
        assertEquals("LOG10(A4)&\"A3\"", shift("LOG10(A3)&\"A3\""))  // function names and strings are not references
        assertEquals("B3*C3", Formula.translate("B2*C2", 1, 0))
        assertEquals("C2*\$C\$2+\$B3", Formula.translate("B2*\$C\$2+\$B3", 0, 1))
        assertEquals("#REF!+1", Formula.translate("A1+1", -1, 0))
        assertNull(Formula.parse("1+").root)
        assertNull(Formula.parse("SUM(A1").root)
        assertFalse(Formula.parse("VLOOKUP(A1,B:C,2,0)").supported)
        assertFalse(Formula.parse("Rate*2").supported)               // a defined name
        assertNull(Formula.parse("SUM(Table1[Qty])").root)           // a structured reference
        assertTrue(Formula.parse("IF(A1>=10,ROUND(A1/3,2),\"-\")").supported)
    }
}
