package io.embodit.spatialoffice.core.csv

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun writeQuotesOnlyWhereNeeded() {
        assertEquals("a,b,c\r\n", Csv.write(listOf(listOf("a", "b", "c"))))
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",plain\r\n", Csv.write(listOf(listOf("a,b", "say \"hi\"", "two\nlines", "plain"))))
        assertEquals("\" lead\",\"trail \",mid dle\r\n", Csv.write(listOf(listOf(" lead", "trail ", "mid dle"))))
        assertEquals(",,\r\n\r\n", Csv.write(listOf(listOf("", "", ""), listOf(""))))
        assertEquals("", Csv.write(emptyList()))
        assertEquals("1\r\n2\r\n", Csv.write(listOf(listOf("1"), listOf("2"))))
        assertEquals("\"cr\rhere\"\r\n", Csv.write(listOf(listOf("cr\rhere"))))
    }

    @Test fun parseHandlesQuotesLineEndsAndBlanks() {
        assertEquals(listOf(listOf("a", "b", "c")), Csv.parse("a,b,c"))
        assertEquals(listOf(listOf("a", "b", "c")), Csv.parse("a,b,c\r\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\nc,d\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\rc,d"))
        assertEquals(listOf(listOf("a,b", "say \"hi\"", "two\r\nlines")), Csv.parse("\"a,b\",\"say \"\"hi\"\"\",\"two\r\nlines\"\r\n"))
        assertEquals(listOf(listOf("", "", "")), Csv.parse(",,"))
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), Csv.parse("a\r\n\r\nb"))
        assertEquals(emptyList<List<String>>(), Csv.parse(""))
        assertEquals(listOf(listOf("")), Csv.parse("\"\""))
        assertEquals(listOf(listOf("x", "y")), Csv.parse("﻿x,y"))
        assertEquals(listOf(listOf(" a ", "b")), Csv.parse(" a ,b"))
        assertEquals(listOf(listOf("ab", "c")), Csv.parse("\"a\"b,c"))
        assertEquals(listOf(listOf("日本語", "ñ", "😀")), Csv.parse("日本語,ñ,😀\n"))
    }

    @Test fun roundTrip() {
        val rows = listOf(
            listOf("Name", "Note", "Amount"),
            listOf("Ana, María", "said \"ok\"", "¥1,200.50"),
            listOf("", " padded ", "multi\nline\r\ntext"),
            listOf("only one"),
            listOf(""),
            listOf("\"", ",", "\n"),
        )
        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }
}
