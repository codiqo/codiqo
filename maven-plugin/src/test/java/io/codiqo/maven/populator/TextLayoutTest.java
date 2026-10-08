package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import com.github.freva.asciitable.AsciiTable;
import com.github.freva.asciitable.Column;
import com.github.freva.asciitable.HorizontalAlign;

import lombok.Value;

class TextLayoutTest {
    private final TextLayout layout = new TextLayout();

    @Test
    void padsAndFormatsNumbers() {
        assertEquals("ab   ", layout.left("ab", 5));
        assertEquals("63", layout.fixed(62.5, 0));
        assertEquals("0.13", layout.fixed(0.125, 2));
    }
    @Test
    void breaksAParagraphBetweenWordsOnly() {
        assertEquals(List.of("one two", StringUtils.repeat('x', 12), "three"), layout.words(" one \n two " + StringUtils.repeat('x', 12) + "  three", 8));
        assertEquals(List.of(), layout.words(" \n ", 8));
    }
    /** the template names the columns and picks each cell by property; the table is ascii-table's own */
    @Test
    void aTemplateDrawsATableThroughTheLibrary() {
        List<Row> rows = List.of(new Row("a/b/C.java", "1.50"), new Row("D.java", "10.00"));
        String expected = AsciiTable.getTable(AsciiTable.BASIC_ASCII_NO_DATA_SEPARATORS, rows, List.of(
                new Column().header("File").dataAlign(HorizontalAlign.LEFT).with(Row::getPath),
                new Column().header("Amount").dataAlign(HorizontalAlign.RIGHT).with(Row::getAmount)));

        assertEquals(expected, render("[(${#tables.compact({'File', 'Amount'}, {'LEFT', 'RIGHT'}, rows.{ {path, amount} })})]", rows));
    }
    /** an unwrapped column is as wide as its longest cell, where the library would otherwise wrap it */
    @Test
    void anUnwrappedColumnKeepsItsLongestCellOnOneLine() {
        List<Row> rows = List.of(new Row(StringUtils.repeat('x', 100), "1"));

        String wrapped = render("[(${#tables.grid({'File', 'Amount'}, {'LEFT', 'RIGHT'}, rows.{ {path, amount} })})]", rows);
        String unwrapped = render("[(${#tables.grid({'File', 'Amount'}, {'LEFT', 'RIGHT'}, rows.{ {path, amount} }, {'File'})})]", rows);

        assertEquals(6, wrapped.split(StringUtils.LF).length);
        assertEquals(5, unwrapped.split(StringUtils.LF).length);
    }
    private static String render(String template, List<Row> rows) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new TextLayoutDialect());

        Context ctx = new Context(Locale.ROOT);
        ctx.setVariable("rows", rows);
        return engine.process(template, ctx);
    }

    @Value
    public static class Row {
        String path;
        String amount;
    }
}
